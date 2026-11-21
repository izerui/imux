package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Path

class CliCapabilityCacheTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `帮助按程序缓存且安装更新后重新探测`() {
        assumeTrue(File("/bin/sh").canExecute())
        val script = Path.of("src/main/cli-capabilities/cli-help.sh").toAbsolutePath()
        for (agent in AgentType.entries) {
            val cli = temp.newFile(agent.cli)
            val calls = temp.newFile("${agent.cli}.calls")
            fun install(output: String, exit: Int = 0) {
                cli.writeText("#!/bin/sh\necho probe >> '${calls.path}'\necho '$output'\nexit $exit\n")
                check(cli.setExecutable(true))
            }
            fun help(): String {
                val command = cliHelpCommand(ShellDialect.POSIX, agent.cli, script)
                val builder = ProcessBuilder("/bin/sh", "-c", command)
                builder.environment()["PATH"] = "${temp.root}:${System.getenv("PATH")}"
                builder.environment()["XDG_CACHE_HOME"] = temp.root.path
                val process = builder.start()
                val result = process.inputStream.bufferedReader().use { it.readText().trim() }
                process.waitFor()
                return result
            }
            install("broken", 1)
            assertEquals("", help())
            assertEquals("", help())
            assertEquals(2, calls.readLines().size)
            install("--first")
            assertEquals("--first", help())
            assertEquals("--first", help())
            assertEquals(3, calls.readLines().size)
            install("--second-feature")
            assertEquals("--second-feature", help())
            assertEquals(4, calls.readLines().size)
        }
    }
}
