package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 终端只认识通用的附加启动配置：要带哪部分引导词由各功能在 [TerminalLaunchContribution.guidance] 里声明，
 * 终端不能回到「按服务名认功能」的写法（以前靠 `servers.any { it.name == "imux_code" }` 判断）。
 */
class TerminalDecouplingSourceTest {
    private val terminalSources = File("src/main/kotlin/com/github/izerui/imux/terminal").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `终端包的每个源文件都不认识查代码工具的服务名，也不引用查代码功能包`() {
        assertTrue("找不到终端包源文件，下面的断言会恒真", terminalSources.size > 5)
        assertTrue("子目录没扫到", terminalSources.any { it.parentFile.name != "terminal" })
        for (file in terminalSources) {
            val code = SourceCode(file.path).normalized
            assertFalse(file.name, code.contains("CODE_TOOLS_SERVER"))
            assertFalse(file.name, code.contains("\"imux_code\""))
            assertFalse(file.name, code.contains("com.github.izerui.imux.code."))
        }
    }

    @Test
    fun `终端按启动配置里声明的引导能力拼引导词`() {
        val host = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt")
        assertTrue(host.normalized.contains("capabilities = launchContributions[tabId]?.guidance.orEmpty()"))
    }
}
