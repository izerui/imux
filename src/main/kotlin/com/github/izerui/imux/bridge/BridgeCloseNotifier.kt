package com.github.izerui.imux.bridge

import com.github.izerui.imux.bridge.core.Binding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

internal data class ClosingBinding(val binding: Binding, val title: String, val label: String)

/** 在关闭回调返回、渠道被销毁前完成通知；项目关闭与应用退出可能先后触发，只发送一次。 */
internal class BridgeCloseNotifier(
    private val send: suspend (ClosingBinding) -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val onTimeout: () -> Unit,
    private val timeoutMs: Long = 3_000,
) {
    private val notified = AtomicBoolean(false)

    fun notifyOnce(bindings: List<ClosingBinding>) {
        if (!notified.compareAndSet(false, true) || bindings.isEmpty()) return
        // 不能 launch 到即将取消的服务作用域；仅在确定关闭时短暂等待，IO 不访问 EDT 或平台读写锁。
        val completed = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                coroutineScope {
                    bindings.forEach { notice ->
                        launch {
                            try {
                                send(notice)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                onFailure(e)
                            }
                        }
                    }
                }
                true
            }
        }
        if (completed == null) onTimeout()
    }
}
