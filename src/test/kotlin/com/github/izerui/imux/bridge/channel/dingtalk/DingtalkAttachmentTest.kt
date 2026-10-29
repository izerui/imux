package com.github.izerui.imux.bridge.channel.dingtalk

import com.github.izerui.imux.bridge.channel.DownloadResult
import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.core.FetchResult
import com.github.izerui.imux.bridge.core.FetchedFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

class DingtalkAttachmentTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Volatile
    private var downloadAnswer: Pair<Int, String> = 200 to """{"downloadUrl":"https://oss/x?Expires=1"}"""

    private val http =
        DingtalkFakeHttp { url, _ ->
            when {
                url.endsWith("/v1.0/oauth2/accessToken") -> 200 to """{"accessToken":"T","expireIn":7200}"""
                url.endsWith("/v1.0/robot/messageFiles/download") -> downloadAnswer
                else -> 404 to "{}"
            }
        }
    private val api = DingtalkApi("cid", "sec", http)
    private val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()

    private fun downloader(result: () -> DownloadResult) =
        HttpFileDownload { url, _, headers ->
            requests += url to headers
            result()
        }

    @Test
    fun `先换地址再下载，文件名用消息里的`() =
        runBlocking {
            val file = temp.newFile().toPath()
            val attachment =
                DingtalkAttachment(
                    DingtalkMediaRef(AttachmentKind.FILE, "d1", "报告.pdf"),
                    api,
                    downloader { DownloadResult.Ok(file, "x.file") },
                )
            assertEquals(AttachmentKind.FILE, attachment.kind)
            assertEquals(FetchResult.Ok(FetchedFile(file, "报告.pdf")), attachment.fetch(100))
            assertEquals(listOf("https://oss/x?Expires=1" to emptyMap<String, String>()), requests.toList())
        }

    @Test
    fun `消息里没有文件名时不用下载地址给的名字`() =
        runBlocking {
            val file = temp.newFile().toPath()
            val attachment = DingtalkAttachment(DingtalkMediaRef(AttachmentKind.IMAGE, "d1", null), api, downloader { DownloadResult.Ok(file, "x.file") })
            assertEquals(FetchResult.Ok(FetchedFile(file, null)), attachment.fetch(100))
        }

    @Test
    fun `换地址失败、超限、下载失败`() =
        runBlocking {
            downloadAnswer = 400 to """{"code":"invalidParameter.robotCode.downloadCode","message":"expired"}"""
            val failed = DingtalkAttachment(DingtalkMediaRef(AttachmentKind.IMAGE, "d1", null), api, downloader { DownloadResult.TooLarge })
            assertEquals(FetchResult.Failed("invalidParameter.robotCode.downloadCode"), failed.fetch(100))
            assertEquals(0, requests.size)
            downloadAnswer = 200 to """{"downloadUrl":"https://oss/x"}"""
            assertEquals(FetchResult.TooLarge, failed.fetch(100))
            val broken = DingtalkAttachment(DingtalkMediaRef(AttachmentKind.IMAGE, "d1", null), api, downloader { DownloadResult.Failed("HTTP 403") })
            assertEquals(FetchResult.Failed("HTTP 403"), broken.fetch(100))
        }
}
