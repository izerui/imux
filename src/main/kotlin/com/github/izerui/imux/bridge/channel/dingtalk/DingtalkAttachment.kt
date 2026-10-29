package com.github.izerui.imux.bridge.channel.dingtalk

import com.github.izerui.imux.bridge.channel.DownloadResult
import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.channel.download
import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.core.FetchResult
import com.github.izerui.imux.bridge.core.FetchedFile
import com.github.izerui.imux.bridge.core.InboundAttachment

/**
 * 钉钉消息里的一个附件：[fetch] 时才用下载码换临时地址再下载（地址是 OSS 签名地址，不带令牌）。
 * 下载地址给出的文件名可能是 `.file`，不采用；消息里没有文件名时交给附件落盘按文件头命名。
 */
class DingtalkAttachment(
    private val ref: DingtalkMediaRef,
    private val api: DingtalkApi,
    private val download: HttpFileDownload,
) : InboundAttachment {
    override val kind: AttachmentKind
        get() = ref.kind

    override suspend fun fetch(maxBytes: Long): FetchResult {
        val url =
            when (val result = api.downloadUrl(ref.downloadCode)) {
                is DingtalkResult.Ok -> result.value
                is DingtalkResult.Error -> return FetchResult.Failed(result.code)
                is DingtalkResult.Network -> return FetchResult.Failed(result.reason)
            }
        return when (val result = download.download(url, maxBytes)) {
            DownloadResult.TooLarge -> FetchResult.TooLarge
            is DownloadResult.Ok -> FetchResult.Ok(FetchedFile(result.path, ref.fileName))
            is DownloadResult.Failed -> FetchResult.Failed(result.reason)
        }
    }
}
