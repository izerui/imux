package com.github.izerui.imux.bridge.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test

class QrCodesTest {
    @Test
    fun `生成的二维码可以被解码回原文`() {
        val content = "https://work.weixin.qq.com/ai/qc/c?s=SCODE&hide_more_btn=true"
        val image = qrImage(content, 240)
        val pixels = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels)))
        assertEquals(content, QRCodeReader().decode(bitmap).text)
    }
}
