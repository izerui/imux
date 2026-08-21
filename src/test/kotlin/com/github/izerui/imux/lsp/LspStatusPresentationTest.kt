package com.github.izerui.imux.lsp

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/**
 * 「状态 → 怎么显示」的**行为**测试。
 *
 * 这一组用例存在的理由，是设置页只能做源码文本断言，而文本断言可以被
 * 「原有字面量一个字不改、在它前面加一句守卫」整个绕过：
 *
 * ```
 * if (finding.status == LspStatus.AUTO_MANAGED) return message("settings.lsp.status.binary")
 * return when (finding.status) { …六条分支原样保留… }
 * ```
 *
 * 这种写法能让 pi 组的 TypeScript / Python / Ruby / Rust / PHP / C# 重新显示成
 * 「服务器不在 PATH 中」——正是整轮改造要消灭的那条假消息——而逐分支钉字面量的
 * 断言全绿。这里直接**调用**映射，返回值一变就红，守卫无处藏身。
 */
class LspStatusPresentationTest {

    @Test
    fun `每个状态对应的文案键`() {
        assertEquals("settings.lsp.status.config", statusMessageKey(LspStatus.MISSING_CONFIG))
        assertEquals("settings.lsp.status.binary", statusMessageKey(LspStatus.MISSING_BINARY))
        assertEquals("settings.lsp.status.unknown", statusMessageKey(LspStatus.UNKNOWN))
        assertEquals("settings.lsp.status.auto", statusMessageKey(LspStatus.AUTO_MANAGED))
        assertEquals("settings.lsp.status.unavailable", statusMessageKey(LspStatus.NOT_AVAILABLE))
    }

    /** 就绪那一栏显示的是 server 二进制名，没有可翻译的文案。 */
    @Test
    fun `就绪没有文案键`() {
        assertNull(statusMessageKey(LspStatus.READY))
    }

    /**
     * `AUTO_MANAGED` 与 `NOT_AVAILABLE` 是这轮改造新加的两个状态，也是最容易被
     * 「顺手复用一条现成文案」毁掉的两个：说成「服务器不在 PATH 中」就是假消息，
     * 说成「插件未启用」是让用户去做一件根本不存在的事。
     */
    @Test
    fun `新增的两个状态不得复用别的状态的文案`() {
        val byStatus = LspStatus.entries.associateWith(::statusMessageKey)
        val keys = byStatus.values.filterNotNull()

        assertEquals("每个状态的文案键必须互不相同", keys.size, keys.toSet().size)
    }

    /**
     * 唯一真正要守住的图标不变量：**只有用户真能采取行动的两种状态配警告**。
     *
     * 刻意写成集合相等而不是逐个断言常量身份——后者会把
     * 「NEUTRAL 现在落在 AllIcons.General.Note 上」这种可微调的取舍一起冻死，
     * 将来换个更贴的图标要改测试，失败信息还像抓到了缺陷。
     */
    @Test
    fun `只有可行动的缺口才配警告图标`() {
        assertEquals(
            setOf(LspStatus.MISSING_CONFIG, LspStatus.MISSING_BINARY),
            LspStatus.entries.filter { statusIconKind(it) == StatusIconKind.WARNING }.toSet(),
        )
    }

    @Test
    fun `每个状态的图标语义类别`() {
        assertEquals(StatusIconKind.OK, statusIconKind(LspStatus.READY))
        assertEquals(StatusIconKind.WARNING, statusIconKind(LspStatus.MISSING_CONFIG))
        assertEquals(StatusIconKind.WARNING, statusIconKind(LspStatus.MISSING_BINARY))
        // pi-lens 自动装是好消息；官方无对应插件用户无从处理；没查出来就是没查出来。
        assertEquals(StatusIconKind.INFO, statusIconKind(LspStatus.AUTO_MANAGED))
        assertEquals(StatusIconKind.NEUTRAL, statusIconKind(LspStatus.NOT_AVAILABLE))
        assertEquals(StatusIconKind.QUESTION, statusIconKind(LspStatus.UNKNOWN))
    }

    /**
     * 键必须在资源包里真实存在，且资源包里不能留孤儿。
     *
     * 前一半挡的是打错的键（`ImuxBundle` 取不到只会显示成 `!key!`，跑起来才看得见）；
     * 后一半挡的是「改了映射、忘了删旧键」——`ImuxBundleTest` 只比对十个语言文件
     * 的键集合是否一致，十个文件一起留着同一个没人用的键，它照样全绿。
     */
    @Test
    fun `文案键与资源包双向对齐`() {
        val bundle = Properties().apply {
            File("src/main/resources/messages/ImuxBundle.properties").reader(Charsets.UTF_8).use(::load)
        }
        val used = LspStatus.entries.mapNotNull(::statusMessageKey).toSet()

        used.forEach { key ->
            assertTrue("资源包里没有 $key，界面上会显示成 !$key!", bundle.containsKey(key))
        }
        assertEquals(
            "资源包里有没人使用的 settings.lsp.status.* 键",
            used,
            bundle.stringPropertyNames().filter { it.startsWith("settings.lsp.status.") }.toSet(),
        )
    }

    /**
     * Kotlin 一门语言两边的 server 是**不同的两个程序**：Claude Code 官方插件用
     * JetBrains 的 `kotlin-lsp`，pi-lens 用社区的 `kotlin-language-server`。
     * 取错边会在「已就绪」那一栏指认一个根本没在跑的进程。
     */
    @Test
    fun `pi 与 codex 取 pi-lens 的 server，claude 取官方插件的`() {
        val kotlin = LspCatalog.languages.single { it.id == "kotlin" }

        assertEquals("kotlin-language-server", serverBinaryFor(kotlin, AgentType.PI))
        assertEquals("kotlin-language-server", serverBinaryFor(kotlin, AgentType.CODEX))
        assertEquals("kotlin-lsp", serverBinaryFor(kotlin, AgentType.CLAUDE))
    }

    /** 非 gated 语言在 pi 那边没有二进制（pi-lens 按需装），不能凭空拿 Claude 那边的顶上。 */
    @Test
    fun `自动管理的语言在 pi 侧没有 server 二进制`() {
        val typescript = LspCatalog.languages.single { it.id == "typescript" }

        assertNull(serverBinaryFor(typescript, AgentType.PI))
        assertEquals("typescript-language-server", serverBinaryFor(typescript, AgentType.CLAUDE))
    }
}
