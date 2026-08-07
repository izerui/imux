package com.github.izerui.imux

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守住「写了扩展点实现类却忘记在 plugin.xml 注册」这个坑。
 *
 * 真实故障：AgentTerminalFileEditorProvider 类写好了但没注册，导致没有任何 provider
 * 接受 AgentTerminalVirtualFile；FileEditorManagerImpl.openFileImpl 发现该文件没有
 * 可用编辑器后当场调用 closeFile，表现为「标签页一闪而过」。
 *
 * 这类错误编译器查不出来、单元测试也碰不到，只能靠断言注册内容本身。
 */
class PluginXmlRegistrationTest {

    private val pluginXml: String by lazy {
        val file = File("src/main/resources/META-INF/plugin.xml")
        assertTrue("找不到 plugin.xml：${file.absolutePath}", file.exists())
        file.readText()
    }

    @Test
    fun `注册了终端编辑器的 FileEditorProvider`() {
        assertTrue(
            "plugin.xml 未注册 AgentTerminalFileEditorProvider，" +
                "会导致会话标签页打开后立即被平台关闭",
            pluginXml.contains("com.github.izerui.imux.terminal.AgentTerminalFileEditorProvider"),
        )
        assertTrue("注册应使用 fileEditorProvider 扩展点", pluginXml.contains("<fileEditorProvider"))
    }

    @Test
    fun `注册了终端标签标题提供器`() {
        assertTrue(
            "动态标签标题必须由 EditorTabTitleProvider 提供，不能重命名只读虚拟文件",
            pluginXml.contains("com.github.izerui.imux.terminal.AgentTerminalTabTitleProvider"),
        )
        assertTrue(
            "注册应使用 editorTabTitleProvider 扩展点",
            pluginXml.contains("<editorTabTitleProvider"),
        )
    }

    @Test
    fun `注册了终端标签图标提供器`() {
        assertTrue(
            "plugin.xml 未注册 AgentTerminalFileIconProvider，终端标签页不会显示 Agent 图标",
            pluginXml.contains("com.github.izerui.imux.terminal.AgentTerminalFileIconProvider"),
        )
        assertTrue("注册应使用 fileIconProvider 扩展点", pluginXml.contains("<fileIconProvider"))
    }

    @Test
    fun `注册了左侧工具窗口`() {
        assertTrue(
            "plugin.xml 未注册 AgentToolWindowFactory",
            pluginXml.contains("com.github.izerui.imux.toolwindow.AgentToolWindowFactory"),
        )
        assertTrue("工具窗口应停靠在左侧", pluginXml.contains("anchor=\"left\""))
    }

    @Test
    fun `注册了项目启动活动`() {
        // 工具窗口的内容是懒加载的：监听若只在 createToolWindowContent 里启动，
        // 从没展开过 imux 面板的项目就一条轮次完成提醒都收不到，而用户不会察觉。
        assertTrue(
            "plugin.xml 未注册 ImuxStartupActivity，" +
                "未展开过工具窗口的项目将收不到任何会话完成提醒",
            pluginXml.contains("com.github.izerui.imux.monitor.ImuxStartupActivity"),
        )
        assertTrue("注册应使用 postStartupActivity 扩展点", pluginXml.contains("<postStartupActivity"))
    }

    @Test
    fun `声明了对终端插件的依赖`() {
        assertTrue(
            "终端 API 来自捆绑的 terminal 插件，必须声明依赖",
            pluginXml.contains("<depends>org.jetbrains.plugins.terminal</depends>"),
        )
    }

    @Test
    fun `plugin xml 引用的本地实现类都必须存在`() {
        val classNames = Regex(
            """(?:implementation|class|factoryClass)="(com\.github\.izerui\.imux\.[^"]+)"""",
        ).findAll(pluginXml).map { it.groupValues[1] }.toList()
        val missing = classNames.filterNot { className ->
            File("src/main/kotlin/${className.replace('.', '/')}.kt").exists()
        }

        assertTrue(
            "plugin.xml 引用了不存在的实现类；若它覆盖平台 action，会连平台原 action 一起移除：" +
                missing.joinToString(prefix = "\n"),
            missing.isEmpty(),
        )
    }

    @Test
    fun `插件描述符合官方结构校验规则`() {
        val description = Regex(
            """<description><!\[CDATA\[(.*?)]\]></description>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(pluginXml)?.groupValues?.get(1)?.trim().orEmpty()

        assertTrue("插件描述必须至少包含 40 个字符", description.length >= 40)
        assertTrue(
            "插件描述必须以拉丁字符开头",
            description.firstOrNull()?.let { it in 'A'..'Z' || it in 'a'..'z' } == true,
        )
    }
}
