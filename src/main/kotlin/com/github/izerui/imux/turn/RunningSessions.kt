package com.github.izerui.imux.turn

import com.github.izerui.imux.session.ClaudeRuntimeSession

/**
 * 合成「此刻正在执行」的会话集合。
 *
 * 两个 agent 的信息来源不同，精度也不同：
 *
 * - **claude** 有按 pid 命名的运行态文件，`status` 是 CLI 自己写的一手状态，
 *   打开时就能知道在不在跑
 * - **codex 与 pi** 没有等价的东西（codex 那个运行态 sqlite 只答「这个 pid 在写哪个
 *   会话」，不答「在不在跑」），只能从会话文件的轮次信号推断（codex 看
 *   `task_started` / `task_complete`，pi 看 assistant 消息的 `stopReason`），
 *   且只覆盖被 [TurnWatcher] 监控的会话（即经本插件打开过的）
 *
 * 因此这两者有个已知盲区：点开一个**已经在跑**的会话，标记不会亮——监控从文件末尾
 * 开始，看不到之前那条开始信号。要等本轮结束、下一轮开始才亮。
 *
 * 纯函数，无 IO、无平台依赖，可完整单测。
 */
object RunningSessions {

    fun of(
        runtime: Map<String, ClaudeRuntimeSession>,
        fileInferredWorking: Set<String>,
    ): Set<String> =
        runtime.values.filter { it.isBusy }.map { it.sessionId }.toSet() + fileInferredWorking
}
