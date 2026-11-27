package com.github.izerui.imux.progress
import org.junit.Assert.*
import org.junit.Test
class TurnProgressTest {
    @Test fun `直接使用上报百分比`() { assertEquals(37, TurnProgress(37, "正在分析").percent) }
    @Test fun `允许零进度`() { assertEquals(0, TurnProgress(0, "刚开始").percent) }
    @Test fun `允许满格`() { assertEquals(100, TurnProgress(100, "已完成").percent) }
    @Test fun `空说明被拒绝`() { assertThrows(IllegalArgumentException::class.java) { TurnProgress(10, " ") } }
    @Test fun `过长说明被拒绝`() { assertThrows(IllegalArgumentException::class.java) { TurnProgress(10, "字".repeat(201)) } }
}
