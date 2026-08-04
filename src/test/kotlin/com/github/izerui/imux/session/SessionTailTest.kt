package com.github.izerui.imux.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.time.Instant

class SessionTailTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun write(vararg lines: String): Path =
        folder.newFile().toPath().apply { toFile().writeText(lines.joinToString("\n") + "\n") }

    @Test
    fun `取最后一条带时间戳的记录`() {
        val file = write(
            """{"type":"user","timestamp":"2026-07-31T03:30:00.000Z"}""",
            """{"type":"assistant","timestamp":"2026-07-31T03:38:28.877Z"}""",
        )

        assertEquals(Instant.parse("2026-07-31T03:38:28.877Z"), lastTimestampOf(file))
    }

    @Test
    fun `跳过 resume 追加的无时间戳记录`() {
        // 这是本 bug 的核心场景：resume 会在尾部追加 mode / permission-mode，
        // 它们不带时间戳也不含对话，不该让会话看起来「刚刚活动过」
        val file = write(
            """{"type":"assistant","timestamp":"2026-07-31T03:38:28.877Z"}""",
            """{"type":"last-prompt","sessionId":"x"}""",
            """{"type":"ai-title","aiTitle":"讨论用 Java 重写项目"}""",
            """{"type":"mode","mode":"default","sessionId":"x"}""",
            """{"type":"permission-mode","permissionMode":"default","sessionId":"x"}""",
        )

        assertEquals(Instant.parse("2026-07-31T03:38:28.877Z"), lastTimestampOf(file))
    }

    @Test
    fun `没有任何时间戳时返回 null`() {
        val file = write(
            """{"type":"mode","mode":"default"}""",
            """{"type":"permission-mode","permissionMode":"default"}""",
        )

        assertNull(lastTimestampOf(file))
    }

    @Test
    fun `时间戳在初始窗口之外时扩大窗口重试`() {
        val filler = "x".repeat(500)
        val lines = buildList {
            add("""{"type":"assistant","timestamp":"2026-07-31T03:38:28.877Z"}""")
            repeat(40) { add("""{"type":"mode","padding":"$filler"}""") }
        }
        val file = write(*lines.toTypedArray())

        assertEquals(
            Instant.parse("2026-07-31T03:38:28.877Z"),
            lastTimestampOf(file, initialTailBytes = 256),
        )
    }

    @Test
    fun `窗口切断的半行不被当作有效记录`() {
        // 窗口起点落在一行中间时，残片可能恰好留下形似 "timestamp":"…" 的完整字段，
        // 但它未必属于这条记录（超长行的中段就可能嵌着别的东西）。残行一律丢弃。
        val file = write(
            """{"type":"user","content":"aaaa","timestamp":"2020-01-01T00:00:00.000Z"}""",
            """{"type":"mode","mode":"default"}""",
        )

        // 上限与初始窗口取同值，逼停翻倍重试，让窗口停在残行状态：
        // 此时宁可返回 null 交给上层回退，也不能报一个来路不明的时间
        assertNull(lastTimestampOf(file, initialTailBytes = 45, maxTailBytes = 45))
    }

    @Test
    fun `窗口翻倍到覆盖整个文件后仍能读到被截断过的那一行`() {
        val file = write(
            """{"type":"user","content":"aaaa","timestamp":"2020-01-01T00:00:00.000Z"}""",
            """{"type":"mode","mode":"default"}""",
        )

        assertEquals(
            Instant.parse("2020-01-01T00:00:00.000Z"),
            lastTimestampOf(file, initialTailBytes = 45),
        )
    }

    @Test
    fun `不把转义文本里的时间戳当成记录自身的时间`() {
        // 工具输出里常整段嵌着别的 JSON，那里的 \"timestamp\" 是被转义的，不是本记录的字段
        val file = write(
            """{"type":"user","content":"日志：{\"timestamp\":\"2020-01-01T00:00:00.000Z\"}"}""",
        )

        assertNull(lastTimestampOf(file))
    }
}
