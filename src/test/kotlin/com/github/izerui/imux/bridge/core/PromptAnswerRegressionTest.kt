package com.github.izerui.imux.bridge.core

import com.github.izerui.imux.model.AgentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 手机作答的回归：普通词不当控制键、长数字不抛异常、原文不被改写。 */
class PromptAnswerRegressionTest {
    private val rule = "─".repeat(100)

    /** Claude Code 2.1.308 单选题画面，光标在 `4. Chat about this`。 */
    private val single =
        ScreenPrompts.extract(
            """
            $rule
             ☐ 宠物

            你更喜欢哪种宠物？

              1. cat
                 猫
              2. dog
                 狗
              3. Type something.
            $rule
            ❯ 4. Chat about this

            Enter to select · ↑/↓ to navigate · ctrl+g to edit in Vim · Esc to cancel
            """.trimIndent(),
        )!!

    private val focused = single.copy(freeTextFocused = true, cursorItem = 3)

    private fun pasted(text: String) = "\u001b[200~$text\u001b[201~"

    @Test
    fun `取消、确认、ESC、回车作为普通文字填进输入项，不会退出选择框`() {
        for (reply in listOf("取消", "确认", "ESC", "回车", "ok")) {
            assertEquals(reply, PromptAnswer.Keys(listOf("\u001b[A", pasted(reply), ScreenPrompts.ENTER)), ScreenPrompts.answer(single, reply))
        }
        assertEquals(PromptAnswer.Keys(listOf(pasted("取消"), ScreenPrompts.ENTER)), ScreenPrompts.answer(focused, "取消"))
    }

    @Test
    fun `长数字不抛异常：对不上选项的数字与「选 99」都把原话填进自定义输入项`() {
        for (reply in listOf("13800138000", "电话 13800138000", "选 13800138000", "选 99")) {
            assertEquals(reply, PromptAnswer.Keys(listOf("\u001b[A", pasted(reply), ScreenPrompts.ENTER)), ScreenPrompts.answer(single, reply))
        }
    }

    @Test
    fun `原文的换行与首尾空格原样粘贴，ESC 字符被去掉以免提前结束粘贴`() {
        assertEquals(
            PromptAnswer.Keys(listOf("\u001b[A", pasted("  仓鼠\n小的那种  要两只 "), ScreenPrompts.ENTER)),
            ScreenPrompts.answer(single, "  仓鼠\n小的那种  要两只 "),
        )
        assertEquals(PromptAnswer.Keys(listOf("\u001b[A", pasted("a[201~b"), ScreenPrompts.ENTER)), ScreenPrompts.answer(single, "a\u001b[201~b"))
    }

    /**
     * 输入框里已有两行、编辑光标在第二行末尾时回「选 1」。Claude Code 2.1.308 实测（tmux）：
     * - 单选：按一次 ↑ 光标直接离开输入项落到 `2. dog`（操作提示里的 `ctrl+g to edit in Vim` 随之消失），
     *   不是在输入框内移到第一行；再按 `1`，CLI 收到的答案是 `'cat'`，两行草稿没有混进去；
     * - 多选：同样一次 ↑ 离开到 `2. pear`，按 `1` 勾上 apple，草稿项仍勾着；`下一题` 后在确认页按 `1`，
     *   CLI 收到 `'apple, 猕猴桃\n要软的'`。
     * 下面用当时的画面，确认解析器认出「光标在多行输入项上」并给出 ↑ + 编号，↑ 之后的画面认出已离开。
     */
    private val singleTwoLines =
        """
        $rule
         ☐ pet

        你更喜欢哪种宠物？

          1. cat
             猫
          2. dog
             狗
        ❯ 3. 仓鼠
             小的那种
        $rule
          4. Chat about this

        Enter to select · ↑/↓ to navigate · ctrl+g to edit in Vim · Esc to cancel
        """.trimIndent()

    private val multiTwoLines =
        """
        $rule
        ←  ☐ fruits  ✔ Submit  →

        你喜欢哪些水果？

          1. [ ] apple
                 苹果
          2. [ ] pear
                 梨
        ❯ 3. [✔] 猕猴桃
                 要软的
             Submit
        $rule
          4. Chat about this

        Enter to select · ↑/↓ to navigate · ctrl+g to edit in Vim · Esc to cancel
        """.trimIndent()

