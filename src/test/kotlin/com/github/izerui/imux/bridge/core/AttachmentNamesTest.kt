package com.github.izerui.imux.bridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class AttachmentNamesTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    @Test
    fun `清理文件名：去掉路径、控制字符与非法字符，空名返回 null`() {
        assertEquals("报告.pdf", AttachmentNames.sanitize("../../etc/报告.pdf"))
        assertEquals("a.png", AttachmentNames.sanitize("C:\\temp\\a.png"))
        assertEquals("ab.txt", AttachmentNames.sanitize("a\u0000b?.txt"))
        assertEquals("x.log", AttachmentNames.sanitize("  x.log.. "))
        assertNull(AttachmentNames.sanitize(null))
        assertNull(AttachmentNames.sanitize(" .. "))
    }

    @Test
    fun `过长文件名截断到 120 字符并保留扩展名`() {
        val name = AttachmentNames.sanitize("a".repeat(300) + ".pdf")!!
        assertEquals(120, name.length)
        assertEquals(true, name.endsWith(".pdf"))
        assertEquals(120, AttachmentNames.sanitize("b".repeat(300))!!.length)
    }

    @Test
    fun `按文件头识别扩展名`() {
        assertEquals("png", AttachmentNames.extensionOf(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A)))
        assertEquals("jpg", AttachmentNames.extensionOf(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("gif", AttachmentNames.extensionOf(ascii("GIF89a")))
        assertEquals("webp", AttachmentNames.extensionOf(ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WEBP")))
        assertEquals("bmp", AttachmentNames.extensionOf(ascii("BM") + bytes(0, 0)))
        assertEquals("pdf", AttachmentNames.extensionOf(ascii("%PDF-1.7")))
        assertEquals("zip", AttachmentNames.extensionOf(bytes(0x50, 0x4B, 0x03, 0x04)))
        assertEquals("mp4", AttachmentNames.extensionOf(bytes(0, 0, 0, 0x18) + ascii("ftypisom")))
        assertEquals("mp4", AttachmentNames.extensionOf(bytes(0, 0, 0, 0x14) + ascii("ftypqt  ")))
        assertNull(AttachmentNames.extensionOf(ascii("hello")))
        assertNull(AttachmentNames.extensionOf(ByteArray(0)))
    }

    @Test
    fun `默认文件名按类型与时间命名，识别不了用 bin`() {
        assertEquals("image-153012.png", AttachmentNames.fallback(AttachmentKind.IMAGE, LocalTime.of(15, 30, 12), "png"))
        assertEquals("file-090501.bin", AttachmentNames.fallback(AttachmentKind.FILE, LocalTime.of(9, 5, 1), null))
        assertEquals("video-000000.mp4", AttachmentNames.fallback(AttachmentKind.VIDEO, LocalTime.MIDNIGHT, "mp4"))
    }

    @Test
    fun `同名时依次编号`() {
        val taken = setOf("a.png", "a-1.png", "noext")
        assertEquals("b.png", AttachmentNames.unique("b.png") { it in taken })
        assertEquals("a-2.png", AttachmentNames.unique("a.png") { it in taken })
        assertEquals("noext-1", AttachmentNames.unique("noext") { it in taken })
    }
}
