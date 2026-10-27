package com.github.izerui.imux.bridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `后缀加在扩展名前，没有扩展名时加在末尾`() {
        assertEquals("报告-k3f9.pdf", AttachmentNames.withSuffix("报告.pdf", "k3f9"))
        assertEquals("a.tar-k3f9.gz", AttachmentNames.withSuffix("a.tar.gz", "k3f9"))
        assertEquals("noext-k3f9", AttachmentNames.withSuffix("noext", "k3f9"))
        assertEquals(".env-k3f9", AttachmentNames.withSuffix(".env", "k3f9"))
    }

    @Test
    fun `随机后缀为 4 位小写字母与数字`() {
        val random = kotlin.random.Random(7)
        repeat(200) { assertTrue(Regex("[a-z0-9]{4}").matches(AttachmentNames.randomSuffix(random))) }
    }

    @Test
    fun `过长的中文文件名按 UTF-8 字节截断到 200 字节以内并保留扩展名`() {
        val name = AttachmentNames.sanitize("报".repeat(150) + ".pdf")!!
        assertTrue("实际 ${name.toByteArray(Charsets.UTF_8).size} 字节", name.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue(name.endsWith(".pdf"))
        assertEquals("报".repeat(65) + ".pdf", name)
        val noExtension = AttachmentNames.sanitize("报".repeat(150))!!
        assertEquals("报".repeat(66), noExtension)
    }

    @Test
    fun `截断不拆开代理对`() {
        // 每个 emoji 占 2 个 char、4 个 UTF-8 字节
        val name = AttachmentNames.sanitize("\uD83D\uDE00".repeat(100) + ".png")!!
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue(name.endsWith(".png"))
        val stem = name.removeSuffix(".png")
        assertEquals(0, stem.length % 2)
        assertTrue(stem.codePoints().allMatch { it == 0x1F600 })
    }

    @Test
    fun `ASCII 文件名截断行为不变`() {
        assertEquals("a".repeat(116) + ".pdf", AttachmentNames.sanitize("a".repeat(300) + ".pdf"))
        assertEquals("b".repeat(120), AttachmentNames.sanitize("b".repeat(300)))
        assertEquals("short.txt", AttachmentNames.sanitize("short.txt"))
    }

    @Test
    fun `去掉 Unicode 格式字符，防止伪装扩展名`() {
        assertEquals("invoicefdp.exe", AttachmentNames.sanitize("invoice\u202Efdp.exe"))
        assertEquals("ab.txt", AttachmentNames.sanitize("a\u200Bb\uFEFF.txt"))
        assertNull(AttachmentNames.sanitize("\u202E\u200F"))
    }
}
