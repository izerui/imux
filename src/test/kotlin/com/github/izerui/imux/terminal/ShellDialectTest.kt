package com.github.izerui.imux.terminal

import com.github.izerui.imux.lsp.buildProbeScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellDialectTest {

    @Test
    fun `方言按 shell 二进制名判定而不按操作系统`() {
        assertEquals(ShellDialect.POSIX, dialectOf("/bin/zsh"))
        assertEquals(ShellDialect.POSIX, dialectOf("/bin/bash"))
        assertEquals(ShellDialect.POSIX, dialectOf("/usr/local/bin/fish"))
        assertEquals(ShellDialect.POWERSHELL, dialectOf("C:\\Program Files\\PowerShell\\7\\pwsh.exe"))
        assertEquals(
            ShellDialect.POWERSHELL,
            dialectOf("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"),
        )
    }

    @Test
    fun `Windows 上的 Git Bash 是 POSIX 方言`() {
        // 按操作系统判的话这里会错发 PowerShell 的引号规则，当场拼错命令行
        assertEquals(ShellDialect.POSIX, dialectOf("C:\\Program Files\\Git\\bin\\bash.exe"))
    }

    @Test
    fun `可执行文件名大小写与 exe 后缀都不影响判定`() {
        assertEquals(ShellDialect.POWERSHELL, dialectOf("C:\\X\\PowerShell.EXE"))
        assertEquals(ShellDialect.POWERSHELL, dialectOf("pwsh"))
    }

    @Test
    fun `cmd 不是受支持的方言按 POSIX 兜底`() {
        // resolveShell 不会把 cmd 交到这里（它会换成 powershell.exe），
        // 这条只钉住「不认识的名字有确定结果」，不表示支持 cmd
        assertEquals(ShellDialect.POSIX, dialectOf("C:\\Windows\\System32\\cmd.exe"))
    }

    @Test
    fun `POSIX 参数与现网完全一致`() {
        assertEquals(listOf("-l", "-i", "-c"), shellArgs(ShellDialect.POSIX))
    }

    @Test
    fun `PowerShell 不读 profile 但必须绕过执行策略`() {
        // Windows 的 PATH 在环境变量块里，IDE 直接继承，不需要 profile；
        // 而读 profile 会引入启动延迟与用户 profile 报错的风险
        assertEquals(
            listOf("-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command"),
            shellArgs(ShellDialect.POWERSHELL),
        )
    }

    /**
     * `-ExecutionPolicy Bypass` 缺不得——**这一条守的是「Windows 上会话能起」本身**。
     *
     * PowerShell 解析外部命令时 `.ps1` 优先于 `.cmd`，而 npm 全局安装用 `cmd-shim`
     * 同时铺 `name` / `name.cmd` / `name.ps1` 三份。Windows **客户端** SKU 的默认执行
     * 策略是 `Restricted`（「Prevents running of all script files」），于是 npm 装的
     * `claude` / `codex` 一开标签就是那条人尽皆知的红字：
     * `claude.ps1 cannot be loaded because running scripts is disabled on this system`。
     * LSP 页的 `npm install -g pyright`、`gem install ruby-lsp` 同样被挡。
     *
     * 它是 Process 作用域，只活在 `$Env:PSExecutionPolicyPreference` 里，
     * **不写任何配置文件**——完全在「别改 cli 的配置文件本身」这条约束内。
     * 同一段论证 [shellArgs] 的 KDoc 里写全了，这里不重复。
     */
    @Test
    fun `PowerShell 参数带上 Process 作用域的执行策略绕过`() {
        val args = shellArgs(ShellDialect.POWERSHELL)

        assertEquals(
            "少了它，npm 装的 CLI 在默认 Windows 上一个都起不来：$args",
            listOf("-ExecutionPolicy", "Bypass"),
            args.subList(args.indexOf("-ExecutionPolicy"), args.indexOf("-ExecutionPolicy") + 2),
        )
        assertTrue(
            "必须排在 -Command 之前：-Command 之后的都是要执行的脚本文本",
            args.indexOf("-ExecutionPolicy") < args.indexOf("-Command"),
        )
    }

    /**
     * **POSIX 一个字都不许加。** 执行策略是 Windows 独有的概念，往 `zsh -l -i -c`
     * 里塞任何一个新参数，macOS 上每一个终端标签与每一次二进制探测都会当场报错。
     */
    @Test
    fun `POSIX 参数不受执行策略改动影响`() {
        assertEquals(listOf("-l", "-i", "-c"), shellArgs(ShellDialect.POSIX))
        assertFalse(
            "POSIX 侧不得出现任何 PowerShell 概念",
            shellArgs(ShellDialect.POSIX).any { it.startsWith("-Execution") },
        )
    }

    // —— 命令链 ——

    /**
     * **POSIX 的链与改动前逐字节相同**：`joinToString(" && ")`。
     *
     * 这是 macOS 上正在工作的行为。换成 `;` 的话，`brew install --cask dotnet-sdk`
     * 失败之后 `dotnet tool install` 照样跑一遍、再失败一次，最后
     * `claude plugin install` 把一个指向不存在程序的配置写进 `~/.claude/settings.json`。
     */
    @Test
    fun `POSIX 命令链仍然是 and-and 串起来`() {
        assertEquals(
            "brew install --cask dotnet-sdk && dotnet tool install --global csharp-ls",
            commandChain(
                ShellDialect.POSIX,
                listOf("brew install --cask dotnet-sdk", "dotnet tool install --global csharp-ls"),
            ),
        )
        assertEquals(
            "a && b && c",
            commandChain(ShellDialect.POSIX, listOf("a", "b", "c")),
        )
    }

    /** 单条命令时两个方言都原样返回，不加任何检查——没有「下一步」可停。 */
    @Test
    fun `单条命令在两个方言下都不加检查`() {
        assertEquals("pi install npm:pi-lens", commandChain(ShellDialect.POSIX, listOf("pi install npm:pi-lens")))
        assertEquals(
            "pi install npm:pi-lens",
            commandChain(ShellDialect.POWERSHELL, listOf("pi install npm:pi-lens")),
        )
    }

    /** 空列表在两个方言下行为一致：空串（调用方据此判定「没有可跑的东西」）。 */
    @Test
    fun `空命令列表在两个方言下都是空串`() {
        assertEquals("", commandChain(ShellDialect.POSIX, emptyList()))
        assertEquals("", commandChain(ShellDialect.POWERSHELL, emptyList()))
    }

    /**
     * **PowerShell 不能用 `&&`。**
     *
     * `&&` / `||` 是 PowerShell **7.0** 才引入的 pipeline chain operator，而
     * [resolveShell] 在 Windows 上的兜底是 `powershell.exe`——随系统附带的 5.1。
     * 它见到 `&&` 直接报
     * `The token '&&' is not a valid statement separator in this version.`：
     * **整条链一个命令都不会跑**，而「二进制缺 + 配置缺」正是干净 Windows 上最常见的
     * 形态（`enablePlan` 必然产出两条以上命令）。用户点「启用」→ 终端开出来 →
     * 一片红字 → 什么都没装上。
     *
     * 用 `$LASTEXITCODE` 而不是 `$?`：5.1 里原生命令只要往 stderr 写东西就可能把 `$?`
     * 置为 false，而 `npm install` 恰恰会把进度写 stderr——那会在第一步**成功**时
     * 就掐断链，比不检查更糟。
     */
    @Test
    fun `PowerShell 命令链用 LASTEXITCODE 逐步检查而不是 and-and`() {
        val chain = commandChain(ShellDialect.POWERSHELL, listOf("npm install -g pyright", "claude plugin install x"))

        assertEquals(
            "npm install -g pyright; if (\$LASTEXITCODE -ne 0) { exit \$LASTEXITCODE }; " +
                "claude plugin install x",
            chain,
        )
        assertFalse("5.1 见到 && 直接报解析错误，一条命令都不会跑：$chain", chain.contains("&&"))
        assertFalse("\$? 会被写 stderr 的原生命令置假，用它会在第一步成功时就掐断链：$chain", chain.contains("\$?"))
    }

    /** 三条以上时检查逐段插入，**扁平不嵌套**——嵌套写法在 5.1 上极易写错括号配平。 */
    @Test
    fun `PowerShell 三条命令的链是扁平的`() {
        val chain = commandChain(ShellDialect.POWERSHELL, listOf("a", "b", "c"))

        assertEquals(
            "a; if (\$LASTEXITCODE -ne 0) { exit \$LASTEXITCODE }; " +
                "b; if (\$LASTEXITCODE -ne 0) { exit \$LASTEXITCODE }; c",
            chain,
        )
        assertEquals("检查次数必须是命令数减一", 2, Regex("""LASTEXITCODE -ne 0""").findAll(chain).count())
    }

    @Test
    fun `POSIX 引号与现网的 singleQuote 逐字节相同`() {
        listOf("plain", "with space", "it's", "a'b'c", "", "--flag=x").forEach { value ->
            assertEquals("'" + value.replace("'", "'\\''") + "'", quote(ShellDialect.POSIX, value))
        }
    }

    @Test
    fun `POSIX 引号把单引号闭合转义重开`() {
        assertEquals("'it'\\''s'", quote(ShellDialect.POSIX, "it's"))
    }

    @Test
    fun `PowerShell 引号把单引号写成两个`() {
        assertEquals("'it''s'", quote(ShellDialect.POWERSHELL, "it's"))
        assertEquals("'plain'", quote(ShellDialect.POWERSHELL, "plain"))
    }

    @Test
    fun `POSIX 探测脚本与现网逐字节相同`() {
        val binaries = listOf("gopls", "jdtls")
        assertEquals(buildProbeScript(binaries), probeScript(ShellDialect.POSIX, binaries))
    }

    @Test
    fun `PowerShell 探测脚本输出同样的名称制表符路径`() {
        val script = probeScript(ShellDialect.POWERSHELL, listOf("gopls"))
        // 与 POSIX 版共用 parseProbeOutput，格式必须一致
        assertEquals(
            "\$ErrorActionPreference='SilentlyContinue'; @('gopls') | ForEach-Object { " +
                "\$p = (Get-Command \$_ -ErrorAction SilentlyContinue | Select-Object -First 1).Source; \$_ + [char]9 + \$p }",
            script,
        )
    }

    @Test
    fun `探测脚本对二进制名同样施加方言引号`() {
        assertEquals(
            "\$ErrorActionPreference='SilentlyContinue'; @('a''b') | ForEach-Object { " +
                "\$p = (Get-Command \$_ -ErrorAction SilentlyContinue | Select-Object -First 1).Source; \$_ + [char]9 + \$p }",
            probeScript(ShellDialect.POWERSHELL, listOf("a'b")),
        )
    }
}
