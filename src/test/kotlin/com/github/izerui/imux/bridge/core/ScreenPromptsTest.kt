package com.github.izerui.imux.bridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 屏幕样本取自 Claude Code 2.1.308 在 tmux 里的实际画面（capture-pane）。 */
class ScreenPromptsTest {
    private val rule = "─".repeat(100)
    private val dashed = "╌".repeat(100)

    private val permission =
        """
        ⏺ Bash(touch /tmp/imux-probe/a.txt && ls)
        $rule
         Bash command
         Create a.txt and list files
        $dashed
         touch /tmp/imux-probe/a.txt && ls
        $dashed
         Do you want to proceed?
         ❯ 1. Yes
           2. Yes, and always allow access to /tmp/imux-probe from this project
           3. No

         Esc to cancel · Tab to amend


        """.trimIndent()

    private val question =
        """
        ❯ Use AskUserQuestion to ask me one question: pet (cat/dog).
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
        """.trimIndent()

    @Test
    fun `权限确认框从顶部分隔线取到操作提示，命令与选项都在`() {
        val prompt = ScreenPrompts.extract(permission)!!
        assertEquals(
            """
            Bash command
            Create a.txt and list files
            touch /tmp/imux-probe/a.txt && ls
            Do you want to proceed?
            ❯ 1. Yes
              2. Yes, and always allow access to /tmp/imux-probe from this project
              3. No

            Esc to cancel · Tab to amend
            """.trimIndent(),
            prompt.text,
        )
        assertNull(prompt.freeTextOption)
    }

    @Test
    fun `光标在分隔线下的选项时，框顶仍取到题目上方，并认出可填写的选项编号`() {
        val prompt = ScreenPrompts.extract(question)!!
        assertEquals(true, prompt.text.trimStart().startsWith("☐ 宠物\n\n你更喜欢哪种宠物？"))
        assertEquals(true, prompt.text.contains("❯ 4. Chat about this"))
        assertEquals(false, prompt.text.contains("Use AskUserQuestion"))
        assertEquals(3, prompt.freeTextOption)
    }

    @Test
    fun `多选题认出带勾选框的编号与可填写选项，Chat about this 不带勾选框`() {
        val prompt = ScreenPrompts.extract(RealScreens.MULTI_SELECT)!!
        assertEquals(3, prompt.freeTextOption)
        assertEquals(setOf(1, 2, 3), prompt.checkboxOptions)
        assertEquals(setOf(1, 2, 3, 4), prompt.options)
    }

    @Test
    fun `真实多选画面不把离题项混在批量选择里按，混选时当作原话填进输入项`() {
        val prompt = ScreenPrompts.extract(RealScreens.MULTI_SELECT)!!
        assertEquals(PromptAnswer.Keys(listOf("\u001b[B", "\u001b[B", "\u001b[200~选 4 1\u001b[201~", "\u001b[B", ScreenPrompts.ENTER)), ScreenPrompts.answer(prompt, "选 4 1"))
        assertEquals(PromptAnswer.Keys(listOf("\u001b[B", "\u001b[B", "\u001b[200~选 1 4\u001b[201~", "\u001b[B", ScreenPrompts.ENTER)), ScreenPrompts.answer(prompt, "选 1 4"))
        assertEquals(PromptAnswer.Keys(listOf("4")), ScreenPrompts.answer(prompt, "选 4"))
    }

    @Test
    fun `没有操作提示的答案确认页，以光标行和末行都是编号选项认出`() {
        val screen =
            """
            $rule
            ←  ☒ 颜色  ☒ 水果  ✔ Submit  →
            Review your answers
             ● 你最喜欢的颜色是什么？
               → 蓝色
            Ready to submit your answers?
            ❯ 1. Submit answers
              2. Cancel
            """.trimIndent()
        val prompt = ScreenPrompts.extract(screen)!!
        assertEquals(true, prompt.text.startsWith("←  ☒ 颜色"))
        assertEquals(true, prompt.text.endsWith("2. Cancel"))
    }

    private val trust =
        """
        $rule
         Accessing workspace:

         /private/tmp/imux-probe

         ❯ No, exit
           Yes, I trust this folder

         Enter to confirm · Esc to cancel
        """.trimIndent()

    @Test
    fun `没有编号的信任目录框也能认出`() {
        assertEquals(true, ScreenPrompts.extract(trust)!!.text.startsWith("Accessing workspace:"))
    }

