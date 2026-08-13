package com.github.izerui.imux.session

/** pi 扩展报上来的一条：某个标签页此刻在跑哪个会话。 */
data class PiSessionReport(val tabId: String, val sessionId: String)

/**
 * 从 pi 的会话文件路径取出会话 id。
 *
 * 文件名形如 `2026-08-13T09-45-00-045Z_019ffa82-bd8d-7edd-a9aa-e5e711524a7a.jsonl`：
 * 时间戳里也带横杠，所以只能按**最后一个下划线**切，不能按横杠。
 *
 * 解析放在这边而不是 js 扩展里：这里能单测，那边不进测试链路。
 */
internal fun piSessionIdOf(sessionFile: String): String? {
    val name = sessionFile.substringAfterLast('/')
    if (!name.endsWith(JSONL_SUFFIX)) return null
    val id = name.removeSuffix(JSONL_SUFFIX).substringAfterLast('_')
    return id.takeIf(::looksLikePiSessionId)
}

/**
 * 解析上报体。
 *
 * 手写扫描而不是引 JSON 库：报文只有两个字符串字段，且这段代码跑在 netty 的
 * EventLoop 线程上，越简单越好。任何不合形状的输入一律返回 null——
 * 上报来自另一个进程，不能假定它的内容。
 */
internal fun parsePiReport(body: String): PiSessionReport? {
    val tabId = JsonLineScanner.stringValue(body, "tabId")?.takeIf { it.isNotBlank() } ?: return null
    val sessionFile = JsonLineScanner.stringValue(body, "sessionFile") ?: return null
    val sessionId = piSessionIdOf(sessionFile) ?: return null
    return PiSessionReport(tabId, sessionId)
}

private fun looksLikePiSessionId(value: String): Boolean =
    value.length == PI_ID_LENGTH &&
        value.withIndex().all { (index, char) ->
            if (index in PI_ID_DASH_POSITIONS) char == '-' else char.isLetterOrDigit()
        }

private const val JSONL_SUFFIX = ".jsonl"
private const val PI_ID_LENGTH = 36
private val PI_ID_DASH_POSITIONS = setOf(8, 13, 18, 23)
