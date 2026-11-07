package com.github.izerui.imux.bridge.core

/**
 * 终端里正停着等人选的框。
 *
 * [text] 是屏幕上框内的原样文字，手机看到的与电脑上一致；[options] 是框里带编号的选项；
 * [checkboxOptions] 是其中带勾选框的选项，按它们的编号只切换勾选、不跳题，[checkedOptions] 是此刻已勾上的；
 * 同一框里不带勾选框的项（如 `Chat about this`）按下就离开这道题；
 * [freeTextOption] 是能自己填写的那一项的编号（Claude 的 `Type something.`），没有时为 null；
 * [freeTextFocused] 表示光标已停在这一项上：此时输入框已聚焦，按下的数字会被当成文字填进去；
 * [items] 是光标能停的各行，自上而下：选项编号，多选题的 `Submit` 记为 [SUBMIT]；[cursorItem] 是光标所在的那一行。
 * 多选题按编号不移动光标，进入自定义输入项要按方向键数行数。
 */
data class ScreenPrompt(
    val text: String,
    val options: Set<Int> = emptySet(),
    val checkboxOptions: Set<Int> = emptySet(),
    val checkedOptions: Set<Int> = emptySet(),
    val freeTextOption: Int? = null,
    val freeTextFocused: Boolean = false,
    val items: List<Int> = emptyList(),
    val cursorItem: Int? = null,
    /** 真实选项编号在 [text] 里所在的列；手写的测试数据可不填，排版时退回到首个编号行的列。 */
    val numberColumn: Int? = null,
) {
    val multiSelect: Boolean get() = checkboxOptions.isNotEmpty()

    companion object {
        /** [items] 里多选题 `Submit` 那一行的记号。 */
        const val SUBMIT = -1
    }
}

/** 手机回复对这个框的处理方式。每种都让这个框结束、会话接着跑，手机不逐键操作终端。 */
sealed interface PromptAnswer {
    /** 逐个按这些键：选中某项、或把原文填进自定义输入项并提交。 */
    data class Keys(val keys: List<String>) : PromptAnswer

    /**
     * 用户自己的要求，框里却没有能填写的项（如权限确认）：按 Esc 拒绝这个框，再把这句话照常提交给 CLI，
     * 与人在终端里按 Esc 后打字相同——权限确认时就是「别这样做，改成……」。
     */
    data object Decline : PromptAnswer

    /**
     * 一个键也不按，提示怎么回：空白回复、认不出光标在哪，或没有编号选项的框（信任目录框按 Esc 会直接退出 CLI）回了一句话。
     */
    data object Invalid : PromptAnswer
}

/**
 * 从终端屏幕末尾认出选择框，并把手机的回复译成按键。纯函数，无 IO。
 *
 * 不认 CLI 的私有协议，只认屏幕：权限确认、AskUserQuestion、信任目录等框
 * 都画成「光标行 `❯` / `›` + 选项 + 末行操作提示」，手机按同一块屏幕作答，
 * 等于替人在电脑上按键。实测形态见 `ScreenPromptsTest`。
 */