    @Test
    fun `平常的输入框与状态栏不算选择框`() {
        val screen =
            """
            ⏺ 你的答案：hamster
            ✻ Sautéed for 6s · done 12:30 PM
            $rule
            ❯ 1. 先改这里
            $rule
              [Opus 5.5 (1M context)] ░░░░░░░░░░ 3% | imux-probe | ⏱️  <1m
              ⏸ manual mode on · ← 13 agents
            """.trimIndent()
        assertNull(ScreenPrompts.extract(screen))
        assertNull(ScreenPrompts.extract(""))
    }

    private val single = ScreenPrompts.extract(question)!!

    private val focusedQuestion =
        question.replace("❯ 4. Chat about this", "  4. Chat about this").replace("  3. Type something.", "❯ 3. Type something.")

    @Test
    fun `认出选项编号、单选与多选，以及光标是否停在自由输入项上`() {
        assertEquals(setOf(1, 2, 3, 4), single.options)
        assertEquals(false, single.multiSelect)
        assertEquals(false, single.freeTextFocused)
        assertEquals(true, ScreenPrompts.extract(focusedQuestion)!!.freeTextFocused)
        assertEquals(setOf(1, 2, 3), ScreenPrompts.extract(permission)!!.options)
    }

    @Test
    fun `自由输入项填了字后不再显示 Type something，仍按 Chat about this 的前一项认出且光标在它上面`() {
        val typed = focusedQuestion.replace("❯ 3. Type something.", "❯ 3. 仓")
        val prompt = ScreenPrompts.extract(typed)!!
        assertEquals(3, prompt.freeTextOption)
        assertEquals(true, prompt.freeTextFocused)
    }

    @Test
    fun `单选指令只按当前框里的一个编号，对不上时当作原话`() {
        assertEquals(PromptAnswer.Keys(listOf("2")), ScreenPrompts.answer(single, " 选 2 "))
        assertEquals(PromptAnswer.Keys(listOf("1")), ScreenPrompts.answer(single, "选1"))
        assertEquals(PromptAnswer.Keys(listOf("2")), ScreenPrompts.answer(single, "select 2"))
        assertEquals(PromptAnswer.Keys(listOf("1")), ScreenPrompts.answer(single, "選 1"))
        // 对不上（单选回多个、不存在的编号）不按编号，原话填进自定义输入项，由智能体判断
        assertEquals(PromptAnswer.Keys(listOf("\u001b[A", "\u001b[200~选 1 2\u001b[201~", ScreenPrompts.ENTER)), ScreenPrompts.answer(single, "选 1 2"))
        assertEquals(PromptAnswer.Keys(listOf("\u001b[A", "\u001b[200~选 13\u001b[201~", ScreenPrompts.ENTER)), ScreenPrompts.answer(single, "选 13"))
        assertEquals(PromptAnswer.Keys(listOf("\u001b[A", "\u001b[200~选 9\u001b[201~", ScreenPrompts.ENTER)), ScreenPrompts.answer(single, "选 9"))
    }

    @Test
    fun `多选指令把勾选改成恰好这几项后移到 Submit 提交，不存在或重复的编号当作原话`() {
        val multi = ScreenPrompts.extract(RealScreens.MULTI_SELECT)!!
        val submit = listOf("\u001b[B", "\u001b[B", "\u001b[B", ScreenPrompts.ENTER)
        assertEquals(PromptAnswer.Keys(listOf("1", "2") + submit), ScreenPrompts.answer(multi, "选 1 2"))
        assertEquals(PromptAnswer.Keys(listOf("1", "2") + submit), ScreenPrompts.answer(multi, "select 1，2"))
        assertEquals(PromptAnswer.Keys(listOf("\u001b[B", "\u001b[B", "\u001b[200~选 1 5\u001b[201~", "\u001b[B", ScreenPrompts.ENTER)), ScreenPrompts.answer(multi, "选 1 5"))
        assertEquals(PromptAnswer.Keys(listOf("\u001b[B", "\u001b[B", "\u001b[200~选 1 1\u001b[201~", "\u001b[B", ScreenPrompts.ENTER)), ScreenPrompts.answer(multi, "选 1 1"))
    }

    @Test
    fun `普通回复先移动到自由输入项，括号粘贴保留原文与换行后提交`() {
        assertEquals(
            PromptAnswer.Keys(listOf("\u001b[A", "\u001b[200~仓鼠\n小的\u001b[201~", ScreenPrompts.ENTER)),
            ScreenPrompts.answer(single, "仓鼠\n小的"),
        )
    }

