package com.github.izerui.imux.session

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/** 初始只读这么多尾部字节。实测本机 80 个会话文件，最后一条时间戳距文件尾最远 29KB。 */
private const val DEFAULT_TAIL_BYTES = 64L * 1024

/** 翻倍重试的上限。超过它仍找不到，就认定这个文件里没有可用时间戳。 */
private const val MAX_TAIL_BYTES = 1024L * 1024

/**
 * 会话文件里最后一条带 timestamp 的记录的时刻，找不到返回 null。
 *
 * 为什么不能用文件 mtime：resume 时 CLI 会往尾部追加 `mode` 与 `permission-mode`
 * 两条记录，它们不带时间戳、不含任何对话，却把 mtime 推到当下——
 * 于是「打开又关掉」的会话会显示成「刚刚」。记录自身的时间戳没有这个问题。
 *
 * 为什么只读尾部而不整文件扫描：会话文件可达数 MB 且每 3 秒重扫一轮，
 * 而要找的东西几乎总在末尾（实测中位数距尾 762 字节）。窗口不够就翻倍重试，
 * 代价只在极少数文件上付出。
 */
internal fun lastTimestampOf(
    file: Path,
    initialTailBytes: Long = DEFAULT_TAIL_BYTES,
    maxTailBytes: Long = MAX_TAIL_BYTES,
    acceptLine: (String) -> Boolean = { true },
): Instant? = scanTail(file, initialTailBytes, maxTailBytes) { lines ->
    lines.asReversed().filter(acceptLine).firstNotNullOfOrNull(::timestampOf)
}

/**
 * 从文件尾部取若干完整行交给 [extract]，它返回 null 就把窗口翻倍重来。
 *
 * 会话文件可达数 MB 而要找的东西几乎总在末尾，整文件扫描的代价不值得付；
 * 但窗口多大才够又无法预先知道——codex 单条 function_call_output 就可能有几百 KB。
 * 翻倍重试把「常见情况读得少」和「极端情况仍能找到」两头都占上，
 * 代价只在极少数文件上付出。
 *
 * 到了文件头或触到 [maxTailBytes] 仍无结果，就认定这个文件里没有要找的东西。
 */
internal fun <T> scanTail(
    file: Path,
    initialTailBytes: Long = DEFAULT_TAIL_BYTES,
    maxTailBytes: Long = MAX_TAIL_BYTES,
    extract: (List<String>) -> T?,
): T? {
    val size = runCatching { java.nio.file.Files.size(file) }.getOrElse { return null }
    var window = initialTailBytes

    while (true) {
        val from = maxOf(0L, size - window)
        val text = runCatching { readFrom(file, from, size) }.getOrElse { return null }

        // 窗口不是从文件头切入时，第一行必然是半截，丢弃——
        // 残行里可能留着完整的 `"timestamp":"…"` 片段，用它就会张冠李戴
        val lines = text.split('\n').let { if (from > 0L) it.drop(1) else it }

        extract(lines)?.let { return it }

        if (from == 0L || window >= maxTailBytes) return null
        window *= 2
    }
}

/**
 * 取该行顶层的 timestamp 字段。
 *
 * 用 [JsonLineScanner] 而非搜子串：工具输出里常整段嵌着别的 JSON，那里的
 * `\"timestamp\"` 是被转义的，结构化匹配不会误认；解析不出合法时刻的也一并跳过。
 */
private fun timestampOf(line: String): Instant? =
    JsonLineScanner.stringValue(line, "timestamp")
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun readFrom(file: Path, from: Long, size: Long): String {
    val length = (size - from).toInt()
    if (length <= 0) return ""

    val buffer = ByteBuffer.allocate(length)
    FileChannel.open(file, StandardOpenOption.READ).use { channel ->
        channel.position(from)
        while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
    }
    buffer.flip()

    // 窗口起点可能切在一个多字节字符中间，遇到坏字节替换即可，不能让整次读取失败
    return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
        .decode(buffer)
        .toString()
}
