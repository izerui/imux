package com.github.izerui.imux.bridge.channel.feishu

import com.github.izerui.imux.bridge.channel.DownloadResult
import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.core.AttachmentKind
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class FeishuSpeechTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val tone = javaClass.getResourceAsStream("/feishu/tone-16k-mono.opus")!!.readBytes()
    private val ref = FeishuMediaRef(AttachmentKind.AUDIO, "a_1", "file", null)
    private val chunks = CopyOnWriteArrayList<Int>()
    private val configs = CopyOnWriteArrayList<com.google.gson.JsonObject>()
    private val downloaded = CopyOnWriteArrayList<Path>()

    private fun http(
        code: Int,
        texts: ArrayDeque<String>,
    ) = FakeHttpJson { _, url, body ->
        when {
            url.endsWith("/tenant_access_token/internal") -> 200 to """{"code":0,"tenant_access_token":"T","expire":7200}"""
            url.endsWith("/speech_to_text/v1/speech/file_recognize") -> {
                val json = JsonParser.parseString(body).asJsonObject
                chunks += Base64.getDecoder().decode(json["speech"].asJsonObject["speech"].asString).size
                configs += json["config"].asJsonObject
                200 to """{"code":$code,"msg":"m","data":{"recognition_text":"${
                    texts.removeFirstOrNull().orEmpty()
                }"}}"""
            }

            else -> 404 to "{}"
        }
    }

    private fun download(bytes: ByteArray) =
        HttpFileDownload { _, _, _ ->
            val file = temp.newFile().toPath()
            Files.write(file, bytes)
            downloaded.add(file)
            DownloadResult.Ok(file, null)
        }

    private fun speech(
        code: Int = 0,
        texts: List<String> = emptyList(),
        bytes: ByteArray = tone,
        chunkSamples: Int = 50 * 16_000,
    ) = FeishuSpeech(
        FeishuApi("cli", "sec", FeishuDomain.FEISHU, http(code, ArrayDeque(texts))),
        download(bytes),
        chunkSamples = chunkSamples
    )

    @Test
    fun `解码后按段识别并按顺序拼接`() =
        runBlocking {
            assertEquals(
                "你好世界。",
                speech(texts = listOf("你好", "世界", "。"), chunkSamples = 8_000).transcribe("om_1", ref)
            )
            assertEquals(3, chunks.size)
            assertTrue(chunks.all { it <= 8_000 * 2 })
            assertTrue("总字节 ${chunks.sum()}", chunks.sum() in 32_000..32_800)
            configs.forEach {
                assertEquals("pcm", it["format"].asString)
                assertEquals("16k_auto", it["engine_type"].asString)
                assertTrue(Regex("[a-z0-9]{16}").matches(it["file_id"].asString))
            }
            assertFalse("临时文件要删掉", Files.exists(downloaded.single()))
        }

    @Test
    fun `识别接口报错时返回 null`() =
        runBlocking {
            assertNull(speech(code = 99991672).transcribe("om_1", ref))
        }

    @Test
    fun `识别结果为空时返回 null`() =
        runBlocking {
            assertNull(speech(texts = listOf("  ")).transcribe("om_1", ref))
        }

    @Test
    fun `任一段识别为空时整体返回 null，不交出部分文字`() =
        runBlocking {
            // 第一段有文字、第二段为空（或缺 recognition_text）：退回音频附件，不能只交出前半段
            assertNull(speech(texts = listOf("你好", ""), chunkSamples = 8_000).transcribe("om_1", ref))
            assertEquals("第二段为空后不再识别后续段", 2, chunks.size)
        }

    @Test
    fun `不是 Opus 时返回 null，临时文件也删掉`() =
        runBlocking {
            assertNull(speech(bytes = "garbage".toByteArray()).transcribe("om_1", ref))
            assertFalse(Files.exists(downloaded.single()))
            assertTrue(chunks.isEmpty())
        }
}
