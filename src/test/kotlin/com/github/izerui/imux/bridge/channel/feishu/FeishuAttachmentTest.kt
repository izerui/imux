package com.github.izerui.imux.bridge.channel.feishu

import com.github.izerui.imux.bridge.channel.DownloadResult
import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.core.FetchResult
import com.github.izerui.imux.bridge.core.FetchedFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class FeishuAttachmentTest {
    private val tokens = AtomicInteger()
    private val api =
        FeishuApi(
            "cli",
            "sec",
            FeishuDomain.FEISHU,
            FakeHttpJson { _, _, _ -> 200 to """{"code":0,"tenant_access_token":"T${tokens.incrementAndGet()}","expire":7200}""" })

    private class Recorder(private val results: ArrayDeque<DownloadResult>) : HttpFileDownload {
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()

        override suspend fun download(
            url: String,
            maxBytes: Long,
            headers: Map<String, String>,
        ): DownloadResult {
            requests += url to headers["Authorization"]
            return results.removeFirst()
        }
    }

    private val path = Path.of("/tmp/x")

    @Test
    fun `带令牌下载，文件名优先用消息里的`() =
        runBlocking {
            val download = Recorder(ArrayDeque(listOf(DownloadResult.Ok(path, "header.pdf"))))
            val ref = FeishuMediaRef(AttachmentKind.FILE, "f_1", "file", "报告.pdf")
            assertEquals(
                FetchResult.Ok(FetchedFile(path, "报告.pdf")),
                FeishuAttachment("om_1", ref, api, download).fetch(100)
            )
            assertEquals(
                "https://open.feishu.cn/open-apis/im/v1/messages/om_1/resources/f_1?type=file" to "Bearer T1",
                download.requests.single()
            )
            assertEquals(AttachmentKind.FILE, FeishuAttachment("om_1", ref, api, download).kind)
        }

    @Test
    fun `消息没给文件名时用响应头的`() =
        runBlocking {
            val download = Recorder(ArrayDeque(listOf(DownloadResult.Ok(path, "a.png"))))
            val ref = FeishuMediaRef(AttachmentKind.IMAGE, "img", "image", null)
            assertEquals(
                FetchResult.Ok(FetchedFile(path, "a.png")),
                FeishuAttachment("om_1", ref, api, download).fetch(100)
            )
        }

    @Test
    fun `4xx 时重取令牌只重试一次；超限直接返回`() =
        runBlocking {
            val ref = FeishuMediaRef(AttachmentKind.FILE, "f", "file", null)
            val retry =
                Recorder(ArrayDeque(listOf(DownloadResult.Failed("HTTP 400"), DownloadResult.Failed("HTTP 400"))))
            assertEquals(FetchResult.Failed("HTTP 400"), FeishuAttachment("om", ref, api, retry).fetch(100))
            assertEquals(listOf("Bearer T1", "Bearer T2"), retry.requests.map { it.second })

            val tooLarge = Recorder(ArrayDeque(listOf(DownloadResult.TooLarge)))
            assertEquals(FetchResult.TooLarge, FeishuAttachment("om", ref, api, tooLarge).fetch(100))
        }
}
