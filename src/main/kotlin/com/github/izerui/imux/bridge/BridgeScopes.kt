package com.github.izerui.imux.bridge

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * 从平台注入的作用域派生桥接自己的作用域：随父作用域取消；子任务互不牵连，
 * 未捕获的异常只记日志，不冒到 IDE 或别的功能里。
 */
internal fun isolatedScope(
    parent: CoroutineScope,
    log: Logger,
    what: String,
): CoroutineScope =
    CoroutineScope(
        parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) +
            CoroutineExceptionHandler { _, e -> log.warn("${what}异常，已隔离", e) },
    )
