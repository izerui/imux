package com.github.izerui.imux.bridge.terminal

import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.bridge.core.normalizeGitPickerScreen
import com.github.izerui.imux.bridge.core.reviewPickerTitle
import com.github.izerui.imux.session.JsonLineScanner
import com.github.izerui.imux.session.codexPids
import com.github.izerui.imux.session.readHeldRollouts
import com.github.izerui.imux.session.readTabId
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.WeakHashMap
import kotlin.coroutines.cancellation.CancellationException

internal enum class GitPicker(val title: String) {
    COMMIT("Select a commit to review"), BRANCH("Select a base branch")
}

/** Called on the EDT. Only the catalog load leaves it; input decisions use a fresh viewport after that suspension. */
internal class GitPickerScreens<K : Any> {
    private class Page(val picker: GitPicker) {
        val lock = Mutex()
        var entries: List<String>? = null
    }
    private val pages = WeakHashMap<K, Page>()

    fun forget(key: K) {
        pages.remove(key)
    }

    suspend fun read(key: K, raw: () -> String?, load: (GitPicker) -> List<String>): String? {
        while (true) {
            val screen = raw()
            val title = screen?.let(::reviewPickerTitle)
            val picker = GitPicker.entries.firstOrNull { it.title == title }
            if (screen == null || picker == null) {
                pages.remove(key)
                return screen
            }
            val page = pages[key]?.takeIf { it.picker == picker } ?: Page(picker).also { pages[key] = it }
            page.entries?.let { return normalizeGitPickerScreen(screen, it) }
            page.lock.withLock {
                if (page.entries == null) page.entries = withContext(Dispatchers.IO) { load(picker) }
            }
            // Both waiting for another reader and loading Git can outlive this viewport.
            if (pages[key] !== page) {
                val current = raw() ?: return null
                val active = pages[key]?.takeIf { it.picker.title == reviewPickerTitle(current) }
                return normalizeGitPickerScreen(current, active?.entries.orEmpty())
            }
        }
    }
}

internal fun pickerEntriesForTab(
    target: SessionTarget,
    picker: GitPicker,
    liveCwd: (String) -> Path? = ::codexTabCwd,
    entries: (Path) -> List<String> = when (picker) {
        GitPicker.COMMIT -> ::gitCommitSubjects
        GitPicker.BRANCH -> ::gitLocalBranches
    },
): List<String> =
    catalogAttempt {
        val cwd = catalogAttempt { liveCwd(target.tabId) } ?: Path.of(target.projectPath)
        cwd.takeIf(Path::isAbsolute)?.let(entries)
    }.orEmpty()

private fun codexTabCwd(tabId: String): Path? {
    val pid = codexPids().singleOrNull { readTabId(it) == tabId } ?: return null
    val rollout = readHeldRollouts(pid).distinct().singleOrNull() ?: return null
    return codexReviewCwd(Path.of(rollout))
}

private inline fun <T> catalogAttempt(read: () -> T?): T? =
    try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

internal fun codexReviewCwd(rollout: Path): Path? =
    Files.newBufferedReader(rollout).useLines { lines ->
        var cwd: String? = null
        for (line in lines) {
            when (JsonLineScanner.topLevelStringValue(line, "type")) {
                "session_meta", "turn_context" -> cwd = JsonLineScanner.objectStringValue(line, "payload", "cwd")
            }
        }
        cwd?.let(Path::of)?.takeIf(Path::isAbsolute)
    }

internal fun gitCommitSubjects(cwd: Path): List<String> {
    val output = gitOutput(cwd, "log", "-100", "--no-color",
        "--no-show-signature", "--no-notes", "--no-decorate", "--encoding=UTF-8", "-z", "--format=%s", "--",
    ) ?: return emptyList()
    if (!output.endsWith('\u0000')) return emptyList()
    return output.dropLast(1).split('\u0000')
}

internal fun gitLocalBranches(cwd: Path): List<String> =
    gitOutput(cwd, "for-each-ref", "--format=%(refname:lstrip=2)", "refs/heads")
        ?.lineSequence()?.filter(String::isNotEmpty)?.toList().orEmpty()

private fun gitOutput(cwd: Path, vararg args: String): String? {
    val command = GeneralCommandLine("git", "--no-pager", "--no-optional-locks", *args)
        .withWorkDirectory(cwd.toFile()).withCharset(Charsets.UTF_8)
        .withEnvironment("GIT_NO_LAZY_FETCH", "1")
        .withEnvironment("GIT_TERMINAL_PROMPT", "0")
    val result = ExecUtil.execAndGetOutput(command, 3000)
    return result.stdout.takeIf { result.isExitCodeSet && result.exitCode == 0 && !result.isTimeout }
}
