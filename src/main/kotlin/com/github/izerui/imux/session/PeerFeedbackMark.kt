package com.github.izerui.imux.session

/**
 * 副驾驶的审查意见发进主会话时的固定开头。
 *
 * 意见是当作一条输入打进终端的，会话记录里它和用户亲手输入的话长得一样。不加标记的话，整条路线都会认错人：
 * 主会话把它当成用户的要求照做，会话标题按它改名，会话报告把它记成需求变更，接手会话的智能体以为那是用户提的。
 * 所以开头标明它是谁说的，后面每一环按这个开头认出它。
 *
 * 开头只写来源、不写「该怎么对待」：它同时显示在终端里、也会作为「电脑端输入」转到手机上，要尽量短（用户定的）。
 * 主会话该怎么对待它，写在结对工具的说明里（`review_once`、`enable_pair_programming`）。
 * 认的时候按前缀认：早先的版本在后面还带过一段说明，旧会话记录里的那种开头也要认得出。
 * 提示词只有中、英两套，标记也只有两种；两种都认（用户可能中途切换界面语言）。
 */
internal const val PEER_FEEDBACK_MARK_ZH = "【副驾驶审查意见】"
internal const val PEER_FEEDBACK_MARK_EN = "[Copilot review]"

internal const val LEGACY_PEER_ZH = "【副驾驶审查意见｜不是用户的要求：先核实再处理，与用户已定的事冲突时以用户为准】"
internal const val LEGACY_PEER_EN = "[Copilot review — not a user request: verify before acting; where it conflicts with what the user decided, the user decides]"
private const val LEGACY_SHORT_EN = "[Copilot review — not a user request: verify before acting]"

internal fun peerFeedbackMessage(feedback: String, chinese: Boolean): String =
    (if (chinese) PEER_FEEDBACK_MARK_ZH else PEER_FEEDBACK_MARK_EN) + "\n\n" + feedback

/** [text] 是不是副驾驶发进来的审查意见（而不是用户亲手输入的话）。 */
internal fun isPeerFeedbackMessage(text: String): Boolean =
    text.trimStart().let { input ->
        listOf(PEER_FEEDBACK_MARK_ZH, PEER_FEEDBACK_MARK_EN, LEGACY_PEER_ZH, LEGACY_PEER_EN, LEGACY_SHORT_EN)
            .any { input.startsWith(it) && (input.length == it.length || input[it.length].isWhitespace()) } ||
            (input.startsWith("[Copilot review — not a user") && LEGACY_PEER_EN.startsWith(input))
    }
