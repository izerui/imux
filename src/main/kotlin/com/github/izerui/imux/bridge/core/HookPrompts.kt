package com.github.izerui.imux.bridge.core

/**
 * 一次 PermissionRequest。[id] 由入口分配；[input] 是 tool_input 的顶层字段 → 规范化 JSON（键名递归排序），
 * 与 [agentId]、[toolName] 一起用来对上随后的 PostToolUse（见 [matches]）。
 * [agentId] 为 null 是主会话的请求，否则来自子代理，[agentType] 是子代理的类型（主会话时为 null）。
 */
data class HookRequest(
    val id: Long,
    val toolName: String,
    val agentId: String?,
    val agentType: String?,
    val detail: String,
    val questions: List<AskedQuestion>?,
    val input: Map<String, String>,
) {
    /**
     * 电脑端允许后的 PostToolUse 是否就是这个请求：代理、工具相同，且两边都有的每个顶层字段值相等。
     * 一边没有的字段不比较：PostToolUse 里计划的输入为空、提问多出 answers 与 annotations（实测 26）。
     */
    fun matches(done: HookToolDone): Boolean =
        agentId == done.agentId && toolName == done.toolName &&
            input.all { (key, value) -> done.input[key]?.let { it == value } ?: true }

    /** AskUserQuestion → QUESTION，ExitPlanMode → PLAN，其余 → PERMISSION。 */
    val kind: PromptKind
        get() = when (toolName) {
            "AskUserQuestion" -> PromptKind.QUESTION
            PermissionPrompts.EXIT_PLAN_MODE -> PromptKind.PLAN
            else -> PromptKind.PERMISSION
        }
}

/** 一次 PostToolUse / PostToolUseFailure：电脑端允许后工具跑完。[input] 同 [HookRequest.input]。 */
data class HookToolDone(
    val agentId: String?,
    val toolName: String,
    val input: Map<String, String>,
)

/** hook 路径的决定：交给电脑、允许、拒绝。 */
sealed interface HookDecision {
    /** 回 {}：交给电脑。 */
    data object Pass : HookDecision

    /** 允许，可附答案（提问）或依次切换的模式（计划，按顺序各下发一条 setMode）。 */
    data class Allow(val answers: Map<String, String>? = null, val modes: List<String> = emptyList()) : HookDecision

    /** 拒绝，附原因。 */
    data class Deny(val message: String) : HookDecision
}

/**
 * 手机回复译成 hook 决定。纯函数，无 IO。
 *
 * 词表用 [PermissionPrompts] 的 [PermissionPrompts.ALLOW]、[PermissionPrompts.DENY]、[PermissionPrompts.OPTION_LIKE]。
 */
object HookReplies {
    private const val DENIED = "The user denied this tool call from their phone."
    private const val DENIED_AND_SAID = "The user denied this tool call from their phone and said: "
    private const val PLAN_DENIED = "The user does not want to proceed with this plan yet."

    /**
     * 计划回 1：与电脑上的第 1 项一致，先切 auto 再切 bypassPermissions（实测 23、24）。
     * 以跳过权限方式启动的会话最终为 bypass；其余会话里 bypass 是空操作，停在 auto。顺序不能反。
     */
    val PLAN_FIRST_MODES = listOf("auto", "bypassPermissions")

    /** 权限确认：允许词 → Allow()；拒绝词或像选项编号/字母 → 通用拒绝；其余 → 拒绝并附原话。 */
    fun permission(reply: String): HookDecision {
        val word = TerminalKeys.normalizeReply(reply).lowercase()
        return when (word) {
            in PermissionPrompts.ALLOW -> HookDecision.Allow()
            in PermissionPrompts.DENY -> HookDecision.Deny(DENIED)
            else -> if (PermissionPrompts.OPTION_LIKE.matches(word))
                HookDecision.Deny(DENIED)
            else
                HookDecision.Deny(DENIED_AND_SAID + reply.trim())
        }
    }

    /**
     * 计划确认：1/a → [PLAN_FIRST_MODES]，2/b → default，拒绝词（去掉 2/b 再加「不执行」「不執行」）或其余像选项编号/字母的回复 → 通用不执行，
     * 其他 → Deny(原话)。「3」之类不当作修改意见转给 Claude。
     */
    fun plan(reply: String): HookDecision {
        val word = TerminalKeys.normalizeReply(reply).lowercase()
        return when (word) {
            "1", "a" -> HookDecision.Allow(modes = PLAN_FIRST_MODES)
            "2", "b" -> HookDecision.Allow(modes = listOf("default"))
            in PermissionPrompts.DENY - setOf("2", "b") + setOf("不执行", "不執行") -> HookDecision.Deny(PLAN_DENIED)
            else -> if (PermissionPrompts.OPTION_LIKE.matches(word))
                HookDecision.Deny(PLAN_DENIED)
            else
                HookDecision.Deny(reply.trim())
        }
    }
}