    @Test
    fun `多行草稿的输入项聚焦时回「选 1」先按一次上键离开再按编号：单选与多选`() {
        for (screen in listOf(singleTwoLines, multiTwoLines)) {
            val prompt = ScreenPrompts.extract(screen)!!
            assertEquals(3, prompt.freeTextOption)
            assertEquals(true, prompt.freeTextFocused)
        }
        assertEquals(PromptAnswer.Keys(listOf("\u001b[A", "1")), ScreenPrompts.answer(ScreenPrompts.extract(singleTwoLines)!!, "选 1"))
        // 多选：↑ 离开后光标在第 2 项，勾上 1（草稿那一项的勾不动），再 ↓↓ 到 Submit 回车提交
        assertEquals(
            PromptAnswer.Keys(listOf("\u001b[A", "1", "\u001b[B", "\u001b[B", ScreenPrompts.ENTER)),
            ScreenPrompts.answer(ScreenPrompts.extract(multiTwoLines)!!, "选 1"),
        )
    }

    @Test
    fun `按上键后的实测画面认出光标已离开输入项、停在第 2 项`() {
        val afterUp =
            singleTwoLines
                .replace("  2. dog", "❯ 2. dog")
                .replace("❯ 3. 仓鼠", "  3. 仓鼠")
                .replace(" · ctrl+g to edit in Vim", "")
        val prompt = ScreenPrompts.extract(afterUp)!!
        assertEquals(false, prompt.freeTextFocused)
        assertEquals(2, prompt.cursorItem)
        assertEquals(PromptAnswer.Keys(listOf("1")), ScreenPrompts.answer(prompt, "选 1"))
    }

    /**
     * 草稿是编号列表时（Claude Code 2.1.308 实测，tmux）：草稿续行缩进到选项文字那一列（`     1. 保留颜色`），
     * 真实选项的编号在第 2 列。光标在 `4. Chat about this` 上按一次 ↑ 就回到输入项；再粘贴「（补充）」并回车，
     * CLI 收到 `'要求如下\n1. 保留颜色\n2. 不要黑色（补充）'`——草稿行不占光标位置。
     */
    private val numberedDraftOnChat =
        """
        $rule
         ☐ pet

        你更喜欢哪种宠物？

          1. cat
             猫
          2. dog
             狗
          3. 要求如下
             1. 保留颜色
             2. 不要黑色
        $rule
        ❯ 4. Chat about this

        Enter to select · ↑/↓ to navigate · ctrl+g to edit in Vim · Esc to cancel
        """.trimIndent()

    @Test
    fun `编号列表草稿不算选项：从 Chat about this 回到输入项只按一次上键`() {
        val prompt = ScreenPrompts.extract(numberedDraftOnChat)!!
        assertEquals(listOf(1, 2, 3, 4), prompt.items)
        assertEquals(setOf(1, 2, 3, 4), prompt.options)
        assertEquals(3, prompt.freeTextOption)
        assertEquals(4, prompt.cursorItem)
        assertEquals(PromptAnswer.Keys(listOf("\u001b[A", pasted("（补充）"), ScreenPrompts.ENTER)), ScreenPrompts.answer(prompt, "（补充）"))
    }

    @Test
    fun `编号列表草稿的输入项聚焦时认得出，「选 1」只按一次上键`() {
        val focusedDraft = numberedDraftOnChat.replace("❯ 4. Chat", "  4. Chat").replace("  3. 要求如下", "❯ 3. 要求如下")
        val prompt = ScreenPrompts.extract(focusedDraft)!!
        assertEquals(true, prompt.freeTextFocused)
        assertEquals(PromptAnswer.Keys(listOf("\u001b[A", "1")), ScreenPrompts.answer(prompt, "选 1"))
    }

    @Test
    fun `多选题的编号列表草稿与单独一行的 Submit 草稿都不算光标停靠行`() {
        // 由实测多选多行画面（续行缩进到第 9 列，`Submit` 在第 5 列）改写草稿内容
        val screen =
            """
            $rule
            ←  ☐ fruits  ✔ Submit  →

            你喜欢哪些水果？

              1. [ ] apple
                     苹果
              2. [ ] pear
                     梨
              3. [✔] 要求如下
                     1. 保留颜色
                     Submit
                 Submit
            $rule
            ❯ 4. Chat about this

            Enter to select · ↑/↓ to navigate · ctrl+g to edit in Vim · Esc to cancel
            """.trimIndent()
        val prompt = ScreenPrompts.extract(screen)!!
        assertEquals(listOf(1, 2, 3, ScreenPrompt.SUBMIT, 4), prompt.items)
        assertEquals(setOf(1, 2, 3), prompt.checkboxOptions)
        assertEquals(
            PromptAnswer.Keys(listOf("\u001b[A", "\u001b[A", pasted("xy"), "\u001b[B", ScreenPrompts.ENTER)),
            ScreenPrompts.answer(prompt, "xy"),
        )
    }

