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
     * 任意两个状态都不得共用一条文案——最容易踩的是这轮改造新加的
     * `AUTO_MANAGED` 与 `NOT_AVAILABLE`：说成「服务器不在 PATH 中」就是假消息，
     * 说成「插件未启用」是让用户去做一件根本不存在的事。
     */
    @Test
    fun `每个状态的文案键互不相同`() {
        val keys = LspStatus.entries.mapNotNull(::statusMessageKey)

        assertEquals("每个状态的文案键必须互不相同：$keys", keys.size, keys.toSet().size)
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
        // 官方无对应插件用户无从处理；没查出来就是没查出来。
        assertEquals(StatusIconKind.NEUTRAL, statusIconKind(LspStatus.NOT_AVAILABLE))
        assertEquals(StatusIconKind.QUESTION, statusIconKind(LspStatus.UNKNOWN))
    }

    /**
     * **pi-lens 自己提供的那些语言必须与就绪长得一模一样。**
     *
     * 这一条是照用户反馈新加的。他看到 pi 组的 C# 写着「由 pi-lens 按需安装」、配一个
     * 蓝色信息图标，问的是「为什么还要按需安装？」——那一行长得像一件待办，而实际上
     * 用户什么都不用做。判据是**用户视角**：绿 = 我不用做任何事。
     *
     * 只断言「不是 WARNING」是不够的：退回一个自成一档的 INFO/NEUTRAL 也不是 WARNING，
     * 而界面上仍然是一个「跟绿勾不一样」的牌子，用户仍然会去找自己该做什么。
     * 所以断言的是**与 READY 同一个类别**——那才是「我不用管」这句话在界面上的形状。
     */
    @Test
    fun `pi-lens 自己提供的语言与就绪同一个图标`() {
        assertEquals(
            "AUTO_MANAGED 上用户什么都不用做，它在界面上必须与就绪长得一样",
            statusIconKind(LspStatus.READY),
            statusIconKind(LspStatus.AUTO_MANAGED),
        )
    }

    /**
     * 枚举里不许留没人映到的语义类别。
     *
     * `INFO` 随 AUTO_MANAGED 改判一起删了。留着的话，设置页那个 `when` 里会有一条永远
     * 走不到的分支——而下一位维护者会合理地以为「有这一档，那就该有状态用它」，
     * 于是把某个状态挪过去，界面上凭空多出一种没人想过的牌子。
     */
    @Test
    fun `每个语义类别都至少有一个状态用到`() {
        val used = LspStatus.entries.map(::statusIconKind).toSet()

        assertEquals(
            "这些语义类别没有任何状态映到，设置页里对应的分支是死代码：${StatusIconKind.entries - used}",
            StatusIconKind.entries.toSet(),
            used,
        )
    }

    /**
     * 键必须在资源包里真实存在，且资源包里不能留孤儿。
     *
     * 前一半挡的是打错的键（`ImuxBundle` 取不到只会显示成 `!key!`，跑起来才看得见）；
     * 后一半挡的是「改了映射、忘了删旧键」——`ImuxBundleTest` 只比对十个语言文件
     * 的键集合是否一致，十个文件一起留着同一个没人用的键，它照样全绿。
     *
     * `settings.lsp.status.*` 这个命名空间有**两个**生产者：静态状态走
     * [statusMessageKey]，命令跑起来之后那一句「正在启用…」走 [ENABLING_STATUS_KEY]。
     * 孤儿检查必须两个一起算——只算一个的话，另一个生产的键全都会被判成孤儿，
     * 失败信息还会把维护者往「删掉它」的方向指，而那正是页面上唯一会动的一列。
     */
    @Test
    fun `文案键与资源包双向对齐`() {
        val bundle = Properties().apply {
            File("src/main/resources/messages/ImuxBundle.properties").reader(Charsets.UTF_8).use(::load)
        }
        val used = LspStatus.entries.mapNotNull(::statusMessageKey).toSet() + ENABLING_STATUS_KEY

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

    /**
     * 就绪那一列**显示出来的那串字**——这是用户直接看见的东西，不是一个可空的内部值。
     *
     * 设置页那一侧只能做源码文本断言，而「让这一列变成一整列空白」的改法不止一种：
     * 把 `?: ""` 写成恒空、在设置页里补一句 `private fun String?.orEmpty(): String = ""`
     * 遮蔽默认导入……被钉死的函数体一个字节都不用改。这条用例直接**调用**它，
     * 返回值一变就红。
     */
    @Test
    fun `就绪那一列显示的是供能的 server 二进制名`() {
        val kotlin = LspCatalog.languages.single { it.id == "kotlin" }

        assertEquals("kotlin-language-server", readyServerText(kotlin, AgentType.PI))
        assertEquals("kotlin-language-server", readyServerText(kotlin, AgentType.CODEX))
        assertEquals("kotlin-lsp", readyServerText(kotlin, AgentType.CLAUDE))
    }

    /**
     * 目录表里的每一门语言，在**任何**一个 CLI 下都至少有一边能说出 server 名字。
     *
     * 这一条挡的是「整列空白」：把 [readyServerText] 改成恒返回空串，上一条只会红在
     * kotlin 一行，这一条会把 18 门语言一次性摊开——失败信息直接列出哪几门变哑了。
     */
    @Test
    fun `没有哪门语言在所有 CLI 下都说不出 server 名字`() {
        val mute = LspCatalog.languages.filter { language ->
            AgentType.entries.all { readyServerText(language, it).isEmpty() }
        }

        assertEquals("这些语言的「就绪」列在任何 CLI 下都是空白：${mute.map { it.id }}", emptyList<String>(), mute.map { it.id })
    }
}
