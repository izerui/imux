package com.github.izerui.imux.settings

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImuxSettingsTest {
    @Test
    fun `session close confirmation is enabled by default`() {
        assertTrue(ImuxSettings().state.confirmBeforeClosingSession)
    }

    @Test
    fun `project new menu is shown by default`() {
        assertTrue(ImuxSettings().state.showProjectNewAgentMenu)
    }

    @Test
    fun `IDEA MCP session injection uses the platform default port`() {
        val state = ImuxSettings().state

        assertTrue(state.injectIdeaMcp)
        assertEquals(64342, state.ideaMcpPort)
    }

    @Test
    fun `未自定义端口时采用自动检测值`() {
        val settings = ImuxSettings()

        settings.initializeIdeaMcpPortDefault(64355)

        assertEquals(64355, settings.state.ideaMcpPort)
        assertEquals(false, settings.state.ideaMcpPortCustomized)
    }

    @Test
    fun `自动端口成功初始化后不再变化`() {
        val settings = ImuxSettings()

        settings.initializeIdeaMcpPortDefault(64355)
        settings.initializeIdeaMcpPortDefault(64356)

        assertEquals(64355, settings.state.ideaMcpPort)
    }

    @Test
    fun `自动端口首次读取失败后允许重试`() {
        val settings = ImuxSettings()

        settings.initializeIdeaMcpPortDefault(null)
        settings.initializeIdeaMcpPortDefault(64355)

        assertEquals(64355, settings.state.ideaMcpPort)
    }

    @Test
    fun `用户修改端口后不再被自动检测覆盖`() {
        val settings = ImuxSettings()
        settings.setIdeaMcpPort(64360)

        settings.initializeIdeaMcpPortDefault(64355)

        assertEquals(64360, settings.state.ideaMcpPort)
        assertTrue(settings.state.ideaMcpPortCustomized)
    }

    @Test
    fun `IDEA MCP 引导默认开启并提供完整默认提示词`() {
        val settings = ImuxSettings()

        assertEquals(true, settings.state.ideaMcpGuidanceEnabled)
        assertTrue(settings.ideaMcpGuidance.contains("rename_refactoring"))
        assertTrue(settings.ideaMcpGuidance.contains("xdebug_set_breakpoint"))
    }

    // region 提示词骨架断言——保护原有四段结构和安全规则

    @Test
    fun `英文引导词包含 MUST use 段`() {
        assertTrue(DEFAULT_IDEA_MCP_GUIDANCE_EN.contains("MUST use IDEA MCP"))
    }

    @Test
    fun `英文引导词包含 SHOULD PREFER 段`() {
        assertTrue(DEFAULT_IDEA_MCP_GUIDANCE_EN.contains("SHOULD PREFER IDEA MCP"))
    }

    @Test
    fun `英文引导词包含 MUST NOT use 段`() {
        assertTrue(DEFAULT_IDEA_MCP_GUIDANCE_EN.contains("MUST NOT use IDEA MCP"))
    }

    @Test
    fun `英文引导词包含 NEVER retry 安全规则`() {
        assertTrue(DEFAULT_IDEA_MCP_GUIDANCE_EN.contains("NEVER retry these after an ambiguous failure"))
    }

    @Test
    fun `中文引导词包含必须使用段`() {
        assertTrue(DEFAULT_IDEA_MCP_GUIDANCE_ZH.contains("必须使用 IDEA MCP"))
    }

    @Test
    fun `中文引导词包含应该优先使用段`() {
        assertTrue(DEFAULT_IDEA_MCP_GUIDANCE_ZH.contains("应该优先使用 IDEA MCP"))
    }

    @Test
    fun `中文引导词包含不得使用段`() {
        assertTrue(DEFAULT_IDEA_MCP_GUIDANCE_ZH.contains("不得使用 IDEA MCP"))
    }

    @Test
    fun `中文引导词包含绝对不要重试安全规则`() {
        assertTrue(DEFAULT_IDEA_MCP_GUIDANCE_ZH.contains("出现不明确的失败后绝对不要重试"))
    }

    // endregion

    // region 新增决策规则的内容断言——钉住完整关键句，而非工具名

    @Test
    fun `英文 LSP 分流：活跃时用 LSP 做快速查找`() {
        assertTrue(
            DEFAULT_IDEA_MCP_GUIDANCE_EN.contains(
                "If your LSP is active for the current file's language, use it for quick single-symbol lookups",
            ),
        )
    }

    @Test
    fun `英文 LSP 分流：需要调用层级树或跨模块符号追踪时切到 IDEA MCP`() {
        assertTrue(
            DEFAULT_IDEA_MCP_GUIDANCE_EN.contains(
                "Switch to IDEA MCP when you need full call hierarchy trees (`analyze_calls`) or cross-module symbol tracing through inheritance and interfaces",
            ),
        )
    }

    @Test
    fun `英文 LSP 分流：不得包含无依据的框架感知导航描述`() {
        assertFalse(DEFAULT_IDEA_MCP_GUIDANCE_EN.contains("framework-aware navigation"))
    }

    @Test
    fun `中文 LSP 分流：不得包含无依据的框架感知导航描述`() {
        assertFalse(DEFAULT_IDEA_MCP_GUIDANCE_ZH.contains("框架感知导航"))
    }

    @Test
    fun `英文 LSP 分流：未配置时改用 IDEA MCP 做语义导航`() {
        assertTrue(
            DEFAULT_IDEA_MCP_GUIDANCE_EN.contains(
                "If LSP is not active or not configured, use IDEA MCP for ALL semantic navigation",
            ),
        )
    }

    @Test
    fun `中文 LSP 分流：活跃时用 LSP 做快速查找`() {
        assertTrue(
            DEFAULT_IDEA_MCP_GUIDANCE_ZH.contains(
                "如果你的 LSP 对当前文件的语言处于活跃状态，对于单符号快速查找（跳转定义、悬停信息、查找引用）使用 LSP 即可",
            ),
        )
    }

    @Test
    fun `中文 LSP 分流：需要调用层级树或跨模块符号追踪时切到 IDEA MCP`() {
        assertTrue(
            DEFAULT_IDEA_MCP_GUIDANCE_ZH.contains(
                "当你需要完整的调用层级树（`analyze_calls`）或跨模块追踪继承和接口的符号解析时，切换到 IDEA MCP",
            ),
        )
    }

    @Test
    fun `中文 LSP 分流：未配置时改用 IDEA MCP 做语义导航`() {
        assertTrue(
            DEFAULT_IDEA_MCP_GUIDANCE_ZH.contains(
                "如果 LSP 未激活或未配置，所有语义导航都使用 IDEA MCP",
            ),
        )
    }

    @Test
    fun `英文 SHOULD PREFER 表：运行配置完整行标明条件性使用`() {
        assertTrue(
            DEFAULT_IDEA_MCP_GUIDANCE_EN.contains(
                "| Find and use a matching IDE run configuration (if one exists) | `get_run_configurations` → `execute_run_configuration` (only when matched) |",
            ),
        )
    }

    @Test
    fun `中文 SHOULD PREFER 表：运行配置完整行标明条件性使用`() {
        assertTrue(
            DEFAULT_IDEA_MCP_GUIDANCE_ZH.contains(
                "| 查找并使用匹配的 IDE 运行配置（如果存在）             | `get_run_configurations` → `execute_run_configuration`（仅在匹配时） |",
            ),
        )
    }

    @Test
    fun `英文 SHOULD PREFER 表包含 get_all_open_file_paths`() {
        assertTrue(
            DEFAULT_IDEA_MCP_GUIDANCE_EN.contains(
                "| See what files the developer is currently editing | `get_all_open_file_paths`",
            ),
        )
    }

    @Test
    fun `中文 SHOULD PREFER 表包含 get_all_open_file_paths`() {
        assertTrue(
            DEFAULT_IDEA_MCP_GUIDANCE_ZH.contains(
                "| 查看开发者当前正在编辑哪些文件                      | `get_all_open_file_paths`",
            ),
        )
    }

    @Test
    fun `英文编辑后验证步骤 1：有构建步骤且涉及编译型代码或跨文件改动时运行 build_project`() {
        assertTrue(
            DEFAULT_IDEA_MCP_GUIDANCE_EN.contains(
                "1. When the project has an effective IDE build step and your changes involve compiled code, cross-file references, or public signatures, run `build_project`",
            ),
        )
    }

    @Test
    fun `英文编辑后验证步骤 1：无构建步骤则跳过`() {
        assertTrue(
            DEFAULT_IDEA_MCP_GUIDANCE_EN.contains(
                "skip this when the project has no effective build step",
            ),
        )
    }

    @Test
    fun `英文编辑后验证步骤 2：lint_files 捕获框架规则违规`() {
        assertTrue(
            DEFAULT_IDEA_MCP_GUIDANCE_EN.contains(
                "2. Run `lint_files` on every file you changed — it catches framework-rule violations and warnings that the compiler alone would miss",
            ),
        )
    }

    @Test
    fun `英文编辑后验证步骤 3：有匹配配置时使用 execute_run_configuration`() {
        assertTrue(
            DEFAULT_IDEA_MCP_GUIDANCE_EN.contains(
                "when a matching IDE run configuration exists, use `execute_run_configuration`",
            ),
        )
    }

    @Test
    fun `英文编辑后验证步骤 3：无匹配配置时退回原生测试命令`() {
        assertTrue(
            DEFAULT_IDEA_MCP_GUIDANCE_EN.contains(
                "when no matching configuration exists, fall back to the project's native test command",
            ),
        )
    }

    @Test
    fun `中文编辑后验证步骤 1：有构建步骤且涉及编译型代码或跨文件改动时运行 build_project`() {
        assertTrue(
            DEFAULT_IDEA_MCP_GUIDANCE_ZH.contains(
                "1. 当项目存在有效的 IDE 构建步骤且改动涉及编译型代码、跨文件引用或公共签名时，运行 `build_project`",
            ),
        )
    }

    @Test
    fun `中文编辑后验证步骤 1：无构建步骤则跳过`() {
        assertTrue(
            DEFAULT_IDEA_MCP_GUIDANCE_ZH.contains(
                "若项目没有有效构建步骤则跳过",
            ),
        )
    }

    @Test
    fun `中文编辑后验证步骤 2：lint_files 捕获框架规则违规`() {
        assertTrue(
            DEFAULT_IDEA_MCP_GUIDANCE_ZH.contains(
                "2. 对所有修改过的文件运行 `lint_files`——它能发现编译器遗漏的框架规则违规和警告",
            ),
        )
    }

    @Test
    fun `中文编辑后验证步骤 3：有匹配配置时使用 execute_run_configuration`() {
        assertTrue(
            DEFAULT_IDEA_MCP_GUIDANCE_ZH.contains(
                "存在匹配的 IDE 运行配置时使用 `execute_run_configuration`",
            ),
        )
    }

    @Test
    fun `中文编辑后验证步骤 3：无匹配配置时退回原生测试命令`() {
        assertTrue(
            DEFAULT_IDEA_MCP_GUIDANCE_ZH.contains(
                "不存在匹配配置时，退回项目原生测试命令",
            ),
        )
    }

    @Test
    fun `英文调试优先规则钉住完整决策句`() {
        assertTrue(
            DEFAULT_IDEA_MCP_GUIDANCE_EN.contains(
                "When investigating a runtime bug, prefer interactive debugging over adding print/log statements",
            ),
        )
    }

    @Test
    fun `中文调试优先规则钉住完整决策句`() {
        assertTrue(
            DEFAULT_IDEA_MCP_GUIDANCE_ZH.contains(
                "排查运行时 bug 时，优先使用交互式调试而非添加 print/log 语句",
            ),
        )
    }

    // endregion

    @Test
    fun `IDEA MCP 引导支持自定义并恢复默认`() {
        val settings = ImuxSettings()

        settings.setIdeaMcpGuidance("custom")
        assertEquals("custom", settings.ideaMcpGuidance)

        settings.setIdeaMcpGuidance(DEFAULT_IDEA_MCP_GUIDANCE_EN)
        assertEquals(null, settings.state.ideaMcpGuidanceOverride)
        assertEquals(defaultIdeaMcpGuidanceForLanguage(settings.language), settings.ideaMcpGuidance)
    }

    @Test
    fun `IDEA MCP 引导写入中文默认值同样清除自定义`() {
        val settings = ImuxSettings()

        settings.setIdeaMcpGuidance("custom")
        settings.setIdeaMcpGuidance(DEFAULT_IDEA_MCP_GUIDANCE_ZH)

        assertEquals(null, settings.state.ideaMcpGuidanceOverride)
    }

    // region 各语言下 ideaMcpGuidance 返回正确默认值

    @Test
    fun `简中语言下 IDEA MCP 引导返回中文默认词`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.SIMPLIFIED_CHINESE)

        assertEquals(DEFAULT_IDEA_MCP_GUIDANCE_ZH, settings.ideaMcpGuidance)
        assertTrue(settings.ideaMcpGuidance.contains("必须使用 IDEA MCP"))
    }

    @Test
    fun `繁中语言下 IDEA MCP 引导返回中文默认词`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.TRADITIONAL_CHINESE)

        assertEquals(DEFAULT_IDEA_MCP_GUIDANCE_ZH, settings.ideaMcpGuidance)
    }

    @Test
    fun `英文语言下 IDEA MCP 引导返回英文默认词`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.ENGLISH)

        assertEquals(DEFAULT_IDEA_MCP_GUIDANCE_EN, settings.ideaMcpGuidance)
        assertTrue(settings.ideaMcpGuidance.contains("MUST use IDEA MCP"))
    }

    @Test
    fun `日语下 IDEA MCP 引导返回英文默认词`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.JAPANESE)
        assertEquals(DEFAULT_IDEA_MCP_GUIDANCE_EN, settings.ideaMcpGuidance)
    }

    @Test
    fun `韩语下 IDEA MCP 引导返回英文默认词`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.KOREAN)
        assertEquals(DEFAULT_IDEA_MCP_GUIDANCE_EN, settings.ideaMcpGuidance)
    }

    @Test
    fun `德语下 IDEA MCP 引导返回英文默认词`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.GERMAN)
        assertEquals(DEFAULT_IDEA_MCP_GUIDANCE_EN, settings.ideaMcpGuidance)
    }

    @Test
    fun `法语下 IDEA MCP 引导返回英文默认词`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.FRENCH)
        assertEquals(DEFAULT_IDEA_MCP_GUIDANCE_EN, settings.ideaMcpGuidance)
    }

    @Test
    fun `西班牙语下 IDEA MCP 引导返回英文默认词`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.SPANISH)
        assertEquals(DEFAULT_IDEA_MCP_GUIDANCE_EN, settings.ideaMcpGuidance)
    }

    @Test
    fun `葡萄牙语下 IDEA MCP 引导返回英文默认词`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.PORTUGUESE_BRAZIL)
        assertEquals(DEFAULT_IDEA_MCP_GUIDANCE_EN, settings.ideaMcpGuidance)
    }

    @Test
    fun `俄语下 IDEA MCP 引导返回英文默认词`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.RUSSIAN)
        assertEquals(DEFAULT_IDEA_MCP_GUIDANCE_EN, settings.ideaMcpGuidance)
    }

    // endregion

    @Test
    fun `IDEA MCP 有自定义引导时切换语言不影响返回值`() {
        val settings = ImuxSettings()
        settings.setIdeaMcpGuidance("my custom guidance")
        settings.setLanguage(PluginLanguage.SIMPLIFIED_CHINESE)

        assertEquals("my custom guidance", settings.ideaMcpGuidance)
    }

    @Test
    fun `语言切换只替换默认引导词不覆盖自定义`() {
        assertEquals("my custom", guidanceAfterLanguageSwitch("my custom", PluginLanguage.SIMPLIFIED_CHINESE))
        assertEquals("my custom", guidanceAfterLanguageSwitch("my custom", PluginLanguage.ENGLISH))
    }

    @Test
    fun `语言切换将英文默认引导词替换为中文`() {
        assertEquals(
            DEFAULT_IDEA_MCP_GUIDANCE_ZH,
            guidanceAfterLanguageSwitch(DEFAULT_IDEA_MCP_GUIDANCE_EN, PluginLanguage.SIMPLIFIED_CHINESE),
        )
    }

    @Test
    fun `语言切换将中文默认引导词替换为英文`() {
        assertEquals(
            DEFAULT_IDEA_MCP_GUIDANCE_EN,
            guidanceAfterLanguageSwitch(DEFAULT_IDEA_MCP_GUIDANCE_ZH, PluginLanguage.ENGLISH),
        )
    }

    @Test
    fun `恢复默认按当前语言生效`() {
        assertEquals(DEFAULT_IDEA_MCP_GUIDANCE_ZH, defaultIdeaMcpGuidanceForLanguage(PluginLanguage.SIMPLIFIED_CHINESE))
        assertEquals(DEFAULT_IDEA_MCP_GUIDANCE_ZH, defaultIdeaMcpGuidanceForLanguage(PluginLanguage.TRADITIONAL_CHINESE))
        assertEquals(DEFAULT_IDEA_MCP_GUIDANCE_EN, defaultIdeaMcpGuidanceForLanguage(PluginLanguage.ENGLISH))
        assertEquals(DEFAULT_IDEA_MCP_GUIDANCE_EN, defaultIdeaMcpGuidanceForLanguage(PluginLanguage.JAPANESE))
        assertEquals(DEFAULT_IDEA_MCP_GUIDANCE_EN, defaultIdeaMcpGuidanceForLanguage(PluginLanguage.KOREAN))
        assertEquals(DEFAULT_IDEA_MCP_GUIDANCE_EN, defaultIdeaMcpGuidanceForLanguage(PluginLanguage.GERMAN))
        assertEquals(DEFAULT_IDEA_MCP_GUIDANCE_EN, defaultIdeaMcpGuidanceForLanguage(PluginLanguage.FRENCH))
        assertEquals(DEFAULT_IDEA_MCP_GUIDANCE_EN, defaultIdeaMcpGuidanceForLanguage(PluginLanguage.SPANISH))
        assertEquals(DEFAULT_IDEA_MCP_GUIDANCE_EN, defaultIdeaMcpGuidanceForLanguage(PluginLanguage.PORTUGUESE_BRAZIL))
        assertEquals(DEFAULT_IDEA_MCP_GUIDANCE_EN, defaultIdeaMcpGuidanceForLanguage(PluginLanguage.RUSSIAN))
    }

    @Test
    fun `isDefaultIdeaMcpGuidance 识别两种默认值`() {
        assertTrue(isDefaultIdeaMcpGuidance(DEFAULT_IDEA_MCP_GUIDANCE_EN))
        assertTrue(isDefaultIdeaMcpGuidance(DEFAULT_IDEA_MCP_GUIDANCE_ZH))
        assertFalse(isDefaultIdeaMcpGuidance("custom"))
    }

    // region pendingLanguage / effectiveLanguage

    @Test
    fun `effectiveLanguage 无预览时返回持久化语言`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.ENGLISH)

        assertEquals(null, settings.pendingLanguage)
        assertEquals(PluginLanguage.ENGLISH, settings.effectiveLanguage)
    }

    @Test
    fun `effectiveLanguage 有预览时返回预览语言`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.ENGLISH)
        settings.setPreviewLanguage(PluginLanguage.SIMPLIFIED_CHINESE)

        assertEquals(PluginLanguage.SIMPLIFIED_CHINESE, settings.effectiveLanguage)
        assertEquals(PluginLanguage.ENGLISH, settings.language)
    }

    @Test
    fun `clearPreviewLanguage 清除后回到持久化语言`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.ENGLISH)
        settings.setPreviewLanguage(PluginLanguage.SIMPLIFIED_CHINESE)

        settings.clearPreviewLanguage()

        assertEquals(null, settings.pendingLanguage)
        assertEquals(PluginLanguage.ENGLISH, settings.effectiveLanguage)
    }

    @Test
    fun `未 Apply 切到中文后 MCP 子页面应看到中文引导词`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.ENGLISH)
        settings.setPreviewLanguage(PluginLanguage.SIMPLIFIED_CHINESE)

        val displayedGuidance = settings.state.ideaMcpGuidanceOverride
            ?: defaultIdeaMcpGuidanceForLanguage(settings.effectiveLanguage)

        assertEquals(DEFAULT_IDEA_MCP_GUIDANCE_ZH, displayedGuidance)
        assertEquals(DEFAULT_IDEA_MCP_GUIDANCE_EN, settings.ideaMcpGuidance)
    }

    @Test
    fun `setPreviewLanguage 变化时触发语言监听`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.ENGLISH)
        var fired = false
        val disposable = com.intellij.openapi.util.Disposer.newDisposable()
        try {
            settings.addLanguageListener(disposable) { fired = true }
            settings.setPreviewLanguage(PluginLanguage.SIMPLIFIED_CHINESE)
            assertTrue(fired)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(disposable)
        }
    }

    @Test
    fun `setPreviewLanguage 与当前 effectiveLanguage 相同时不触发监听`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.ENGLISH)
        settings.setPreviewLanguage(PluginLanguage.ENGLISH)
        var fired = false
        val disposable = com.intellij.openapi.util.Disposer.newDisposable()
        try {
            settings.addLanguageListener(disposable) { fired = true }
            settings.setPreviewLanguage(PluginLanguage.ENGLISH)
            assertFalse(fired)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(disposable)
        }
    }

    @Test
    fun `clearPreviewLanguage 变化时触发语言监听`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.ENGLISH)
        settings.setPreviewLanguage(PluginLanguage.SIMPLIFIED_CHINESE)
        var fired = false
        val disposable = com.intellij.openapi.util.Disposer.newDisposable()
        try {
            settings.addLanguageListener(disposable) { fired = true }
            settings.clearPreviewLanguage()
            assertTrue(fired)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(disposable)
        }
    }

    @Test
    fun `clearPreviewLanguage 无变化时不触发监听`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.ENGLISH)
        var fired = false
        val disposable = com.intellij.openapi.util.Disposer.newDisposable()
        try {
            settings.addLanguageListener(disposable) { fired = true }
            settings.clearPreviewLanguage()
            assertFalse(fired)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(disposable)
        }
    }

    @Test
    fun `语言预览逻辑：预览中文后 effectiveLanguage 产出中文默认词 清除后恢复英文`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.ENGLISH)

        settings.setPreviewLanguage(PluginLanguage.SIMPLIFIED_CHINESE)
        assertEquals(DEFAULT_IDEA_MCP_GUIDANCE_ZH, defaultIdeaMcpGuidanceForLanguage(settings.effectiveLanguage))

        settings.clearPreviewLanguage()
        assertEquals(DEFAULT_IDEA_MCP_GUIDANCE_EN, defaultIdeaMcpGuidanceForLanguage(settings.effectiveLanguage))
    }

    // endregion

    @Test
    fun `all agents are enabled by default`() {
        assertEquals(AgentType.entries, ImuxSettings().enabledAgentTypes)
    }

    @Test
    fun `applying unchanged detected language keeps automatic mode`() {
        val settings = ImuxSettings()

        applyLanguageSelection(settings, settings.language)

        assertEquals(null, settings.state.languageId)
    }

    @Test
    fun `applying a different language persists explicit selection`() {
        val settings = ImuxSettings()
        val selected = PluginLanguage.entries.first { it != settings.language }

        applyLanguageSelection(settings, selected)

        assertEquals(selected.id, settings.state.languageId)
    }

    @Test
    fun `existing explicit language remains explicit on apply`() {
        val settings = ImuxSettings()
        val selected = settings.language
        settings.state.languageId = selected.id

        applyLanguageSelection(settings, selected)

        assertEquals(selected.id, settings.state.languageId)
    }

    @Test
    fun `enabled agents can be changed without changing their order`() {
        val settings = ImuxSettings()

        settings.setEnabledAgentTypes(setOf(AgentType.PI, AgentType.CLAUDE))

        assertEquals(listOf(AgentType.CLAUDE, AgentType.PI), settings.enabledAgentTypes)
    }

    @Test
    fun `at least one agent must stay enabled`() {
        val settings = ImuxSettings()

        assertThrows(IllegalArgumentException::class.java) {
            settings.setEnabledAgentTypes(emptySet())
        }
        assertEquals(AgentType.entries, settings.enabledAgentTypes)
    }
}
