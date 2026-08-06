package com.github.izerui.imux.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 这个类被 4 个调用方依赖，却一直没有直接测试。
 *
 * 它存在的全部理由是「正则实现会栈溢出」，那条正是最该被守住的。
 */
class JsonLineScannerTest {

    @Test
    fun `按键取出字符串值`() {
        val line = """{"type":"assistant","aiTitle":"分析工程结构"}"""

        assertEquals("分析工程结构", JsonLineScanner.stringValue(line, "aiTitle"))
    }

    @Test
    fun `键不存在时返回 null`() {
        assertEquals(null, JsonLineScanner.stringValue("""{"a":"1"}""", "b"))
    }

    @Test
    fun `容忍键与值之间的空白`() {
        assertEquals("v", JsonLineScanner.stringValue("""{"k"  :   "v"}""", "k"))
    }

    // ---- 值不是字符串 ----

    @Test
    fun `值为数字时跳过`() {
        assertNull(JsonLineScanner.stringValue("""{"pid":1234}""", "pid"))
    }

    @Test
    fun `值为 null 时跳过`() {
        assertNull(JsonLineScanner.stringValue("""{"stop_reason":null}""", "stop_reason"))
    }

    @Test
    fun `值为对象时跳过`() {
        assertNull(JsonLineScanner.stringValue("""{"message":{"role":"user"}}""", "message"))
    }

    /** 同名键先以非字符串出现、后以字符串出现时，要继续往后找而不是就此放弃。 */
    @Test
    fun `同名键第二次出现才是字符串值`() {
        val line = """{"title":123,"x":1,"title":"真正的标题"}"""

        assertEquals("真正的标题", JsonLineScanner.stringValue(line, "title"))
    }

    // ---- 转义 ----

    @Test
    fun `反转义常见转义序列`() {
        val line = """{"text":"第一行\n第二行\t制表\"引号\"\\反斜杠\/斜杠"}"""

        assertEquals(
            "第一行\n第二行\t制表\"引号\"\\反斜杠/斜杠",
            JsonLineScanner.stringValue(line, "text"),
        )
    }

    @Test
    fun `反转义 unicode 转义`() {
        assertEquals("中", JsonLineScanner.stringValue("""{"c":"中"}""", "c"))
    }

    @Test
    fun `反转义回车与退格`() {
        assertEquals("\r\b", JsonLineScanner.stringValue("""{"c":"\r\b"}""", "c"))
    }

    @Test
    fun `反转义换页符`() {
        assertEquals("", JsonLineScanner.stringValue("""{"c":"\f"}""", "c"))
    }

    // ---- 残缺输入 ----

    @Test
    fun `字符串未闭合时返回 null`() {
        assertNull(JsonLineScanner.stringValue("""{"k":"没有收尾""", "k"))
    }

    @Test
    fun `unicode 转义被截断时返回 null`() {
        assertNull(JsonLineScanner.stringValue("""{"k":"\u4e""", "k"))
    }

    @Test
    fun `行尾恰好是反斜杠时返回 null`() {
        assertNull(JsonLineScanner.stringValue("""{"k":"a\""", "k"))
    }

    @Test
    fun `空字符串是合法值`() {
        assertEquals("", JsonLineScanner.stringValue("""{"k":""}""", "k"))
    }

    // ---- 这个类存在的理由 ----

    /**
     * 正则实现在这里必然 StackOverflowError：它按被匹配值的长度递归。
     * 真实 codex 会话里一条用户消息的 text 值长 11860 字符，实测必炸。
     */
    @Test
    fun `超长值不栈溢出`() {
        val huge = "x".repeat(500_000)
        val line = """{"text":"$huge"}"""

        assertEquals(huge, JsonLineScanner.stringValue(line, "text"))
    }

    /** 工具输出里常整段嵌着别的 JSON，那里的键是被转义的，不该被认成真键。 */
    @Test
    fun `不把嵌在字符串里的转义键当作真键`() {
        val line = """{"output":"{\"timestamp\":\"2020-01-01T00:00:00Z\"}","timestamp":"2026-08-06T00:00:00Z"}"""

        assertEquals("2026-08-06T00:00:00Z", JsonLineScanner.stringValue(line, "timestamp"))
    }
}
