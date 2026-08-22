package com.github.izerui.imux.terminal

import com.github.izerui.imux.lsp.buildProbeScript
import org.junit.Assert.assertEquals
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
    fun `PowerShell 不读 profile`() {
        // Windows 的 PATH 在环境变量块里，IDE 直接继承，不需要 profile；
        // 而读 profile 会引入启动延迟与用户 profile 报错的风险
        assertEquals(listOf("-NoLogo", "-NoProfile", "-Command"), shellArgs(ShellDialect.POWERSHELL))
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
                "\$p = (Get-Command \$_ -ErrorAction SilentlyContinue | Select-Object -First 1).Source; \"\$_`t\$p\" }",
            script,
        )
    }

    @Test
    fun `探测脚本对二进制名同样施加方言引号`() {
        assertEquals(
            "\$ErrorActionPreference='SilentlyContinue'; @('a''b') | ForEach-Object { " +
                "\$p = (Get-Command \$_ -ErrorAction SilentlyContinue | Select-Object -First 1).Source; \"\$_`t\$p\" }",
            probeScript(ShellDialect.POWERSHELL, listOf("a'b")),
        )
    }
}
