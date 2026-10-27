package com.github.izerui.imux.bridge.channel.wecom

import com.github.izerui.imux.bridge.channel.DownloadResult
import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.core.FetchResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class WecomMediaTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val key = ByteArray(32).also { Random(1).nextBytes(it) }

    /** 企业微信下发的 aeskey 常省略结尾的 `=`。 */
    private val keyText = Base64.getEncoder().withoutPadding().encodeToString(key)

    /** 按官方规则加密：PKCS#7 填充到 32 字节倍数，AES-256-CBC，IV 为密钥前 16 字节。 */
    private fun encrypt(plain: ByteArray): ByteArray {
        val pad = 32 - plain.size % 32
        val padded = plain + ByteArray(pad) { pad.toByte() }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key, 0, 16))
        return cipher.doFinal(padded)
    }

    private fun file(bytes: ByteArray): Path = temp.newFile().toPath().also { Files.write(it, bytes) }

    @Test
    fun `按 32 字节填充规则解回原文`() {
        listOf(0, 1, 31, 32, 33, 200_003).forEach { size ->
            val plain = ByteArray(size) { (it * 7).toByte() }
            val target = temp.newFile().toPath()
            WecomMedia.decryptFile(file(encrypt(plain)), target, keyText)
            assertArrayEquals("size=$size", plain, Files.readAllBytes(target))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `填充不合法时报错`() {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key, 0, 16))
        val bad = cipher.doFinal(ByteArray(32) { 0 })
        WecomMedia.decryptFile(file(bad), temp.newFile().toPath(), keyText)
    }

    private class FakeDownload(private val result: () -> DownloadResult) : HttpFileDownload {
        var maxBytes = 0L

        override suspend fun download(
            url: String,
            maxBytes: Long,
        ): DownloadResult {
            this.maxBytes = maxBytes
            return result()
        }
    }

    @Test
    fun `取回：下载、解密、删除密文，带回文件名`() =
        runBlocking {
            val plain = "hello".toByteArray()
            val encrypted = file(encrypt(plain))
            val download = FakeDownload { DownloadResult.Ok(encrypted, "a.txt") }
            val result = WecomAttachment(AttachmentKind.FILE, "u", keyText, download).fetch(1_000) as FetchResult.Ok
            assertArrayEquals(plain, Files.readAllBytes(result.file.path))
            assertEquals("a.txt", result.file.suggestedName)
            assertFalse("密文临时文件应删除", Files.exists(encrypted))
            assertEquals("密文最多比明文多 32 字节填充", 1_032L, download.maxBytes)
        }

    @Test
    fun `取回：下载过大或失败原样返回`() =
        runBlocking {
            assertEquals(FetchResult.TooLarge, WecomAttachment(AttachmentKind.VIDEO, "u", keyText, FakeDownload { DownloadResult.TooLarge }).fetch(10))
            assertEquals(FetchResult.Failed("HTTP 404"), WecomAttachment(AttachmentKind.IMAGE, "u", keyText, FakeDownload { DownloadResult.Failed("HTTP 404") }).fetch(10))
        }

    @Test
    fun `取回：解密后超过上限返回过大，解密失败返回失败，都不留临时文件`() =
        runBlocking {
            val big = file(encrypt(ByteArray(40)))
            assertEquals(FetchResult.TooLarge, WecomAttachment(AttachmentKind.FILE, "u", keyText, FakeDownload { DownloadResult.Ok(big, null) }).fetch(20))
            assertFalse(Files.exists(big))
            // 长度不是 16 的倍数，一定解不开
            val garbage = file(ByteArray(47) { 1 })
            assertTrue(WecomAttachment(AttachmentKind.FILE, "u", keyText, FakeDownload { DownloadResult.Ok(garbage, null) }).fetch(1_000) is FetchResult.Failed)
            assertFalse(Files.exists(garbage))
        }

    @Test
    fun `下载完成后被取消时删除密文临时文件`() =
        runBlocking {
            val encrypted = file(encrypt("hello".toByteArray()))
            val download =
                object : HttpFileDownload {
                    override suspend fun download(
                        url: String,
                        maxBytes: Long,
                    ): DownloadResult {
                        currentCoroutineContext().job.cancel()
                        return DownloadResult.Ok(encrypted, null)
                    }
                }
            val job = launch { WecomAttachment(AttachmentKind.FILE, "u", keyText, download).fetch(1_000) }
            job.join()
            assertTrue(job.isCancelled)
            assertFalse("密文临时文件应删除", Files.exists(encrypted))
            assertEquals("不应残留明文临时文件", emptyList<String>(), Files.list(encrypted.parent).use { s -> s.map { it.fileName.toString() }.filter { it.endsWith(".bin") }.toList() })
        }
}
