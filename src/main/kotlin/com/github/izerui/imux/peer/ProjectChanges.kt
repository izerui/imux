package com.github.izerui.imux.peer

import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 返回项目范围内是否存在 Git 变更；非 Git 项目或检查失败返回 null。
 *
 * `-- .` 很重要：项目可能只是更大仓库中的一个子目录，外部目录的变更不应触发本项目副驾驶。
 */
internal fun projectHasChanges(
    projectPath: String,
    runStatus: (List<String>, Path, Long) -> Boolean? = ::runGitStatus,
): Boolean? =
    runStatus(
        listOf(
            "git",
            "-C",
            projectPath,
            "status",
            "--porcelain=v1",
            "--untracked-files=all",
            "--",
            ".",
        ),
        Path.of(projectPath),
        GIT_STATUS_TIMEOUT_SECONDS,
    )

private fun runGitStatus(
    command: List<String>,
    cwd: Path,
    timeoutSeconds: Long,
): Boolean? {
    var process: Process? = null
    return try {
        val started =
            ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        process = started
        started.outputStream.close()

        val hasOutput = AtomicBoolean(false)
        val finishedReading = CountDownLatch(1)
        Thread {
            runCatching {
                started.inputStream.use { input ->
                    val buffer = ByteArray(2_048)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) hasOutput.set(true)
                    }
                }
            }
            finishedReading.countDown()
        }.apply {
            isDaemon = true
            name = "imux-peer-git-status-reader"
        }.start()

        if (!started.waitFor(timeoutSeconds, TimeUnit.SECONDS)) return null
        finishedReading.await(READ_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (started.exitValue() != 0) null else hasOutput.get()
    } finally {
        process?.let(::destroyProcessTree)
    }
}

private const val GIT_STATUS_TIMEOUT_SECONDS = 5L
private const val READ_DRAIN_TIMEOUT_SECONDS = 1L
