package com.github.liuyuhua.imux

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
            pluginXml.contains("com.github.liuyuhua.imux.terminal.AgentTerminalFileEditorProvider"),
        )
        assertTrue("注册应使用 fileEditorProvider 扩展点", pluginXml.contains("<fileEditorProvider"))
    }

    @Test
    fun `注册了左侧工具窗口`() {
        assertTrue(
            "plugin.xml 未注册 AgentToolWindowFactory",
            pluginXml.contains("com.github.liuyuhua.imux.toolwindow.AgentToolWindowFactory"),
        )
        assertTrue("工具窗口应停靠在左侧", pluginXml.contains("anchor=\"left\""))
    }

    @Test
    fun `声明了对终端插件的依赖`() {
        assertTrue(
            "终端 API 来自捆绑的 terminal 插件，必须声明依赖",
            pluginXml.contains("<depends>org.jetbrains.plugins.terminal</depends>"),
        )
    }
}
