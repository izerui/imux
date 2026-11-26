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
internal const val PEER_FEEDBACK_MARK_ZH = "【副驾驶审查意见"
internal const val PEER_FEEDBACK_MARK_EN = "[Copilot review"

private const val HEADER_ZH = "$PEER_FEEDBACK_MARK_ZH】"
private const val HEADER_EN = "$PEER_FEEDBACK_MARK_EN]"

internal fun peerFeedbackMessage(feedback: String, chinese: Boolean): String =
    (if (chinese) HEADER_ZH else HEADER_EN) + "\n\n" + feedback

/** [text] 是不是副驾驶发进来的审查意见（而不是用户亲手输入的话）。 */
internal fun isPeerFeedbackMessage(text: String): Boolean =
    text.trimStart().let { it.startsWith(PEER_FEEDBACK_MARK_ZH) || it.startsWith(PEER_FEEDBACK_MARK_EN) }
