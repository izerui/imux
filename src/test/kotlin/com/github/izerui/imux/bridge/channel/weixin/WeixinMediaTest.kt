package com.github.izerui.imux.bridge.channel.weixin

import com.github.izerui.imux.bridge.channel.DownloadResult
import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.core.FetchResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class WeixinMediaTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val keyHex = "000102030405060708090a0b0c0d0e0f"
    private val key = ByteArray(16) { it.toByte() }
    private val plain = "图片内容 hello".toByteArray()
    private val requests = CopyOnWriteArrayList<Pair<String, Long>>()

    private fun encrypt(data: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            doFinal(data)
        }

    private fun downloader(bytes: ByteArray) =
        HttpFileDownload { url, maxBytes, _ ->
            requests += url to maxBytes
            if (bytes.size > maxBytes) {
                DownloadResult.TooLarge
            } else {
                val file = temp.newFile().toPath()
                Files.write(file, bytes)
                DownloadResult.Ok(file, "x.bin")
            }
        }

    @Test
    fun `下载地址：full_url 优先，否则拼 CDN 并编码`() {
        assertEquals("https://cdn/f", weixinDownloadUrl(WeixinMediaRef(AttachmentKind.FILE, "https://cdn/f", "p", keyHex, null)))
        assertEquals(
            "https://novac2c.cdn.weixin.qq.com/c2c/download?encrypted_query_param=a%2Bb%2Fc%3D",
            weixinDownloadUrl(WeixinMediaRef(AttachmentKind.FILE, null, "a+b/c=", keyHex, null)),
        )
    }

    @Test
    fun `AES-128-ECB 解密，文件名用消息里的`() =
        runBlocking {
            val attachment = WeixinAttachment(WeixinMediaRef(AttachmentKind.FILE, "https://cdn/f", null, keyHex, "报告.pdf"), downloader(encrypt(plain)))
            assertEquals(AttachmentKind.FILE, attachment.kind)
            val ok = attachment.fetch(100) as FetchResult.Ok
            assertArrayEquals(plain, Files.readAllBytes(ok.file.path))
            assertEquals("报告.pdf", ok.file.suggestedName)
            assertEquals("https://cdn/f" to 116L, requests.single())
        }

    @Test
    fun `图片没有密钥按明文；文件名交给落盘`() =
        runBlocking {
            val ok = WeixinAttachment(WeixinMediaRef(AttachmentKind.IMAGE, "https://cdn/i", null, null, null), downloader(plain)).fetch(100) as FetchResult.Ok
            assertArrayEquals(plain, Files.readAllBytes(ok.file.path))
            assertEquals(null, ok.file.suggestedName)
            assertEquals(100L, requests.single().second)
        }

    @Test
    fun `超限、解密失败与下载失败`() =
        runBlocking {
            val ref = WeixinMediaRef(AttachmentKind.FILE, "https://cdn/f", null, keyHex, null)
            assertEquals(FetchResult.TooLarge, WeixinAttachment(ref, downloader(encrypt(ByteArray(200)))).fetch(100))
            val bad = WeixinAttachment(ref, downloader(ByteArray(15)))
            val failed = bad.fetch(100) as FetchResult.Failed
            assertFalse("原因不含地址", failed.reason.contains("cdn"))
            assertTrue("解密失败的临时文件要删掉", temp.root.listFiles()!!.none { it.length() == 15L })
            assertEquals(
                FetchResult.Failed("HTTP 403"),
                WeixinAttachment(ref, HttpFileDownload { _, _, _ -> DownloadResult.Failed("HTTP 403") }).fetch(100),
            )
        }
}