object ScreenPrompts {
    /** 屏幕末尾停着选择框就取出，否则为 null。[screen] 是终端末尾若干行的文字。 */
    fun extract(screen: String): ScreenPrompt? {
        val lines = screen.lines().map(String::trimEnd).dropLastWhile(String::isBlank).takeLast(MAX_LINES)
        val last = lines.lastOrNull() ?: return null
        val cursor = lines.indexOfLast { CURSOR.containsMatchIn(it) }
        if (cursor < 0) return null
        // 末行是操作提示（Esc to cancel 等）；Claude 的答案确认页没有提示，末行就是带编号的选项
        val closed = FOOTER.containsMatchIn(last) || (OPTION.containsMatchIn(lines[cursor]) && OPTION.containsMatchIn(last))
        if (!closed) return null

        var top = separatorAbove(lines, cursor)
        // 光标落在分隔线下的选项（如 `4. Chat about this`）时，框顶还在上一条分隔线
        if (top >= 0 && (top + 1 until cursor).none { FIRST_OPTION.containsMatchIn(lines[it]) }) {
            val outer = separatorAbove(lines, top)
            if (outer >= 0 && (outer + 1 until top).any { FIRST_OPTION.containsMatchIn(lines[it]) }) top = outer
        }
        if (top < 0) top = maxOf(-1, cursor - MAX_ABOVE_CURSOR - 1)

        // 框内的虚线（权限框把命令夹在 `╌` 之间）只是装饰，与分隔线一并去掉
        val body = lines.subList(top + 1, lines.size).filterNot { isRule(it, RULE_CHARS) }.collapseBlankRuns()
        if (body.isEmpty()) return null
        val indent = body.filter(String::isNotBlank).minOf { line -> line.indexOfFirst { !it.isWhitespace() } }
        val column = numberColumn(lines[cursor])
        val optionLines = body.filter { optionNumber(it, column) != null }
        val options = optionLines.mapNotNull { optionNumber(it, column) }.toSet()
        // 输入框里有了文字，`Type something` 就被替换成所填内容；它总排在 `Chat about this` 前一项
        val freeText =
            optionLines.firstNotNullOfOrNull { FREE_TEXT.find(it)?.groupValues?.get(1)?.toIntOrNull() }
                ?: optionLines.firstNotNullOfOrNull { CHAT_ABOUT.find(it)?.groupValues?.get(1)?.toIntOrNull() }?.minus(1)?.takeIf { it in options }
        val checkboxes = optionLines.mapNotNull { CHECKBOX.find(it) }
        val checkboxOptions = checkboxes.map { it.groupValues[1].toInt() }.toSet()
        val checked = checkboxes.filter { it.groupValues[2].isNotBlank() }.map { it.groupValues[1].toInt() }.toSet()
        return ScreenPrompt(
            text = body.joinToString("\n") { it.drop(indent) },
            options = options,
            checkboxOptions = checkboxOptions,
            checkedOptions = checked,
            freeTextOption = freeText,
            freeTextFocused = freeText != null && optionNumber(lines[cursor], column) == freeText,
            items = body.mapNotNull { itemOf(it, column) },
            cursorItem = itemOf(lines[cursor], column),
            numberColumn = column?.minus(indent),
        )
    }

    /**
     * 真实选项的编号都在同一列，从光标所在的那一行定：光标总停在真实选项上（或多选题的 `Submit` 上，
     * 它的文字比编号右移 [SUBMIT_OFFSET] 列）。不取框里第一条像 `1. …` 的行——题目本身可能就是
     * 「1. 请选择通知渠道」，用它定列会把真实选项全部滤掉。自定义输入项里的草稿续行缩进到选项文字的列，
     * 写成 `1. 保留颜色` 也不在这一列，不会被当成光标能停的选项。
     * 光标行既不是编号选项也不是 `Submit`（如信任目录框）时为 null，整个框按无编号处理。
     */
    private fun numberColumn(cursorLine: String): Int? =
        OPTION.find(cursorLine)?.groups?.get(1)?.range?.first
            ?: SUBMIT_ITEM.find(cursorLine)?.groups?.get(1)?.range?.first?.minus(SUBMIT_OFFSET)

    /**
     * 光标能停的一行：编号在 [column] 列的选项，或多选题的 `Submit`。`Submit` 的文字比编号右移三列
     * （实测 `  3. [ ] Type something` 下面是 `     Submit`），草稿里单独一行的 `Submit` 缩进更深，不算。
     */
    private fun itemOf(
        line: String,
        column: Int?,
    ): Int? {
        if (column == null) return null
        optionNumber(line, column)?.let { return it }
        val submit = SUBMIT_ITEM.find(line) ?: return null
        return ScreenPrompt.SUBMIT.takeIf { submit.groups[1]!!.range.first == column + SUBMIT_OFFSET }
    }

