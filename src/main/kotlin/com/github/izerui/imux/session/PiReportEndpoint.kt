package com.github.izerui.imux.session

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
data class PiReportEndpoint(val url: String, val token: String) {

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
        fun current(): PiReportEndpoint? =
            ApplicationManager.getApplication().service<PiReportEndpointCache>().endpoint()
    }
}

/**
 * 缓存本次 IDE 运行期的上报端点，并保证计算不落在 EDT 上。
 *
 * 应用级：内置 HTTP 服务整个 IDE 一个，端口与令牌都没有项目维度。
 */
@Service(Service.Level.APP)
class PiReportEndpointCache(scope: CoroutineScope) {

    /**
     * 算好的端点；还没算好时为 null。
     *
     * 后台协程写、EDT 读，故必须 volatile。用字段而不是让调用方去 await 一个
     * Deferred，是为了让「读」这件事在任何线程上都确定不阻塞。
     */
    @Volatile
    private var cached: PiReportEndpoint? = null

    init {
        // 服务一被实例化就在后台开算，不等第一次调用。放在 init 里用协程而不是
        // 直接算：服务的构造发生在谁先碰到它的那个线程上，可能就是 EDT，
        // 在构造函数里阻塞和在 endpoint() 里阻塞一样糟。
        scope.launch(Dispatchers.IO) { cached = compute() }
    }

    /**
     * 已经算好就返回，还没算好则返回 null——**绝不等待**。
     *
     * 代价明确且可接受：端点还没算出来时开的 pi 标签页会退回「不上报」
     * （标签页不自动跟随，会话本身照常启动）。而这个窗口实际上碰不到——
     * [warmUp] 在项目打开时就把它算好了，远早于用户点开任何会话。
     * 拿这点换「任何情况下都不卡 EDT」是划算的：卡 UI 是用户能立刻感知的伤害。
     */
    fun endpoint(): PiReportEndpoint? = cached

    private fun compute(): PiReportEndpoint? = runCatching {
        val port = BuiltInServerManager.getInstance().waitForStart().port
        if (port <= 0) return@runCatching null
        PiReportEndpoint(
            url = "http://127.0.0.1:$port$PI_REPORT_PATH",
            token = ApplicationManager.getApplication()
                .getService(PiReportTokenHolder::class.java)
                .token,
        )
    }.getOrNull()

    companion object {
        /**
         * 触发缓存计算。由项目启动活动调用——那是后台协程，且发生在用户可能点开
         * 任何会话之前，于是 [endpoint] 在真正被用到的时候总是现成的。
         *
         * 服务本身是懒加载的：不主动碰一下，它要等第一次 `current()` 才实例化，
         * 而那时已经在 EDT 上了，等于把整段延迟原样搬到用户点击的那一刻。
         */
        fun warmUp() {
            ApplicationManager.getApplication().service<PiReportEndpointCache>()
        }
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
