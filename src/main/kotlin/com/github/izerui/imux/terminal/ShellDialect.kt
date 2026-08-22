package com.github.izerui.imux.terminal

import com.github.izerui.imux.lsp.buildProbeScript

// shell 方言：参数形状、引号规则、探测脚本写法。
//
// 一行平台 API 都不碰，理由与 lsp/LspRemedyRun.kt 相同：本项目未引入平台
// test-framework，UI 与平台相关逻辑只能做源码文本断言，而文本断言总能被
// 「保留被钉住的字面量、在别处改语义」绕开。搬进纯函数之后，三个平台的分支
// 都能被普通 JUnit 4 真调用——把 quote 的 PowerShell 分支改成 POSIX 规则，
// ShellDialectTest 当场变红，而调用点那一侧只剩一个调用点。

/**
 * 受支持的 shell 方言。
 *
 * **没有 CMD。** cmd 的引号与转义规则（`^` 转义、`%` 会被二次展开、引号内规则随
 * 上下文变化）写对的难度远高于收益，而写错的后果是把用户的初始 prompt 拼成一条
 * 别的命令。IntelliJ 在 Windows 上依次探测 `pwsh` &#8594; `powershell` &#8594; `cmd`，
 * PowerShell 是绝大多数机器上的实际默认值；解析到 cmd 时由 [resolveShell] 换成
 * `powershell.exe`，不会走到这里。
 */
internal enum class ShellDialect { POSIX, POWERSHELL }

/**
 * 按 shell 可执行文件名判方言，**不按操作系统判**。
 *
 * Windows 上 Git Bash 很常见，它是 POSIX；按 `SystemInfo.isWindows` 判会给
 * Git Bash 用户用 PowerShell 的引号规则，当场拼错命令行。
 *
 * 认不出来的名字一律当 POSIX：这是 macOS/Linux 上唯一正确的答案，而 Windows 上
 * 走到这里的只可能是用户配了个我们没见过的 shell——那时按 POSIX 处理至少与
 * 改动前一致，不会更坏。
 */
internal fun dialectOf(shellPath: String): ShellDialect {
    val name =
        shellPath
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .lowercase()
            .removeSuffix(".exe")
    return if (name == "pwsh" || name == "powershell") ShellDialect.POWERSHELL else ShellDialect.POSIX
}

/**
 * 交给 shell 执行一条命令所需的参数。
 *
 * **POSIX 的 `-l -i` 缺一不可**，这个坑项目里踩过两次：
 * - `-l` 读 profile。从 Dock/Finder 启动的 IDE 只有系统默认 PATH
 *   （`/usr/bin:/bin:/usr/sbin:/sbin`），而 CLI 与 `brew`、`go`、`npm` 都不在里面
 * - `-i` 读 rc，配成 **alias** 的工具才存在（本机的 `claude` 就是个 alias，
 *   还带着参数，丢了它行为就变）
 *
 * **PowerShell 反而要 `-NoProfile`。** 上面那套存在的唯一理由是 macOS/Linux 的
 * GUI 程序拿不到用户 shell 的 PATH；Windows 的 PATH 在环境变量块里，IDE 直接继承，
 * 不需要读 profile，而读 profile 会引入几百毫秒到数秒的启动延迟与用户 profile
 * 报错的风险。**这不是漏了，是有意的。**
 *
 * 已知取舍：Windows 上把 CLI 配成 PowerShell 函数或别名的用户拿不到它。触发面窄——
 * Windows 上 npm 装的 CLI 是 PATH 里的 `.cmd` shim，不是别名。
 */
internal fun shellArgs(dialect: ShellDialect): List<String> =
    when (dialect) {
        ShellDialect.POSIX -> listOf("-l", "-i", "-c")
        ShellDialect.POWERSHELL -> listOf("-NoLogo", "-NoProfile", "-Command")
    }

/**
 * 包成该方言的字面量字符串。
 *
 * 凡是拼进命令行的东西一律当作不可信：会话 id 来自文件名，初始 prompt 来自用户输入。
 *
 * 两种方言都用单引号（内部一切都是字面量），只是转义单引号自身的写法不同：
 * POSIX 要闭合-转义-重开（`'\''`），PowerShell 写两个单引号（`''`）。
 *
 * **POSIX 分支必须与改动前的 `singleQuote` 实现逐字节相同**（`'` + 把 `'` 换成
 * `'\''` + `'`）——那是 macOS 上正在工作的行为，由 `ShellDialectTest` 用字面量钉住。
 */
internal fun quote(
    dialect: ShellDialect,
    value: String,
): String =
    when (dialect) {
        ShellDialect.POSIX -> "'" + value.replace("'", "'\\''") + "'"
        ShellDialect.POWERSHELL -> "'" + value.replace("'", "''") + "'"
    }

/**
 * 一次问完一批二进制在不在 PATH 里的脚本。
 *
 * 两种方言的**输出格式必须一致**（`名称<TAB>路径`，查不到时制表符后为空），
 * 因为上游共用同一个 `parseProbeOutput`。不能靠「有没有这一行」判断：登录 shell
 * 会往 stdout 混入 profile 的欢迎语。
 *
 * POSIX 分支直接复用 [buildProbeScript]，一个字节不改。
 */
internal fun probeScript(
    dialect: ShellDialect,
    binaries: List<String>,
): String =
    when (dialect) {
        ShellDialect.POSIX -> buildProbeScript(binaries)
        ShellDialect.POWERSHELL -> {
            val list = binaries.joinToString(",") { quote(ShellDialect.POWERSHELL, it) }
            "\$ErrorActionPreference='SilentlyContinue'; @($list) | ForEach-Object { " +
                "\$p = (Get-Command \$_ -ErrorAction SilentlyContinue | Select-Object -First 1).Source; \"\$_`t\$p\" }"
        }
    }
