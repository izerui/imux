package com.github.izerui.imux.bridge.channel

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class OggOpusTest {
    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/feishu/$name")!!.readBytes()

    private fun rms(samples: ShortArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    @Test
    fun `单声道 16k 解成 1 秒左右的 16k PCM`() {
        val pcm = OggOpus.decode(resource("tone-16k-mono.opus"), maxSamples = 10 * OggOpus.SAMPLE_RATE)
        assertTrue("采样数 ${pcm.size}", pcm.size in 16_000..16_400)
        assertTrue("不能是静音", rms(pcm) > 1_000)
    }

    @Test
    fun `立体声 48k 由解码器直接混成 16k 单声道`() {
        val pcm = OggOpus.decode(resource("tone-48k-stereo.opus"), maxSamples = 10 * OggOpus.SAMPLE_RATE)
        assertTrue("采样数 ${pcm.size}", pcm.size in 16_000..16_400)
        assertTrue("不能是静音", rms(pcm) > 1_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `超过上限报错`() {
        OggOpus.decode(resource("tone-16k-mono.opus"), maxSamples = 8_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `不是 Ogg 时报错`() {
        OggOpus.decode("not an ogg file at all, definitely".toByteArray(), maxSamples = 16_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `截断时报错`() {
        val bytes = resource("tone-16k-mono.opus")
        OggOpus.decode(bytes.copyOf(bytes.size - 10), maxSamples = 10 * OggOpus.SAMPLE_RATE)
    }
}