    /**
     * 排成聊天里的列表发到手机：每个选项一行 `1. 文字 · 说明`，勾选框换成 ☐ / ☑；
     * 终端才有意义的光标 `❯` 和操作提示（`Esc to cancel` 等）不发，怎么回由后面的提示说明。
     * [markdown] 为 true 时编号加粗、行尾用硬换行（钉钉不认单个换行），并转义会被渲染成标题、列表的行首。
     */
    fun render(
        prompt: ScreenPrompt,
        markdown: Boolean,
    ): String {
        val blocks = mutableListOf<MutableList<String>>(mutableListOf())
        var optionColumn: Int? = null
        var inOptions = false
        // 与 extract 同一列：只有编号在真实选项那一列的才是选项，草稿里的 `1. …` 接在所属选项后面
        val numberColumn = prompt.numberColumn ?: prompt.text.lines().firstNotNullOfOrNull { OPTION.find(it)?.groups?.get(1)?.range?.first }
        for (raw in prompt.text.lines()) {
            if (FOOTER.containsMatchIn(raw)) continue
            if (raw.isBlank()) {
                if (blocks.last().isNotEmpty()) blocks += mutableListOf<String>()
                optionColumn = null
                continue
            }
            val option = OPTION_RENDER.find(raw)?.takeIf { it.groups[1]!!.range.first == numberColumn }
            val indent = raw.indexOfFirst { !it.isWhitespace() }
            val column = optionColumn
            when {
                option != null -> {
                    // 选项与上方的题目、命令分成两段
                    if (!inOptions && blocks.last().isNotEmpty()) blocks += mutableListOf<String>()
                    inOptions = true
                    optionColumn = option.groups[3]!!.range.first
                    val box =
                        when (option.groupValues[2]) {
                            "" -> ""
                            " " -> "☐ "
                            else -> "☑ "
                        }
                    val number = option.groupValues[1] + "."
                    blocks.last() += (if (markdown) "**$number**" else number) + " " + box + inline(option.groupValues[3], markdown)
                }
                // 说明行接在所属选项后面，免得缩进在聊天里丢失后看不出归属
                column != null && indent >= column -> {
                    val options = blocks.last()
                    options[options.lastIndex] = options.last() + " · " + inline(raw.trim(), markdown)
                }
                else -> {
                    if (inOptions && blocks.last().isNotEmpty()) blocks += mutableListOf<String>()
                    inOptions = false
                    optionColumn = null
                    val line = raw.trim().removePrefix("❯").removePrefix("›").trim()
                    blocks.last() += if (markdown) escapeLineStart(inline(line, true)) else line
                }
            }
        }
        val lineBreak = if (markdown) "  \n" else "\n"
        return blocks.filter(List<String>::isNotEmpty).joinToString("\n\n") { it.joinToString(lineBreak) }
    }

    /** 行内的 `*`、反引号会被当成强调、代码，命令里常有，转义后原样显示。 */
    private fun inline(
        text: String,
        markdown: Boolean,
    ): String = if (markdown) text.replace("\\", "\\\\").replace("*", "\\*").replace("`", "\\`") else text

    private fun escapeLineStart(line: String): String {
        ORDERED_START.find(line)?.let { return it.groupValues[1] + "\\" + line.substring(it.groupValues[1].length) }
        return if (line.first() in "#>+-") "\\$line" else line
    }

