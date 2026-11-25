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

    /**
     * 同一份引导词不能原样发给所有角色：副驾驶被禁止改动，却曾经拿到教它重命名、跑构建、执行 SQL 的完整版；
     * 会话报告只读一个记录文件，却曾经带着整套 IDE 工具说明。
     */
    @Test
    fun `副驾驶和需求澄清拿只查不改的引导词，会话报告什么都不挂，会话终端拿完整版`() {
        val peer = SourceCode("src/main/kotlin/com/github/izerui/imux/peer/PeerCoordinator.kt").normalized
        assertTrue(peer.contains("capabilities = codeTools?.guidance.orEmpty(), role = GuidanceRole.INVESTIGATOR"))
        val oneShot = SourceCode("src/main/kotlin/com/github/izerui/imux/agent/OneShotAgentCommand.kt").normalized
        assertTrue(oneShot.contains("configuredAgentGuidance(endpoint != null, codeTools?.guidance.orEmpty(), GuidanceRole.INVESTIGATOR)"))
        val clarify = SourceCode("src/main/kotlin/com/github/izerui/imux/clarification/ClarificationService.kt").normalized
        assertTrue(clarify.contains("AgentMcpConfig.investigator(project, projectPath, agentType)"))
        val report = SourceCode("src/main/kotlin/com/github/izerui/imux/overview/ProgressOverview.kt").normalized
        assertTrue(report.contains("AgentMcpConfig.NONE,"))
        assertFalse(report.contains("AgentMcpConfig.investigator"))
        val host = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt").normalized
        assertFalse("会话终端用默认的干活角色", host.contains("GuidanceRole.INVESTIGATOR"))
    }

    @Test
    fun `审查意见带上固定开头再发进主会话，记进结果的仍是意见原文`() {
        val peer = SourceCode("src/main/kotlin/com/github/izerui/imux/peer/PeerCoordinator.kt").normalized
        assertTrue(peer.contains("val message = peerFeedbackMessage(feedback, ImuxBundle.currentLanguage().isChinese())"))
        assertTrue(peer.contains("if (injectFeedback(mainSessionKey, message, run, guard)) { recordOutcome(mainSessionKey, run, outcome(PeerOutcomeKind.FEEDBACK), feedback) }"))
    }
}
