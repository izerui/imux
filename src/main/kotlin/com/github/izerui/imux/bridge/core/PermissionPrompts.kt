package com.github.izerui.imux.bridge.core

/**
 * hook 送来的权限确认在手机上怎么列，以及手机回复的词表（[HookReplies] 按它译成决定）。纯函数，无 IO。
 *
 * 手机上只给三种回答：允许这一次、拒绝、或者说一段话（拒绝并把这段话转告 Claude）。
 * 永久授权、切换模式等手机上一律不给。
 */
object PermissionPrompts {
    /** 工具名一行，要批准的内容逐行跟在后面。有子代理来源时写在工具名后。 */
    internal fun render(name: String, detail: String, agentType: String? = null): String {
        val suffix = if (agentType != null) " · ${PhoneMarkdown.inline(agentType)}" else ""
        val title = "**${PhoneMarkdown.inline(name)}**$suffix"
        val lines = detail.lines().filter(String::isNotBlank).map { PhoneMarkdown.escapeLineStart(PhoneMarkdown.inline(it.trim())) }
        return (listOf(title) + lines).joinToString(PhoneMarkdown.LINE_BREAK)
    }

    /** 计划确认（ExitPlanMode）的工具名：hook 请求按它认出计划，回复见 [HookReplies.plan]。 */
    const val EXIT_PLAN_MODE = "ExitPlanMode"

    internal val OPTION_LIKE = Regex("""(?:\d{1,2}|[a-z])(?:[\s,，、]+(?:\d{1,2}|[a-z]))*""")
    internal val ALLOW = setOf("1", "a", "允许", "允許", "許可", "同意", "可以", "好", "好的", "行", "yes", "y", "ok", "allow")
    internal val DENY = setOf("2", "b", "拒绝", "拒絕", "拒否", "不", "不行", "不要", "no", "n", "deny")
}
