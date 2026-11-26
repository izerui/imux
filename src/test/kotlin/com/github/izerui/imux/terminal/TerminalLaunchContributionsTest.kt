package com.github.izerui.imux.terminal

import com.github.izerui.imux.settings.GuidanceCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class TerminalLaunchContributionsTest {
    private fun server(name: String) = TerminalMcpServer(name, "http://127.0.0.1:1/$name", "${name.uppercase()}_TOKEN")

    @Test
    fun `合并后的引导能力是各功能声明的并集，没声明的功能不影响`() {
        val code = TerminalLaunchContribution(guidance = setOf(GuidanceCapability.CODE_TOOLS))
        val mobile = TerminalLaunchContribution()
        assertEquals(setOf(GuidanceCapability.CODE_TOOLS), TerminalLaunchContributions.merge(listOf(mobile, code)).guidance)
        assertEquals(emptySet<GuidanceCapability>(), TerminalLaunchContributions.merge(listOf(mobile)).guidance)
    }

    @Test
    fun `合并后服务按顺序拼接，环境变量合在一起，Pi 扩展去重，只有一份 Claude 设置时原样保留`() {
        val shared = Path.of("/tmp/pi-imux-mcp.js")
        val merged = TerminalLaunchContributions.merge(listOf(
            TerminalLaunchContribution(listOf(server("a")), mapOf("A" to "1"), listOf(shared)),
            TerminalLaunchContribution(listOf(server("b")), mapOf("B" to "2"), listOf(shared), claudeSettings = "{}"),
        ))
        assertEquals(listOf("a", "b"), merged.servers.map { it.name })
        assertEquals(mapOf("A" to "1", "B" to "2"), merged.environment)
        assertEquals(listOf(shared), merged.piExtensions)
        assertEquals("{}", merged.claudeSettings)
        assertNull(TerminalLaunchContributions.merge(listOf(TerminalLaunchContribution())).claudeSettings)
    }

    @Test
    fun `两份 Claude 设置的 hook 按事件接在一起，手机和改完检查都保留，其它顶层项保留`() {
        val mobile = """{"hooks":{"PermissionRequest":[{"matcher":"*","hooks":[{"url":"m1"}]}],"PostToolUse":[{"matcher":"*","hooks":[{"url":"m2"}]}]}}"""
        val editCheck = """{"hooks":{"PostToolUse":[{"matcher":"Edit|Write","hooks":[{"url":"e1"}]}]},"other":1}"""
        val merged = com.google.gson.JsonParser.parseString(TerminalLaunchContributions.mergeClaudeSettings(listOf(mobile, editCheck))!!).asJsonObject
        val hooks = merged.getAsJsonObject("hooks")
        assertEquals("手机的确认事件还在", "m1", hooks.getAsJsonArray("PermissionRequest")[0].asJsonObject.getAsJsonArray("hooks")[0].asJsonObject["url"].asString)
        val postToolUse = hooks.getAsJsonArray("PostToolUse").map { it.asJsonObject["matcher"].asString }
        assertEquals("用完工具事件两份都在、手机在前", listOf("*", "Edit|Write"), postToolUse)
        assertEquals("其它顶层项保留", 1, merged["other"].asInt)
    }

    @Test
    fun `没有 Claude 设置时为空，有一份不是合法 JSON 时跳过它`() {
        assertNull(TerminalLaunchContributions.mergeClaudeSettings(emptyList()))
        val merged = TerminalLaunchContributions.mergeClaudeSettings(listOf("not json", """{"hooks":{"PostToolUse":[{"matcher":"Edit"}]}}"""))!!
        assertTrue(merged.contains("\"matcher\":\"Edit\""))
    }

    @Test
    fun `合并后关闭会逐个释放各功能`() {
        val released = mutableListOf<String>()
        TerminalLaunchContributions.merge(listOf(
            TerminalLaunchContribution { released += "a" },
            TerminalLaunchContribution { released += "b" },
        )).close()
        assertEquals(listOf("a", "b"), released)
    }

    @Test
    fun `前面的功能释放出错，后面的功能照样释放，且不往外抛`() {
        val released = mutableListOf<String>()
        TerminalLaunchContributions.merge(listOf(
            TerminalLaunchContribution { throw IllegalStateException("mobile") },
            TerminalLaunchContribution { released += "code" },
        )).close()
        assertEquals(listOf("code"), released)
    }

    @Test
    fun `按旧版编译的扩展调用的两个构造方法（直接给参数、用默认参数）在字节码里都还在`() {
        val type = TerminalLaunchContribution::class.java
        val old = arrayOf(List::class.java, Map::class.java, List::class.java, String::class.java, Function0::class.java)
        val direct = type.getConstructor(*old)
        val withDefaults = type.getConstructor(*old, Int::class.javaPrimitiveType, Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"))
        val released = mutableListOf<String>()
        val built = direct.newInstance(listOf(server("a")), mapOf("A" to "1"), emptyList<Path>(), null, { released += "a" })
        assertEquals(listOf("a"), built.servers.map { it.name })
        assertEquals(emptySet<GuidanceCapability>(), built.guidance)
        built.close()
        assertEquals(listOf("a"), released)
        // 掩码 0b11111：五个参数都取默认值，等同旧代码里的 TerminalLaunchContribution()
        val defaults = withDefaults.newInstance(null, null, null, null, null, 0b11111, null)
        assertEquals(emptyList<TerminalMcpServer>(), defaults.servers)
    }
}