    /**
     * 手机回复 [reply] 对框 [prompt] 该怎么作答。手机不逐键操作终端，只有两种回答，每种都让这个框结束、会话接着跑：
     *
     * - **选择**：回复只有编号或单个字母（`2`、`1 3`、`b`、`a，c`，可带「选」「選」`select` 前缀），
     *   字母按顺序对应选项（a 是第 1 项），不分大小写。单选题选中即进入下一步；
     *   多选题把勾选改成恰好是这几项（按屏幕上当前的勾选状态只切换不同的项），再提交这道题。
     *   对不上选项的（不存在、单选题回了多个、多选题混入 `Chat about this` 这类非勾选项、`8080` 这类不是选项的数、
     *   正是自定义输入项本身）不猜，按下一条当作用户自己的话，由 CLI 里的智能体判断他想选哪个。
     * - **其余都是用户自己的要求**（选项原文、`ok`、「取消」这类普通词、对不上选项的数字字母）：
     *   有自定义输入项就用括号粘贴原样填进去并提交，换行与空格不改，由 CLI 里的智能体理解；
     *   没有就 [PromptAnswer.Decline]：拒绝这个框，再把这句话交给会话。
     *
     * 按键依据（Claude Code 2.1.308 实测）：
     * - 单选题按编号即选中并跳到下一题；多选题按编号只切换勾选、光标不动；
     * - 自定义输入项用 ↑/↓ 移入，光标一到输入框即聚焦；此时按下的数字会被填成文字，选别的编号前先按 ↑ 离开
     *   （多行草稿时也是一次 ↑ 就离开）；
     * - 括号粘贴的原文原样进入输入框，CLI 收到的答案与原文一致（含换行与连续空格）；
     * - 单选题输入后回车即提交；多选题输入时这一项自动勾上，再按回车会取消勾选，
     *   所以输入后按 ↓ 到 `Submit` 再回车，进入下一题或答案确认页；多选题勾好后同样到 `Submit` 回车。
     */
    fun answer(
        prompt: ScreenPrompt,
        reply: String,
    ): PromptAnswer {
        PICKS.matchEntire(reply.trim())?.let { picks -> select(prompt, picks.groupValues[1])?.let { return it } }
        if (reply.isBlank()) return PromptAnswer.Invalid
        return freeText(prompt, reply)
    }

    /** 对得上选项就给出选中的按键；对不上返回 null，由调用方当作用户自己的话。 */
    private fun select(
        prompt: ScreenPrompt,
        numbers: String,
    ): PromptAnswer? {
        // 编号用 toIntOrNull：手机号这类超长数字是对不上的选择，不能抛异常；单个字母按顺序换成编号
        val picks =
            numbers.split(SEPARATORS).filter(String::isNotEmpty).map { token ->
                val letter = token.singleOrNull()?.lowercaseChar()?.takeIf { it in 'a'..'z' }
                letter?.let { it - 'a' + 1 } ?: token.toIntOrNull() ?: return null
            }
        if (picks.isEmpty() || picks.toSet().size != picks.size) return null
        // 终端的数字快捷键是一位一键，不能把 10 拆成会落到后续题目的 1 和 0。
        if (picks.any { it !in 1..9 || it !in prompt.options }) return null
        // 自定义输入项本身要填文字才有意义，回它的编号也当作用户自己的话
        if (prompt.freeTextOption in picks) return null
        val toggles = prompt.checkboxOptions - setOfNotNull(prompt.freeTextOption)
        if (!prompt.multiSelect || picks.none { it in toggles }) {
            // 单选题，或多选题里的 `Chat about this` 这类按下就离开的项：只接受一个
            val pick = picks.singleOrNull() ?: return null
            return PromptAnswer.Keys(leaveInput(prompt) + pick.toString())
        }
        if (picks.any { it !in toggles }) return null
        val flips = toggles.sorted().filter { (it in picks) != (it in prompt.checkedOptions) }.map(Int::toString)
        // 按编号不移动光标；先离开输入框的那一下 ↑ 让光标上移一行
        val from = prompt.items.indexOf(prompt.cursorItem ?: return PromptAnswer.Invalid) - leaveInput(prompt).size
        val submit = prompt.items.indexOf(ScreenPrompt.SUBMIT)
        if (from < 0 || submit < 0) return PromptAnswer.Invalid
        return PromptAnswer.Keys(leaveInput(prompt) + flips + steps(from, submit) + ENTER)
    }

    private fun leaveInput(prompt: ScreenPrompt): List<String> = if (prompt.freeTextFocused) listOf(UP) else emptyList()

    /** 从光标所在行移到 [target] 那一行的方向键；认不出光标或目标时为 null。 */
    private fun moveTo(
        prompt: ScreenPrompt,
        target: Int,
    ): List<String>? {
        val cursor = prompt.items.indexOf(prompt.cursorItem ?: return null)
        val index = prompt.items.indexOf(target)
        if (cursor < 0 || index < 0) return null
        return steps(cursor, index)
    }

    private fun steps(
        from: Int,
        to: Int,
    ): List<String> = if (to >= from) List(to - from) { DOWN } else List(from - to) { UP }

