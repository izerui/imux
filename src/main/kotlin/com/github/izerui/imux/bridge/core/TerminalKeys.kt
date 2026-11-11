package com.github.izerui.imux.bridge.core

/** 手机回复的归一化：认选项编号、允许拒绝词之前先去掉输入法带来的差异。 */
internal object TerminalKeys {
    /** 手机输入法常给的全角字母数字转成半角，去掉首尾空白与结尾标点，便于认出「Ｂ。」这类选择。 */
    fun normalizeReply(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).trim().trimEnd(*TRAILING_PUNCTUATION).trim()

    private val TRAILING_PUNCTUATION = charArrayOf('。', '.', '！', '!', '，', ',', '？', '?', '~', '～')
}

/** 推到手机的列表的转义；各手机端都按 Markdown 渲染。 */
internal object PhoneMarkdown {
    /** 列表各行之间的换行：行尾两个空格才是 Markdown 的硬换行。 */
    const val LINE_BREAK = "  \n"

    /** 行内的 `*`、反引号会被当成强调、代码，命令里常有，转义后原样显示。 */
    fun inline(text: String): String = text.replace("\\", "\\\\").replace("*", "\\*").replace("`", "\\`")

    /** 行首像标题、引用、列表号的转义，免得渲染成标题或列表。 */
    fun escapeLineStart(line: String): String {
        if (line.isEmpty()) return line
        ORDERED_START.find(line)?.let { return it.groupValues[1] + "\\" + line.substring(it.groupValues[1].length) }
        return if (line.first() in "#>+-") "\\$line" else line
    }

    private val ORDERED_START = Regex("""^(\d+)[.)]""")
}
