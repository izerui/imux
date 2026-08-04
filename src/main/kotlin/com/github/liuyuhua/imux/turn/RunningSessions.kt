package com.github.liuyuhua.imux.turn

import com.github.liuyuhua.imux.session.ClaudeRuntimeSession

/**
 * 合成「此刻正在执行」的会话集合。
 *
 * 两个 agent 的信息来源不同，精度也不同：
 *
 * - **claude** 有运行态文件，`status` 是 CLI 自己写的一手状态，打开时就能知道在不在跑
 * - **codex** 没有运行态文件，只能从会话文件的 `task_started` / `task_complete`
 *   事件推断，且只覆盖被 [TurnWatcher] 监控的会话（即经本插件打开过的）
 *
 * 因此 codex 有个已知盲区：点开一个**已经在跑**的会话，标记不会亮——监控从文件末尾
 * 开始，看不到之前那条 `task_started`。要等本轮结束、下一轮开始才亮。
 *
 * 纯函数，无 IO、无平台依赖，可完整单测。
 */
object RunningSessions {

    fun of(runtime: Map<String, ClaudeRuntimeSession>, codexWorking: Set<String>): Set<String> =
        runtime.values.filter { it.isBusy }.map { it.sessionId }.toSet() + codexWorking
}