    @Test
    fun `已聚焦自由输入项时直接粘贴，不再选择或移动光标`() {
        val focused = ScreenPrompts.extract(focusedQuestion)!!
        assertEquals(PromptAnswer.Keys(listOf("\u001b[200~仓鼠\u001b[201~", ScreenPrompts.ENTER)), ScreenPrompts.answer(focused, "仓鼠"))
    }

    @Test
    fun `光标停在自由输入项上时明确选择先离开输入框再按编号`() {
        val focused = ScreenPrompts.extract(focusedQuestion)!!
        assertEquals(PromptAnswer.Keys(listOf("\u001b[A", "1")), ScreenPrompts.answer(focused, "选 1"))
    }

    @Test
    fun `多选题的个人要求通过方向键聚焦输入并移到 Submit 提交`() {
        val multi = ScreenPrompts.extract(RealScreens.MULTI_SELECT)!!
        assertEquals(
            PromptAnswer.Keys(listOf("\u001b[B", "\u001b[B", "\u001b[200~苹果，但不要太酸\u001b[201~", "\u001b[B", ScreenPrompts.ENTER)),
            ScreenPrompts.answer(multi, "苹果，但不要太酸"),
        )
    }

    @Test
    fun `从 Chat about this 返回多选输入项时越过无编号的 Submit 行`() {
        val screen = RealScreens.MULTI_SELECT.replace("❯ 1.", "  1.").replace("  4. Chat", "❯ 4. Chat")
        assertEquals(
            PromptAnswer.Keys(listOf("\u001b[A", "\u001b[A", "\u001b[200~梨\u001b[201~", "\u001b[B", ScreenPrompts.ENTER)),
            ScreenPrompts.answer(ScreenPrompts.extract(screen)!!, "梨"),
        )
    }

    @Test
    fun `从多选 Submit 行可以返回输入项补充要求`() {
        val screen = RealScreens.MULTI_SELECT.replace("❯ 1.", "  1.").replace("     Submit", "❯    Submit")
        assertEquals(
            PromptAnswer.Keys(listOf("\u001b[A", "\u001b[200~梨\u001b[201~", "\u001b[B", ScreenPrompts.ENTER)),
            ScreenPrompts.answer(ScreenPrompts.extract(screen)!!, "梨"),
        )
    }

    @Test
    fun `多选输入项已聚焦时输入后仍通过 Submit 提交`() {
        val screen = RealScreens.MULTI_SELECT.replace("❯ 1.", "  1.").replace("  3.", "❯ 3.")
        assertEquals(
            PromptAnswer.Keys(listOf("\u001b[200~端口 8080\u001b[201~", "\u001b[B", ScreenPrompts.ENTER)),
            ScreenPrompts.answer(ScreenPrompts.extract(screen)!!, "端口 8080"),
        )
    }

    @Test
    fun `回自定义输入项本身的编号不按编号，原话填进输入项，多选时也不混入批量勾选`() {
        val multi = ScreenPrompts.extract(RealScreens.MULTI_SELECT)!!
        assertEquals(PromptAnswer.Keys(listOf("\u001b[B", "\u001b[B", "\u001b[200~选 3\u001b[201~", "\u001b[B", ScreenPrompts.ENTER)), ScreenPrompts.answer(multi, "选 3"))
        assertEquals(PromptAnswer.Keys(listOf("\u001b[B", "\u001b[B", "\u001b[200~选 1 3\u001b[201~", "\u001b[B", ScreenPrompts.ENTER)), ScreenPrompts.answer(multi, "选 1 3"))
        assertEquals(PromptAnswer.Keys(listOf("\u001b[A", "\u001b[200~选 3\u001b[201~", ScreenPrompts.ENTER)), ScreenPrompts.answer(single, "选 3"))
    }

    @Test
    fun `手机不逐键操作终端：按键说法与翻题词都当原文填进输入项`() {
        for (reply in listOf("按 Esc", "press esc", "按回车", "press enter", "下一题", "上一题", "next", "previous", "↑", "↓")) {
            assertEquals(
                reply,
                PromptAnswer.Keys(listOf("\u001b[A", "\u001b[200~$reply\u001b[201~", ScreenPrompts.ENTER)),
                ScreenPrompts.answer(single, reply),
            )
        }
    }

    @Test
    fun `没有自由输入项时普通文字不代选，拒绝这个框后交给会话`() {
        val prompt = ScreenPrompts.extract(permission)!!
        for (reply in listOf("Yes", "yes", "2. Yes, and always allow", "3 No", "改用 pnpm", "ok", "端口 8080")) {
            assertEquals(reply, PromptAnswer.Decline, ScreenPrompts.answer(prompt, reply))
        }
        assertEquals(PromptAnswer.Keys(listOf("1")), ScreenPrompts.answer(prompt, "1"))
        assertEquals(PromptAnswer.Decline, ScreenPrompts.answer(prompt, "8080"))
        assertEquals(PromptAnswer.Decline, ScreenPrompts.answer(prompt, "9"))
    }