    @Test
    fun `排版时草稿里的编号行接在输入项后面，不显示成另一个选项`() {
        val rendered = ScreenPrompts.render(ScreenPrompts.extract(numberedDraftOnChat)!!, markdown = false)
        assertEquals(true, rendered.contains("1. cat · 猫\n2. dog · 狗\n3. 要求如下 · 1. 保留颜色 · 2. 不要黑色"))
    }

    /** 题目本身以 `1. ` 开头（第 0 列），真实选项编号在第 2 列；按实测单选画面的列位置改写题目。 */
    private val numberedQuestion =
        """
        $rule
         ☐ 通知

        1. 请选择通知渠道

        ❯ 1. macOS 通知中心
             系统自带
          2. Webhook
             发 POST 请求
          3. Type something.
        $rule
          4. Chat about this

        Enter to select · ↑/↓ to navigate · Esc to cancel
        """.trimIndent()

    @Test
    fun `题目以编号开头时按光标行定列：选项集合正确，普通文字仍进入输入项`() {
        val prompt = ScreenPrompts.extract(numberedQuestion)!!
        assertEquals(setOf(1, 2, 3, 4), prompt.options)
        assertEquals(listOf(1, 2, 3, 4), prompt.items)
        assertEquals(3, prompt.freeTextOption)
        assertEquals(
            PromptAnswer.Keys(listOf("\u001b[B", "\u001b[B", pasted("企业微信"), ScreenPrompts.ENTER)),
            ScreenPrompts.answer(prompt, "企业微信"),
        )
        assertEquals(PromptAnswer.Keys(listOf("2")), ScreenPrompts.answer(prompt, "选 2"))
    }

    @Test
    fun `题目以编号开头、光标在 Chat about this 上时同样定对列`() {
        val onChat = numberedQuestion.replace("❯ 1. macOS", "  1. macOS").replace("  4. Chat", "❯ 4. Chat")
        val prompt = ScreenPrompts.extract(onChat)!!
        assertEquals(setOf(1, 2, 3, 4), prompt.options)
        assertEquals(PromptAnswer.Keys(listOf("\u001b[A", pasted("企业微信"), ScreenPrompts.ENTER)), ScreenPrompts.answer(prompt, "企业微信"))
    }

    @Test
    fun `题目以编号开头的多选题、光标在 Submit 上时由 Submit 的列推出编号列`() {
        val screen =
            """
            $rule
            ←  ☐ 渠道  ✔ Submit  →

            1. 请选择通知渠道（可多选）

              1. [ ] macOS 通知中心
              2. [ ] Webhook
              3. [ ] Type something
            ❯    Submit
            $rule
              4. Chat about this

            Enter to select · ↑/↓ to navigate · Esc to cancel
            """.trimIndent()
        val prompt = ScreenPrompts.extract(screen)!!
        assertEquals(setOf(1, 2, 3, 4), prompt.options)
        assertEquals(setOf(1, 2, 3), prompt.checkboxOptions)
        assertEquals(ScreenPrompt.SUBMIT, prompt.cursorItem)
        assertEquals(
            PromptAnswer.Keys(listOf("\u001b[A", pasted("企业微信"), "\u001b[B", ScreenPrompts.ENTER)),
            ScreenPrompts.answer(prompt, "企业微信"),
        )
    }

    @Test
    fun `题目以编号开头时排版不把题目当成选项`() {
        val rendered = ScreenPrompts.render(ScreenPrompts.extract(numberedQuestion)!!, markdown = true)
        assertEquals(true, rendered.contains("1\\. 请选择通知渠道"))
        assertEquals(true, rendered.contains("**1.** macOS 通知中心 · 系统自带  \n**2.** Webhook · 发 POST 请求"))
    }

    /**
     * 两道单选题的表单（Claude Code 2.1.308 实测，tmux）：光标在第一题的 Type something 上（输入框聚焦）时按 →，
     * 仍停在第一题；先按 ↑ 离开输入项再按 →，跳到第二题。
     */
    @Test
    fun `输入框聚焦时「下一题」「按回车」也只是原文，填进输入项提交`() {
        assertEquals(PromptAnswer.Keys(listOf(pasted("下一题"), ScreenPrompts.ENTER)), ScreenPrompts.answer(focused, "下一题"))
        assertEquals(PromptAnswer.Keys(listOf(pasted("按回车"), ScreenPrompts.ENTER)), ScreenPrompts.answer(focused, "按回车"))
    }

