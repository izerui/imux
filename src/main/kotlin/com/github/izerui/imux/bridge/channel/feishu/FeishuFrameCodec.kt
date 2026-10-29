package com.github.izerui.imux.bridge.channel.feishu

import java.io.ByteArrayOutputStream

data class FeishuHeader(
    val key: String,
    val value: String,
)

/**
 * 飞书长连接帧 `pbbp2.Frame`，字段号与官方 SDK 内嵌的 pbbp2.proto 一致：
 * SeqID=1、LogID=2、service=3、method=4 是 proto2 required，编码时总要写出；
 * headers=5、payload_encoding=6、payload_type=7、payload=8、LogIDNew=9 可选。
 */
class FeishuFrame(
    val seqId: Long = 0,
    val logId: Long = 0,
    val service: Int = 0,
    val method: Int = 0,
    val headers: List<FeishuHeader> = emptyList(),
    val payloadEncoding: String? = null,
    val payloadType: String? = null,
    val payload: ByteArray? = null,
    val logIdNew: String? = null,
) {
    fun header(key: String): String? = headers.firstOrNull { it.key == key }?.value

    fun withHeader(
        key: String,
        value: String,
    ): FeishuFrame = copy(headers = headers + FeishuHeader(key, value))

    fun copy(
        headers: List<FeishuHeader> = this.headers,
        payload: ByteArray? = this.payload,
    ): FeishuFrame =
        FeishuFrame(seqId, logId, service, method, headers, payloadEncoding, payloadType, payload, logIdNew)

    override fun equals(other: Any?): Boolean =
        other is FeishuFrame &&
                seqId == other.seqId &&
                logId == other.logId &&
                service == other.service &&
                method == other.method &&
                headers == other.headers &&
                payloadEncoding == other.payloadEncoding &&
                payloadType == other.payloadType &&
                (payload?.contentEquals(other.payload) ?: (other.payload == null)) &&
                logIdNew == other.logIdNew

    override fun hashCode(): Int = listOf(
        seqId,
        logId,
        service,
        method,
        headers,
        payloadEncoding,
        payloadType,
        payload?.contentHashCode(),
        logIdNew
    ).hashCode()

    /** 不打印 payload：里面是消息正文。 */
    override fun toString(): String =
        "FeishuFrame(service=$service, method=$method, headers=$headers, payloadBytes=${payload?.size})"

    companion object {
        const val METHOD_CONTROL = 0
        const val METHOD_DATA = 1
    }
}

/** 只实现 pbbp2 用到的两种编码：varint（wire type 0）与长度前缀（wire type 2）；其余 wire type 解码时跳过。 */
object FeishuFrameCodec {
    fun encode(frame: FeishuFrame): ByteArray {
        val out = ByteArrayOutputStream()
        out.varintField(1, frame.seqId)
        out.varintField(2, frame.logId)
        out.varintField(3, frame.service.toLong())
        out.varintField(4, frame.method.toLong())
        frame.headers.forEach { out.bytesField(5, encodeHeader(it)) }
        frame.payloadEncoding?.let { out.bytesField(6, it.toByteArray(Charsets.UTF_8)) }
        frame.payloadType?.let { out.bytesField(7, it.toByteArray(Charsets.UTF_8)) }
        frame.payload?.let { out.bytesField(8, it) }
        frame.logIdNew?.let { out.bytesField(9, it.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    /** 无法解析（截断、长度越界、不支持的 wire type）时抛 [IllegalArgumentException]。 */
    fun decode(bytes: ByteArray): FeishuFrame {
        val reader = Reader(bytes)
        var seqId = 0L
        var logId = 0L
        var service = 0
        var method = 0
        val headers = mutableListOf<FeishuHeader>()
        var payloadEncoding: String? = null
        var payloadType: String? = null
        var payload: ByteArray? = null
        var logIdNew: String? = null
        while (reader.hasMore()) {
            val tag = reader.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            when {
                field == 1 && wire == 0 -> seqId = reader.varint()
                field == 2 && wire == 0 -> logId = reader.varint()
                field == 3 && wire == 0 -> service = reader.varint().toInt()
                field == 4 && wire == 0 -> method = reader.varint().toInt()
                field == 5 && wire == 2 -> headers += decodeHeader(reader.bytes())
                field == 6 && wire == 2 -> payloadEncoding = String(reader.bytes(), Charsets.UTF_8)
                field == 7 && wire == 2 -> payloadType = String(reader.bytes(), Charsets.UTF_8)
                field == 8 && wire == 2 -> payload = reader.bytes()
                field == 9 && wire == 2 -> logIdNew = String(reader.bytes(), Charsets.UTF_8)
                else -> reader.skip(wire)
            }
        }
        return FeishuFrame(seqId, logId, service, method, headers, payloadEncoding, payloadType, payload, logIdNew)
    }

    private fun encodeHeader(header: FeishuHeader): ByteArray {
        val out = ByteArrayOutputStream()
        out.bytesField(1, header.key.toByteArray(Charsets.UTF_8))
        out.bytesField(2, header.value.toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    private fun decodeHeader(bytes: ByteArray): FeishuHeader {
        val reader = Reader(bytes)
        var key = ""
        var value = ""
        while (reader.hasMore()) {
            val tag = reader.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            when {
                field == 1 && wire == 2 -> key = String(reader.bytes(), Charsets.UTF_8)
                field == 2 && wire == 2 -> value = String(reader.bytes(), Charsets.UTF_8)
                else -> reader.skip(wire)
            }
        }
        return FeishuHeader(key, value)
    }

    private fun ByteArrayOutputStream.varint(value: Long) {
        var remaining = value
        while (true) {
            if (remaining and 0x7FL.inv() == 0L) {
                write(remaining.toInt())
                return
            }
            write(((remaining and 0x7F) or 0x80).toInt())
            remaining = remaining ushr 7
        }
    }

    private fun ByteArrayOutputStream.varintField(
        field: Int,
        value: Long,
    ) {
        varint((field shl 3).toLong())
        varint(value)
    }

    private fun ByteArrayOutputStream.bytesField(
        field: Int,
        value: ByteArray,
    ) {
        varint(((field shl 3) or 2).toLong())
        varint(value.size.toLong())
        write(value)
    }

    private class Reader(
        private val bytes: ByteArray,
    ) {
        private var position = 0

        fun hasMore(): Boolean = position < bytes.size

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(position < bytes.size) { "varint 被截断" }
                val byte = bytes[position++].toInt() and 0xFF
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                require(shift < 64) { "varint 过长" }
            }
        }

        fun bytes(): ByteArray {
            val length = varint()
            require(length >= 0 && length <= bytes.size - position) { "长度越界" }
            val end = position + length.toInt()
            return bytes.copyOfRange(position, end).also { position = end }
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> advance(8)
                2 -> bytes()
                5 -> advance(4)
                else -> throw IllegalArgumentException("不支持的 wire type：$wire")
            }
        }

        private fun advance(count: Int) {
            require(count <= bytes.size - position) { "字段被截断" }
            position += count
        }
    }
}