    @Test
    fun `选项文字与说明作为用户原文填入，不推测对应选项`() {
        for (reply in listOf("cat", "猫", "2. dog", "2、狗", "1 先别动", "选猫，但不要黑色的", "确定", "取消", "确认")) {
            assertEquals(
                reply,
                PromptAnswer.Keys(listOf("\u001b[A", "\u001b[200~$reply\u001b[201~", ScreenPrompts.ENTER)),
                ScreenPrompts.answer(single, reply),
            )
        }
    }

    @Test
    fun `复制手机渲染的单选行作为原文填入`() {
        val rendered = ScreenPrompts.render(single, markdown = false).lines().first { it.startsWith("1.") }
        assertEquals(
            PromptAnswer.Keys(listOf("\u001b[A", "\u001b[200~1. cat · 猫\u001b[201~", ScreenPrompts.ENTER)),
            ScreenPrompts.answer(single, rendered),
        )
    }

    @Test
    fun `复制手机渲染的多选行不会取消选择框`() {
        for ((screen, box) in listOf(RealScreens.MULTI_SELECT to "☐", RealScreens.MULTI_SELECT.replace("1. [ ]", "1. [✔]") to "☑")) {
            val prompt = ScreenPrompts.extract(screen)!!
            val rendered = ScreenPrompts.render(prompt, markdown = false).lines().first { it.startsWith("1.") }
            assertEquals(
                PromptAnswer.Keys(listOf("\u001b[B", "\u001b[B", "\u001b[200~1. $box 苹果 · Apple\u001b[201~", "\u001b[B", ScreenPrompts.ENTER)),
                ScreenPrompts.answer(prompt, rendered),
            )
        }
    }

    @Test
    fun `只回数字或单个字母就是选择：字母按顺序对应选项，对不上选项的当作原话`() {
        assertEquals(PromptAnswer.Keys(listOf("1")), ScreenPrompts.answer(single, "1"))
        assertEquals(PromptAnswer.Keys(listOf("2")), ScreenPrompts.answer(single, " 2 "))
        assertEquals(PromptAnswer.Keys(listOf("2")), ScreenPrompts.answer(single, "b"))
        assertEquals(PromptAnswer.Keys(listOf("2")), ScreenPrompts.answer(single, "B"))
        assertEquals(PromptAnswer.Keys(listOf("1")), ScreenPrompts.answer(single, "选 a"))
        for (reply in listOf("8080", "2026", "13", "1 2", "a b", "z")) {
            assertEquals(reply, PromptAnswer.Keys(listOf("\u001b[A", "\u001b[200~$reply\u001b[201~", ScreenPrompts.ENTER)), ScreenPrompts.answer(single, reply))
        }
        // 连写的字母是一个词，不是选择
        assertEquals(PromptAnswer.Keys(listOf("\u001b[A", "\u001b[200~ok\u001b[201~", ScreenPrompts.ENTER)), ScreenPrompts.answer(single, "ok"))
    }

    @Test
    fun `无法定位输入项光标时不盲目输入`() {
        val prompt = ScreenPrompt("未知布局", options = setOf(1, 2, 3), freeTextOption = 3)
        assertEquals(PromptAnswer.Invalid, ScreenPrompts.answer(prompt, "仓鼠"))
    }

    @Test
    fun `空白回复不提交空答案`() {
        assertEquals(PromptAnswer.Invalid, ScreenPrompts.answer(single, " \n "))
    }

    @Test
    fun `多位编号不能拆成可能操作下一题的快捷键，按对不上选项处理`() {
        // 没有自定义输入项的框：对不上选项就拒绝这个框、把原话交给会话，不会按出 1 和 0
        val prompt = ScreenPrompt("十个选项", options = (1..10).toSet())
        assertEquals(PromptAnswer.Decline, ScreenPrompts.answer(prompt, "选 10"))
        val multi = prompt.copy(checkboxOptions = (1..10).toSet())
        assertEquals(PromptAnswer.Decline, ScreenPrompts.answer(multi, "选 1 10"))
    }

