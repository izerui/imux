package com.github.izerui.imux.peer

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlin.time.measureTime

class PeerCliTest {
    @Test
    fun `Claude 副驾驶启用默认工具并使用只读权限`() {
        val script = peerCliCommand("/bin/zsh", AgentType.CLAUDE, "/tmp/project").last()

        assertTrue(script.contains("--safe-mode"))
        assertTrue(script.contains("--permission-mode plan"))
        assertTrue(script.contains("--tools default"))
        assertTrue(script.contains("--no-session-persistence"))
    }

    @Test
    fun `Codex 副驾驶使用只读沙箱并且不保留会话`() {
        val script = peerCliCommand("/bin/zsh", AgentType.CODEX, "/tmp/project").last()

        assertTrue(script.contains("--sandbox read-only"))
        assertTrue(script.contains("--ephemeral"))
        assertTrue(script.contains("-C '/tmp/project'"))
    }

    @Test
    fun `Pi 副驾驶启用只读检查工具并且不保留会话`() {
        val script = peerCliCommand("/bin/zsh", AgentType.PI, "/tmp/project").last()

        assertTrue(script.contains("--tools read,grep,find,ls"))
        assertTrue(!script.contains("--no-tools"))
        assertTrue(script.contains("--no-session"))
    }

    @Test
    fun `PowerShell 命令使用对应方言参数`() {
        val command = peerCliCommand("powershell.exe", AgentType.CODEX, "C:\\work dir")

        assertTrue(command.contains("-ExecutionPolicy"))
        assertTrue(command.last().contains("Write-Output '$PEER_OUTPUT_MARKER'"))
        assertTrue(command.last().contains("-C 'C:\\work dir'"))
    }

    @Test
    fun `shell 启动输出不会进入副驾驶反馈`() {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command =
            listOf(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                PeerCliTest::class.java.name,
                "output",
            )

        val output = runPeerCli(command, Path.of("."), "prompt", 5) {}

        assertEquals("真正的副驾驶反馈", output)
    }

    @Test
    fun `CLI 超时会在进程关闭输出流之前返回`() {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command =
            listOf(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                PeerCliTest::class.java.name,
                "sleep",
            )

        var thrown: PeerCliException? = null
        val elapsed =
            measureTime {
                try {
                    runPeerCli(command, Path.of("."), "x".repeat(1_000_000), 1) {}
                } catch (e: PeerCliException) {
                    thrown = e
                }
            }

        assertTrue("超时应抛出 PeerCliException", thrown != null)
        assertTrue("异常消息应包含超时信息", thrown!!.message!!.contains("timed out"))
        assertTrue("超时应在数秒内生效，实际 $elapsed", elapsed.inWholeSeconds < 5)
    }

    @Test
    fun `非零退出码携带 stderr 内容进入异常消息`() {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command =
            listOf(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                PeerCliTest::class.java.name,
                "fail",
            )

        var thrown: PeerCliException? = null
        try {
            runPeerCli(command, Path.of("."), "prompt", 5) {}
        } catch (e: PeerCliException) {
            thrown = e
        }

        assertTrue("非零退出应抛出 PeerCliException", thrown != null)
        assertTrue(
            "异常消息应包含 stderr 内容，实际: ${thrown!!.message}",
            thrown.message!!.contains("authentication failed"),
        )
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            when (args.firstOrNull()) {
                "sleep" -> Thread.sleep(30_000)
                "output" -> {
                    println("[proxy] enabled via 127.0.0.1:7890")
                    println(PEER_OUTPUT_MARKER)
                    println("真正的副驾驶反馈")
                }

                "fail" -> {
                    System.err.print("authentication failed: invalid token")
                    exitProcess(3)
                }
            }
        }
    }
}
