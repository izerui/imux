package com.github.izerui.imux.bridge.core

/** 手机作答时发给终端的按键。 */
internal object TerminalKeys {
    const val ESC = "\u001b"

    /** 多题提问里翻到下一题，不作答（Claude Code 2.1.308 实测，单选、多选题上都一样）。 */
    const val RIGHT = "\u001b[C"

    /** 手机输入法常给的全角字母数字转成半角，去掉首尾空白与结尾标点，便于认出「Ｂ。」这类选择。 */
    fun normalizeReply(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).trim().trimEnd(*TRAILING_PUNCTUATION).trim()

    private val TRAILING_PUNCTUATION = charArrayOf('。', '.', '！', '!', '，', ',', '？', '?', '~', '～')
}

/** 推到手机的列表在 Markdown 渠道里的转义。 */
internal object PhoneMarkdown {
    /** 行内的 `*`、反引号会被当成强调、代码，命令里常有，转义后原样显示。 */
    fun inline(
        text: String,
        markdown: Boolean,
    ): String = if (markdown) text.replace("\\", "\\\\").replace("*", "\\*").replace("`", "\\`") else text

    /** 行首像标题、引用、列表号的转义，免得渲染成标题或列表。 */
    fun escapeLineStart(line: String): String {
        if (line.isEmpty()) return line
        ORDERED_START.find(line)?.let { return it.groupValues[1] + "\\" + line.substring(it.groupValues[1].length) }
        return if (line.first() in "#>+-") "\\$line" else line
    }

    private val ORDERED_START = Regex("""^(\d+)[.)]""")
}