    @Test
    fun `超长数字对不上选项，不抛异常，原话填进自定义输入项`() {
        assertEquals(PromptAnswer.Keys(listOf("\u001b[A", "\u001b[200~选 13800138000\u001b[201~", ScreenPrompts.ENTER)), ScreenPrompts.answer(single, "选 13800138000"))
        assertEquals(PromptAnswer.Keys(listOf("\u001b[A", "\u001b[200~13800138000\u001b[201~", ScreenPrompts.ENTER)), ScreenPrompts.answer(single, "13800138000"))
        assertEquals(
            PromptAnswer.Keys(listOf("\u001b[A", "\u001b[200~手机 13800138000\u001b[201~", ScreenPrompts.ENTER)),
            ScreenPrompts.answer(single, "手机 13800138000"),
        )
    }

    @Test
    fun `多选框缺少 Submit 时不发送输入或确认按键`() {
        val screen = RealScreens.MULTI_SELECT.replace("     Submit\n", "")
        assertEquals(PromptAnswer.Invalid, ScreenPrompts.answer(ScreenPrompts.extract(screen)!!, "梨"))
    }

    @Test
    fun `没有编号选项的信任目录框回一句话不按 Esc：那会直接退出 CLI`() {
        assertEquals(PromptAnswer.Invalid, ScreenPrompts.answer(ScreenPrompts.extract(trust)!!, "Yes, I trust this folder"))
        assertEquals(PromptAnswer.Invalid, ScreenPrompts.answer(ScreenPrompts.extract(trust)!!, "按回车"))
    }

    @Test
    fun `权限框排成列表：题目与命令一段、选项一段，编号加粗，光标与操作提示不发`() {
        assertEquals(
            "Bash command  \nCreate a.txt and list files  \ntouch /tmp/imux-probe/a.txt && ls  \nDo you want to proceed?\n\n" +
                "**1.** Yes  \n**2.** Yes, and always allow access to /tmp/imux-probe from this project  \n**3.** No",
            ScreenPrompts.render(ScreenPrompts.extract(permission)!!, markdown = true),
        )
    }

    @Test
    fun `不渲染 Markdown 的渠道发同样布局的纯文字`() {
        assertEquals(
            "Bash command\nCreate a.txt and list files\ntouch /tmp/imux-probe/a.txt && ls\nDo you want to proceed?\n\n" +
                "1. Yes\n2. Yes, and always allow access to /tmp/imux-probe from this project\n3. No",
            ScreenPrompts.render(ScreenPrompts.extract(permission)!!, markdown = false),
        )
    }

    @Test
    fun `问答框的说明接在所属选项后面，多选题的勾选框换成方框符号`() {
        val single = ScreenPrompts.render(ScreenPrompts.extract(question)!!, markdown = false)
        assertEquals(true, single.contains("1. cat · 猫\n2. dog · 狗\n3. Type something.\n4. Chat about this"))
        val multi = ScreenPrompts.render(ScreenPrompts.extract(RealScreens.MULTI_SELECT)!!, markdown = false)
        assertEquals(true, multi.contains("1. ☐ 苹果 · Apple\n2. ☐ 梨 · Pear\n3. ☐ Type something"))
        assertEquals(false, multi.contains("Esc to cancel"))
        val checked = RealScreens.MULTI_SELECT.replace("1. [ ] 苹果", "1. [✔] 苹果")
        assertEquals(true, ScreenPrompts.render(ScreenPrompts.extract(checked)!!, markdown = false).contains("1. ☑ 苹果"))
    }

    @Test
    fun `命令里的星号与反引号在 Markdown 里转义，行首像标题或列表号的也转义`() {
        val prompt = ScreenPrompt("# 清理\nrm -rf *.log `x`\n❯ 1. Yes", options = setOf(1))
        assertEquals("\\# 清理  \nrm -rf \\*.log \\`x\\`\n\n**1.** Yes", ScreenPrompts.render(prompt, markdown = true))
    }
}


/** Claude Code 2.1.308 多选题的实际画面（tmux capture-pane，宽 120 列），路由器测试共用。 */
internal object RealScreens {
    val MULTI_SELECT =
        """
        ❯ Use the AskUserQuestion tool to ask me two questions: favorite color (red/blue) and favorite fruit (apple/pear,
          multi-select). Then reply ok.
        ${"─".repeat(120)}
        ←  ☒ 颜色  ☐ 水果  ✔ Submit  →

        你喜欢哪些水果？

        ❯ 1. [ ] 苹果
                 Apple
          2. [ ] 梨
                 Pear
          3. [ ] Type something
             Submit
        ${"─".repeat(120)}
          4. Chat about this

        Enter to select · Tab/Arrow keys to navigate · Esc to cancel
        """.trimIndent()
}
