package com.github.izerui.imux.session

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
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
        /** 内置服务未就绪时返回 null，调用方据此退回「不上报」。 */
        fun current(): PiReportEndpoint? = runCatching {
            val port = BuiltInServerManager.getInstance().waitForStart().port
            if (port <= 0) return null
            PiReportEndpoint(
                url = "http://127.0.0.1:$port$PI_REPORT_PATH",
                token = ApplicationManager.getApplication()
                    .getService(PiReportTokenHolder::class.java)
                    .token,
            )
        }.getOrNull()
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