    /**
     * 多选题回「选 2」而屏幕上已勾了 1（Claude Code 2.1.308 实测，tmux）：按 `1` 取消、`2` 勾上、↓↓↓ 到 Submit 回车，
     * 确认页只剩 pear，提交后 CLI 收到 `'pear'`。
     */
    @Test
    fun `多选题按屏幕上的勾选状态改成恰好选中的几项：已勾而未选的取消`() {
        val prechecked = RealScreens.MULTI_SELECT.replace("1. [ ] 苹果", "1. [✔] 苹果")
        val prompt = ScreenPrompts.extract(prechecked)!!
        assertEquals(setOf(1), prompt.checkedOptions)
        assertEquals(
            PromptAnswer.Keys(listOf("1", "2", "\u001b[B", "\u001b[B", "\u001b[B", ScreenPrompts.ENTER)),
            ScreenPrompts.answer(prompt, "选 2"),
        )
        assertEquals(PromptAnswer.Keys(listOf("\u001b[B", "\u001b[B", "\u001b[B", ScreenPrompts.ENTER)), ScreenPrompts.answer(prompt, "选 1"))
    }

    @Test
    fun `多选题里单独选 Chat about this 这类离开本题的项只按它的编号，与勾选项混选时当作原话`() {
        val prompt = ScreenPrompts.extract(RealScreens.MULTI_SELECT)!!
        assertEquals(PromptAnswer.Keys(listOf("4")), ScreenPrompts.answer(prompt, "选 4"))
        assertEquals(
            PromptAnswer.Keys(listOf("\u001b[B", "\u001b[B", pasted("选 4 1"), "\u001b[B", ScreenPrompts.ENTER)),
            ScreenPrompts.answer(prompt, "选 4 1"),
        )
    }

    @Test
    fun `路由器：只回长数字也送进自定义输入项、不报离线，交给终端的是未经 trim 的原文`() =
        runBlocking {
            val robot = RobotId("r1")
            val owner = RobotOwner("me", "chat-1")
            val registry = BindingRegistry().apply { bind(robot, SessionTarget("/p", "tab-1", AgentType.CLAUDE, "s1")) }
            val pressed = mutableListOf<String>()
            val sent = mutableListOf<String>()
            val port =
                object : TerminalSessionPort {
                    var reads = 0

                    override suspend fun submit(
                        target: SessionTarget,
                        text: String,
                    ) = error("选择框开着时不应走普通提交")

                    override suspend fun prompt(target: SessionTarget) = if (reads++ % 2 == 0) single else null

                    override suspend fun press(
                        target: SessionTarget,
                        keys: String,
                    ): SubmitResult {
                        pressed += keys
                        return SubmitResult.SUBMITTED_TO_TERMINAL
                    }
                }
            val channel =
                object : ChannelAdapter {
                    override val robotId = robot
                    override val maxTextBytes = 1000

                    override suspend fun send(message: OutboundMessage): DeliveryResult {
                        sent += message.text
                        return DeliveryResult.Accepted
                    }
                }
            val router =
                BridgeRouter(
                    registry, { owner }, port, { channel }, BundleFreeTexts,
                    notReadyRetryDelayMs = 0, keyGapMs = 0, promptSettleMs = 0,
                )
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, router.onInbound(InboundMessage(robot, "1", "me", "chat-1", "13800138000")))
            assertEquals(listOf("\u001b[A", pasted("13800138000"), ScreenPrompts.ENTER), pressed)
            assertTrue(sent.none { it == "OFFLINE" })

            pressed.clear()
            router.onInbound(InboundMessage(robot, "2", "me", "chat-1", "仓鼠\n  小的那种 "))
            assertEquals(pasted("仓鼠\n  小的那种 "), pressed[1])
        }

    private object BundleFreeTexts : BridgeTexts {
        override fun unbound() = "UNBOUND"

        override fun offline() = "OFFLINE"

        override fun rebound() = "REBOUND"

        override fun notReady() = "NOT_READY"

        override fun emptyInput() = "EMPTY"

        override fun localInputLabel() = "LOCAL:"

        override fun waiting(reason: String?) = "WAIT"

        override fun prompt(screen: String) = "PROMPT"

        override fun promptAnsweredLocally(answer: String?) = "ANSWERED_LOCALLY($answer)"

        override fun promptRejected() = "PROMPT_REJECTED"

        override fun released(sessionTitle: String) = "RELEASED"

        override fun transcriptRestarted() = "RESTARTED"

        override fun turnWithoutReply() = "NO_REPLY"

        override fun turnFailed(reason: String) = "FAILED($reason)"

        override fun turnInterrupted() = "INTERRUPTED"

        override fun backgroundRunning(tasks: List<String?>) = "BG"

        override fun connected(
            project: String,
            agent: String,
            sessionTitle: String,
        ) = "CONNECTED"

        override fun attachmentLine(path: String) = "ATT"

        override fun attachmentsOnly(paths: List<String>) = "ONLY"

        override fun attachmentFailed() = "ATT_FAILED"

        override fun attachmentTooLarge() = "ATT_TOO_LARGE"
    }
}
