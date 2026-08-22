package com.github.izerui.imux.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CodexHookOverrideTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `TOML 基本字符串转义反斜杠与双引号`() {
        // Windows 路径全是反斜杠；TOML 基本字符串里 \ 是转义引导符，不转义会解析失败
        assertEquals("\"C:\\\\Users\\\\me\\\\r.ps1\"", tomlBasicString("C:\\Users\\me\\r.ps1"))
        assertEquals("\"say \\\"hi\\\"\"", tomlBasicString("say \"hi\""))
        assertEquals("\"plain\"", tomlBasicString("plain"))
    }

    /**
     * 两个 `replace` 的顺序不能换：先转义反斜杠再转义引号。
     *
     * 换了顺序，第二步给引号添的那个反斜杠会被第一步再转义一次，`"` 变成 `\\"`——
     * TOML 解析出来是一个反斜杠加一个字符串结束符，整条 command 被截断。
     * 这条用同时含反斜杠与引号的值把顺序钉死。
     */
    @Test
    fun `先转义反斜杠再转义引号`() {
        assertEquals("\"a\\\\b\\\"c\"", tomlBasicString("a\\b\"c"))
    }

    @Test
    fun `hook 覆盖实参是 codex 认识的 schema`() {
        // schema 由 codex debug models -c 逐层用错类型试出来：
        // hooks.SessionStart 是 MatcherGroup 序列，MatcherGroup.hooks 是
        // HookHandlerConfig 序列，type="command" 时 command 必填且为字符串
        val arg = codexHookOverrideArg(ShellDialect.POWERSHELL, "C:\\p\\r.ps1")
        assertEquals(
            "'hooks.SessionStart=[{hooks=[{type=\"command\"," +
                "command=\"powershell -NoLogo -NoProfile -File \\\"C:\\\\p\\\\r.ps1\\\"\"}]}]'",
            arg,
        )
    }

    /**
     * 转义只施加一次，且必须在最外层。
     *
     * 把内层写成 `tomlBasicString(scriptPath)` 会让路径被转义两次：`C:\p` 变成
     * `C:\\\\p` 而不是 `C:\\p`。**而且写错了 codex 不报错**——实测把两种写法都喂给
     * `codex debug models -c`，codex 全部接受，双重转义在 TOML 语法上完全合法，
     * 只是解析出来的路径不存在。症状是 hook 静默地永不触发，与「这个功能没做」
     * 长得一模一样。这条是这一层唯一的守卫。
     */
    @Test
    fun `路径只被转义一次`() {
        val arg = codexHookOverrideArg(ShellDialect.POWERSHELL, "C:\\p\\r.ps1")

        assertTrue("反斜杠必须恰好翻一倍：$arg", arg.contains("C:\\\\p\\\\r.ps1"))
        assertFalse("四个反斜杠说明转义施加了两次：$arg", arg.contains("C:\\\\\\\\p"))
    }

    @Test
    fun `实参外层套的是方言引号`() {
        // 这是全项目嵌套引号最深的一处：TOML 字符串里嵌着脚本路径，外面再套一层 shell 引号
        val arg = codexHookOverrideArg(ShellDialect.POWERSHELL, "C:\\o'brien\\r.ps1")
        // PowerShell 单引号内的单引号写成两个
        assertEquals(true, arg.startsWith("'"))
        assertEquals(true, arg.endsWith("'"))
        assertEquals(true, arg.contains("o''brien"))
    }

    @Test
    fun `在插件目录下定位上报脚本`() {
        val scripts = File(tmp.root, "scripts").apply { mkdirs() }
        val script = File(scripts, "codex-imux-reporter.ps1").apply { writeText("# x") }

        assertEquals(script.toPath(), codexReporterScriptIn(tmp.root.toPath()))
    }

    @Test
    fun `从打包 jar 位置定位上报脚本`() {
        val pluginRoot = File(tmp.root, "imux")
        val lib = File(pluginRoot, "lib").apply { mkdirs() }
        val jar = File(lib, "imux.jar").apply { writeText("") }
        val scripts = File(pluginRoot, "scripts").apply { mkdirs() }
        val script = File(scripts, "codex-imux-reporter.ps1").apply { writeText("# x") }

        assertEquals(script.toPath(), locateCodexReporterScript(null, jar.toPath()))
    }

    /**
     * 安装不完整时必须退回「不加 -c」。
     *
     * 理由与 pi 的 `-e` 完全相同：拼一个加载不了的路径会让 CLI 启动失败，
     * 那是整个会话起不来；而少了上报只是标签不自动跟随。
     */
    @Test
    fun `脚本缺失时返回 null`() {
        assertNull(codexReporterScriptIn(tmp.root.toPath()))
        assertNull(codexReporterScriptIn(null))
        assertNull(locateCodexReporterScript(null, null))
    }

    /** 源码里的脚本必须真实存在，否则打包出来的插件缺文件，功能静默失效。 */
    @Test
    fun `仓库里带着待打包的脚本`() {
        assertTrue(File("src/main/scripts/codex-imux-reporter.ps1").exists())
    }

    /**
     * 上报脚本的行为约束只能做源码断言：本机与 CI 上都没有 PowerShell，
     * 而这几条写错了都是**静默**失效——没有任何一条会让人看见报错。
     */
    @Test
    fun `上报脚本发出 handler 认得的报文`() {
        val script = File("src/main/scripts/codex-imux-reporter.ps1").readText()

        assertTrue(
            "tabId 只能来自 codex 进程继承下来的 IMUX_TAB",
            script.contains("\$env:IMUX_TAB"),
        )
        assertTrue("上报地址来自环境变量", script.contains("\$env:IMUX_REPORT_URL"))
        assertTrue("令牌来自环境变量", script.contains("\$env:IMUX_TOKEN"))
        assertTrue(
            "会话 id 取 hook 报文的 session_id",
            script.contains("session_id"),
        )
        assertTrue(
            "cwd 必须一起报上去：handler 靠它判断这条属于哪个项目，缺了会被整条丢弃",
            script.contains("cwd"),
        )
        assertTrue(
            "type 必须是 session_start：parsePiReport 认不出 type 就返回 null",
            script.contains("session_start"),
        )
        assertTrue(
            "令牌走 x-imux-token 头，与 handler 校验的字段名一致",
            script.contains("x-imux-token"),
        )
    }

    /**
     * 上报失败绝不能影响 codex 的会话启动。
     *
     * hook 的退出码非 0 时 codex 会在会话里显示报错；上报只负责标签自动跟随，
     * 不该让用户看见任何东西。
     */
    @Test
    fun `上报脚本任何分支都以 0 退出`() {
        val script = File("src/main/scripts/codex-imux-reporter.ps1").readText()

        assertTrue("必须有 try 兜住全部异常", script.contains("try {"))
        assertTrue("catch 里也要 exit 0", Regex("""catch\s*\{[^}]*exit 0""").containsMatchIn(script))
        assertFalse("不能有非零退出码", Regex("""exit\s+[1-9]""").containsMatchIn(script))
    }
}
