package com.github.izerui.imux

import com.github.izerui.imux.bridge.mcp.MobileMcpProtocol
import com.github.izerui.imux.clarification.ClarificationRecord
import com.github.izerui.imux.clarification.ContentOrigin
import com.github.izerui.imux.clarification.ReviewedDraft
import com.github.izerui.imux.clarification.answersMessage
import com.github.izerui.imux.clarification.continueMessage
import com.github.izerui.imux.clarification.correctionMessage
import com.github.izerui.imux.clarification.loadGuidance
import com.github.izerui.imux.clarification.openingMessage
import com.github.izerui.imux.code.CodeMcpProtocol
import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.overview.TrailCursor
import com.github.izerui.imux.overview.report.reportPrompt
import com.github.izerui.imux.peer.mcp.PeerMcpProtocol
import com.github.izerui.imux.session.peerFeedbackMessage
import com.github.izerui.imux.session.titleGenerationPrompt
import com.github.izerui.imux.settings.GuidanceRole
import com.github.izerui.imux.settings.PluginLanguage
import com.github.izerui.imux.settings.agentGuidance
import com.github.izerui.imux.settings.defaultPeerPromptForLanguage
import com.github.izerui.imux.settings.defaultPlainReplyGuidanceForLanguage
import com.github.izerui.imux.terminal.handoffPrompt
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * 把插件对智能体说的每一段话原样写到 `build/prompt-review/`（`ZH.md`、`EN.md`、`tools.md`），供人通读审阅。
 *
 * 随测试一起生成：提示词散在十来个文件里、按开关和角色拼装，只看源码看不出智能体最终读到什么；
 * 手工导出的版本改一次就过时。任务、对话、路径处是占位内容。
 */
class PromptReviewDumpTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val out: Path = Path.of("build", "prompt-review")

    @Test
    fun `中文和英文的提示词原文、三组工具说明都写到审阅目录`() {
        Files.createDirectories(out)
        Files.writeString(out.resolve("ZH.md"), prompts(chinese = true))
        Files.writeString(out.resolve("EN.md"), prompts(chinese = false))
        Files.writeString(out.resolve("tools.md"), tools())
        assertTrue(Files.readString(out.resolve("ZH.md")).contains("## 主会话 · 两组工具同时开启"))
        assertTrue(Files.readString(out.resolve("EN.md")).contains("## 主会话 · 两组工具同时开启"))
        assertTrue(Files.readString(out.resolve("tools.md")).contains("## imux_code"))
    }

    private fun prompts(chinese: Boolean): String = buildString {
        val language = if (chinese) PluginLanguage.SIMPLIFIED_CHINESE else PluginLanguage.ENGLISH
        append("# imux 提示词原文 · ").append(if (chinese) "ZH" else "EN").append("\n\n")
        append("由 `PromptReviewDumpTest` 随测试生成，不要手改。任务、对话、路径使用占位内容。\n")
        append("工具引导词按「挂了哪些工具 × 这个智能体是来干什么的」拼装：两组工具都关闭时为空；会话报告不带引导词。回复风格由独立开关决定。\n\n")

        val combos = listOf("两组工具同时开启" to (true to true), "只开启 IDEA MCP" to (true to false), "只开启 imux_code" to (false to true))
        for ((name, combo) in combos) {
            section("主会话 · $name", agentGuidance(chinese, combo.first, combo.second, GuidanceRole.WORKER)!!)
        }
        for ((name, combo) in combos) {
            section("只查不改（副驾驶审查、先聊再做）· $name", agentGuidance(chinese, combo.first, combo.second, GuidanceRole.INVESTIGATOR)!!)
        }
        section("回复风格（主会话）", defaultPlainReplyGuidanceForLanguage(language))

        section("结对与单次审查的副驾驶", defaultPeerPromptForLanguage(language))
        section("单次审查填写了额外要求时追加", (if (chinese) "## 本次审查要求" else "## Review Instructions") + "\n\n\${oneShotInstruction}")
        section("副驾驶的意见发进主会话时的样子", peerFeedbackMessage("\${feedback}", chinese))

        val draft = ReviewedDraft("\${editedText}", "\${originalText}", ContentOrigin.AGENT, 0)
        section("先聊再做 · 首次讨论", openingMessage(ClarificationRecord("\${requirement}"), chinese, loadGuidance(chinese)))
        section("先聊再做 · 回答后（回答逐行列在中间）", answersMessage(emptyList(), chinese))
        section("先聊再做 · 继续讨论", continueMessage(chinese, draft))
        section("先聊再做 · 格式纠正", correctionMessage(listOf("\${validationError}"), chinese))

        val file = temp.newFile(if (chinese) "zh.jsonl" else "en.jsonl").toPath()
        val session = AgentSession("\${sessionId}", "\${session.title}", AgentType.CLAUDE, Instant.EPOCH, Instant.EPOCH, Path.of("/path/to/session.jsonl"))
        section("转移会话", handoffPrompt(session, language))
        section("生成会话标题 · 固定使用英文", titleGenerationPrompt(session.copy(filePath = file)))
        for (type in AgentType.entries) {
            section(
                "会话报告 · ${type.name} · 首次（增量时只换「读哪几行」那一段）",
                reportPrompt(chinese, language, type, Path.of("/path/to/session.jsonl"), null, TrailCursor(100, 0)),
            )
        }
    }

    private fun tools(): String = buildString {
        append("# imux 内置 MCP 工具说明\n\n由 `PromptReviewDumpTest` 随测试生成，不要手改。以下是各服务向智能体公开的工具说明、输入参数和标记原文。\n\n")
        section("imux_code", pretty(CodeMcpProtocol.tools()))
        section("imux_session", pretty(PeerMcpProtocol.tools()))
        section("imux_mobile", pretty(MobileMcpProtocol.tools()))
    }

    private fun pretty(tools: JsonArray): String = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(tools)

    private fun StringBuilder.section(title: String, body: String) {
        append("## ").append(title).append("\n\n````text\n").append(body.trim()).append("\n````\n\n")
    }
}
