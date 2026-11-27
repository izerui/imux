package com.github.izerui.imux.bridge.terminal

import com.github.izerui.imux.bridge.core.CodexReviewMenu
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.model.AgentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Path

class CommitPickerCatalogTest {
    @get:Rule val temp = TemporaryFolder()

    private fun File.git(vararg args: String) {
        val p = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.com",
            "-c", "commit.gpgSign=false") + args).directory(this).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText()
        assertEquals(output, 0, p.waitFor())
    }

    @Test fun `实际 Git 读取保留中文重复标题和提交顺序`() {
        val repo = temp.newFolder("repo with spaces")
        repo.git("init")
        for (subject in listOf("old subject", "重复", "重复")) repo.git("commit", "--allow-empty", "-m", subject)
        assertEquals(listOf("重复", "重复", "old subject"), gitCommitSubjects(repo.toPath()))
    }

    @Test fun `分支目录保留本地分支完整名字并排除标签和远程引用`() {
        val repo = temp.newFolder("branch repo")
        repo.git("init", "-b", "main")
        repo.git("commit", "--allow-empty", "-m", "subject")
        repo.git("branch", "topic/版本/v2")
        repo.git("tag", "release")
        repo.git("update-ref", "refs/remotes/origin/upstream", "HEAD")
        val target = SessionTarget(repo.path, "tab", AgentType.CODEX, null)
        assertEquals(listOf("main", "topic/版本/v2"), pickerEntriesForTab(target, GitPicker.BRANCH, { repo.toPath() }).sorted())
    }

    @Test fun `活动会话的工作目录优先且首轮没有记录时读取启动目录`() {
        val launch = temp.newFolder("launch").toPath()
        val active = temp.newFolder("active").toPath()
        val target = SessionTarget(launch.toString(), "tab", AgentType.CODEX, null)
        val reads = mutableListOf<Path>()
        val git: (Path) -> List<String> = { reads.add(it); listOf("subject") }
        assertEquals(listOf("subject"), pickerEntriesForTab(target, GitPicker.COMMIT, { assertEquals("tab", it); active }, git))
        assertEquals(listOf(active), reads)
        reads.clear()
        assertEquals(listOf("subject"), pickerEntriesForTab(target, GitPicker.COMMIT, { null }, git))
        assertEquals(listOf(launch), reads)
    }

    @Test fun `当前轮次记录的目录覆盖创建会话时的目录`() {
        val cwd = temp.newFolder("worktree").toPath()
        val file = temp.newFile()
        val payload = com.google.gson.JsonObject().apply { addProperty("cwd", cwd.toString()) }
        file.writeText("""{"type":"session_meta","payload":{"cwd":"old"}}""" + "\n" +
            """{"type":"turn_context","payload":$payload}""")
        assertEquals(cwd, codexReviewCwd(file.toPath()))
    }

    @Test fun `加载期间移动光标后用新位置回答且重开菜单刷新目录`() = runBlocking {
        fun screen(a: String, b: String) =
            "Select a commit to review\n  Type to search commits\n${a}long\n  subject\n${b}last\nenter select · esc back"
        var viewport = screen("› ", "  ")
        val key = Any()
        val cache = GitPickerScreens<Any>()
        val caller = Thread.currentThread()
        var scans = 0
        suspend fun read() = cache.read(key, { viewport }) {
            assertNotSame(caller, Thread.currentThread())
            scans++
            viewport = screen("  ", "› ")
            listOf("long subject", "last")
        }
        val menu = CodexReviewMenu.parse(read()!!)!!
        assertEquals(listOf("long subject", "last"), menu.options)
        assertEquals(listOf("\u001b[A", "\r"), menu.keysFor("1"))
        read()
        assertEquals(1, scans)
        viewport = "prompt"
        assertEquals("prompt", read())
        viewport = screen("› ", "  ")
        read()
        assertEquals(2, scans)
    }

    @Test fun `加载提交期间换到分支后按分支还原选项并在切回提交时刷新`() = runBlocking {
        val commit = "Select a commit to review\n  Type to search commits\n› topic/\n  name\nenter select · esc back"
        val branch = "Select a base branch\n  Type to search branches\n› topic/\n  name\n  main\nenter select · esc back"
        val key = Any()
        val cache = GitPickerScreens<Any>()
        var viewport = commit
        val scans = mutableListOf<GitPicker>()
        suspend fun read() = CodexReviewMenu.parse(cache.read(key, { viewport }) { picker ->
            scans.add(picker)
            when (picker) {
                GitPicker.COMMIT -> {
                    if (scans.size == 1) viewport = branch
                    listOf("topic/ name")
                }
                GitPicker.BRANCH -> listOf("topic/name", "main")
            }
        }!!)!!
        assertEquals(listOf("topic/name", "main"), read().options)
        assertEquals(listOf("topic/name", "main"), read().options)
        viewport = commit
        assertEquals(listOf("topic/ name"), read().options)
        assertEquals(listOf(GitPicker.COMMIT, GitPicker.BRANCH, GitPicker.COMMIT), scans)
    }
}
