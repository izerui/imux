package com.github.izerui.imux.bridge.channel.feishu

import com.github.izerui.imux.bridge.channel.DownloadResult
import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.core.FetchResult
import com.github.izerui.imux.bridge.core.FetchedFile
import com.github.izerui.imux.bridge.core.InboundAttachment

/** 飞书消息里的一个附件：只在 [fetch] 时才带令牌下载；内容不加密。 */
class FeishuAttachment(
    private val messageId: String,
    private val ref: FeishuMediaRef,
    private val api: FeishuApi,
    private val download: HttpFileDownload,
) : InboundAttachment {
    override val kind: AttachmentKind
        get() = ref.kind

    override suspend fun fetch(maxBytes: Long): FetchResult {
        var retried = false
        while (true) {
            val token =
                when (val result = api.token()) {
                    is FeishuResult.Ok -> result.value
                    is FeishuResult.Error -> return FetchResult.Failed("token ${result.code}")
                    is FeishuResult.Network -> return FetchResult.Failed(result.reason)
                }
            val url = api.resourceUrl(messageId, ref.fileKey, ref.resourceType)
            when (val result = download.download(url, maxBytes, mapOf("Authorization" to "Bearer $token"))) {
                DownloadResult.TooLarge -> return FetchResult.TooLarge
                is DownloadResult.Ok -> return FetchResult.Ok(FetchedFile(result.path, ref.fileName ?: result.fileName))
                is DownloadResult.Failed -> {
                    // 令牌恰好失效时平台以 4xx 拒绝：清掉令牌重取一次
                    if (!retried && result.reason in RETRY_ON) {
                        retried = true
                        api.invalidateToken()
                        continue
                    }
                    return FetchResult.Failed(result.reason)
                }
            }
        }
    }

    private companion object {
        val RETRY_ON = setOf("HTTP 400", "HTTP 401")
    }
}