    private fun freeText(
        prompt: ScreenPrompt,
        text: String,
    ): PromptAnswer {
        // 没有编号选项的框（启动时的信任目录框）按 Esc 会直接退出 CLI，不替人拒绝
        if (prompt.options.isEmpty()) return PromptAnswer.Invalid
        val option = prompt.freeTextOption ?: return PromptAnswer.Decline
        val move = moveTo(prompt, option) ?: return PromptAnswer.Invalid
        // 原文里的 ESC 会提前结束括号粘贴，后面的字节就成了按键；只去掉它，其余原样
        val paste = PASTE_START + text.replace(ESC, "") + PASTE_END
        if (!prompt.multiSelect) return PromptAnswer.Keys(move + paste + ENTER)
        val submit = prompt.items.indexOf(ScreenPrompt.SUBMIT) - prompt.items.indexOf(option)
        if (submit <= 0) return PromptAnswer.Invalid
        return PromptAnswer.Keys(move + paste + List(submit) { DOWN } + ENTER)
    }

    private fun optionNumber(
        line: String,
        column: Int?,
    ): Int? = OPTION.find(line)?.takeIf { it.groups[1]!!.range.first == column }?.groupValues?.get(1)?.toIntOrNull()

    private fun separatorAbove(
        lines: List<String>,
        index: Int,
    ): Int = (index - 1 downTo 0).firstOrNull { isSeparator(lines[it]) } ?: -1

    /** 框顶只认实线：虚线在框内部。 */
    private fun isSeparator(line: String): Boolean = isRule(line, SOLID_CHARS)

    private fun isRule(
        line: String,
        chars: String,
    ): Boolean {
        val trimmed = line.trim()
        return trimmed.length >= MIN_SEPARATOR && trimmed.all { it in chars }
    }

    private fun List<String>.collapseBlankRuns(): List<String> =
        filterIndexed { i, line -> line.isNotBlank() || (i > 0 && this[i - 1].isNotBlank()) }
            .dropWhile(String::isBlank)
            .dropLastWhile(String::isBlank)

    const val ESC = "\u001b"
    const val ENTER = "\r"
    private const val UP = "\u001b[A"
    private const val DOWN = "\u001b[B"
    private const val PASTE_START = "\u001b[200~"
    private const val PASTE_END = "\u001b[201~"

    private const val MAX_LINES = 60
    private const val MAX_ABOVE_CURSOR = 15
    private const val MIN_SEPARATOR = 10
    private const val SOLID_CHARS = "─━"
    private const val RULE_CHARS = "─━╌"

    private val CURSOR = Regex("""^\s*[❯›]\s+\S""")
    private val OPTION = Regex("""^\s*(?:[❯›]\s*)?(\d+)\.\s""")
    private val FIRST_OPTION = Regex("""^\s*(?:[❯›]\s*)?1\.\s""")
    private val FOOTER = Regex("""(?i)\besc to cancel\b|\benter to (?:confirm|select)\b""")
    private val FREE_TEXT = Regex("""^\s*(?:[❯›]\s*)?(\d+)\.\s+(?:\[.]\s+)?Type something""")
    private val OPTION_RENDER = Regex("""^\s*(?:[❯›]\s*)?(\d+)\.\s+(?:\[([ ✔✓xX])]\s+)?(\S.*)$""")
    private val ORDERED_START = Regex("""^(\d+)[.)]""")
    private val CHAT_ABOUT = Regex("""^\s*(?:[❯›]\s*)?(\d+)\.\s+Chat about this""")
    private val CHECKBOX = Regex("""^\s*(?:[❯›]\s*)?(\d+)\.\s+\[([ ✔✓xX])]""")
    private val SUBMIT_ITEM = Regex("""^\s*(?:[❯›]\s*)?(Submit)$""")
    private const val SUBMIT_OFFSET = 3
    /** 只有编号或单个字母（可带「选」前缀）：`ok` 这种连写的字母是一个词，不算。 */
    private val PICKS = Regex("""(?i)(?:选|選|select)?\s*((?:\d+|[a-z])(?:\s*[,，、\s]\s*(?:\d+|[a-z]))*)""")
    private val SEPARATORS = Regex("""[\s,，、]+""")
}
