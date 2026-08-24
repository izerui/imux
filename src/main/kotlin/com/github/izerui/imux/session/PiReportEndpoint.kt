package com.github.izerui.imux.session

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.jetbrains.ide.BuiltInServerManager
import java.util.UUID

/** 上报接口的 URI 前缀。取得足够独特：扩展点是全局共享链，撞名会截胡别的插件的请求。 */
const val PI_REPORT_PATH: String = "/imux/pi-session"

/**
 * pi 上报所需的地址与令牌。
 *
 * 令牌是这个接口**唯一**的门禁：平台在 `HttpRequestHandler` 这一层不做任何校验，
 * 本机任意进程都能往内置服务上打请求。没有它，别的进程就能伪造上报把标签页
 * 迁到另一个会话上。
 *
 * 令牌只存在于内存，随环境变量下发给 pi 进程——插件不写磁盘（见 README）。
 */
data class PiReportEndpoint(
    val url: String,
    val token: String,
) {
    companion object {
        /**
         * 内置服务未就绪时返回 null，调用方据此退回「不上报」。
         *
         * **不能在这里现算**：唯一的调用点是 `TerminalHost.createView`，那是
         * `open()` 的 EDT 路径，而算出端口要走 `BuiltInServerManager.waitForStart()`——
         * 它会**阻塞**到内置服务起来。绝大多数时候立即返回，但 IDE 刚启动就点开
         * pi 会话时会当场卡住 EDT，且 `runCatching` 只吞异常、解决不了阻塞。
         *
         * 端口在一次 IDE 运行期内不变、令牌是应用级单例，整个结果因此可以缓存。
         * 缓存由 [PiReportEndpointCache] 在后台算好，这里只取现成的。
         */
        fun current(): PiReportEndpoint? = ApplicationManager.getApplication().service<PiReportEndpointCache>().endpoint()
    }
}

/**
 * 缓存本次 IDE 运行期的上报端点，并保证计算不落在 EDT 上。
 *
 * 应用级：内置 HTTP 服务整个 IDE 一个，端口与令牌都没有项目维度。
 */
@Service(Service.Level.APP)
class PiReportEndpointCache(
    scope: CoroutineScope,
) {
    /**
     * 算好的端点；还没算好时为 null。
     *
     * 后台协程写、EDT 读，故必须 volatile。普通打开路径只读这个字段，确保任何线程
     * 都不阻塞；只有启动恢复 Pi 标签时才显式等待 [computation]。
     */
    @Volatile
    private var cached: PiReportEndpoint? = null

    // 服务一被实例化就在后台开算，不等第一次调用。服务构造可能发生在 EDT，
    // 因此这里只排期；恢复 Pi 标签时可等待同一个 Deferred，而不是重复计算。
    private val computation: Deferred<PiReportEndpoint?> =
        scope.async(Dispatchers.IO) {
            compute().also { cached = it }
        }

    /**
     * 已经算好就返回，还没算好则返回 null——**绝不等待**。
     *
     * 端点还没算出来时，用户手动打开的 pi 标签页仍退回「不上报」；启动恢复则通过
     * [awaitReady] 在后台等待，保证恢复出的 Pi 进程带上上报凭据。
     */
    fun endpoint(): PiReportEndpoint? = cached

    internal suspend fun awaitEndpoint(): PiReportEndpoint? = computation.await()

    private fun compute(): PiReportEndpoint? =
        runCatching {
            val port = BuiltInServerManager.getInstance().waitForStart().port
            if (port <= 0) {
                LOG.warn("IDE 内置 HTTP 服务未返回有效端口，Pi 会话将不启用上报")
                return@runCatching null
            }
            PiReportEndpoint(
                url = "http://127.0.0.1:$port$PI_REPORT_PATH",
                token =
                    ApplicationManager
                        .getApplication()
                        .getService(PiReportTokenHolder::class.java)
                        .token,
            )
        }.getOrElse {
            LOG.warn("计算 Pi 会话上报端点失败，Pi 会话将不启用上报", it)
            null
        }

    companion object {
        private val LOG = logger<PiReportEndpointCache>()

        /**
         * 触发缓存计算但不等待。普通启动用它尽早预热；恢复 Pi 标签必须改用
         * [awaitReady]，不能假设异步计算已经完成。
         *
         * 服务本身是懒加载的：不主动碰一下，它要等第一次 `current()` 才实例化，
         * 而那时已经在 EDT 上了，等于把整段延迟原样搬到用户点击的那一刻。
         */
        fun warmUp() {
            ApplicationManager.getApplication().service<PiReportEndpointCache>()
        }

        suspend fun awaitReady(): PiReportEndpoint? =
            ApplicationManager
                .getApplication()
                .service<PiReportEndpointCache>()
                .awaitEndpoint()
    }
}

/**
 * 本次 IDE 运行期的上报令牌。
 *
 * 应用级而非项目级：内置 HTTP 服务是整个 IDE 一个，handler 也只注册一次。
 */
@Service(Service.Level.APP)
class PiReportTokenHolder {
    val token: String = UUID.randomUUID().toString()
}
