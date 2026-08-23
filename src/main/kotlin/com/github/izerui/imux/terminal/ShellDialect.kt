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
 *
 * **`-ExecutionPolicy Bypass` 不是可选的，理由与 [codexHookOverrideArg] 那段
 * KDoc 逐条相同**（Windows 客户端 SKU 的默认执行策略是 `Restricted`，
 * 而它是 Process 作用域、不写任何配置文件，完全在「别改 cli 的配置文件本身」这条约束内），
 * 那里不再重复。这里补的是**它在这一层为什么同样必需**：
 *
 * PowerShell 解析外部命令时 `.ps1` 优先于 `.cmd`，而 npm 全局安装用 `cmd-shim`
 * **同时铺** `name`、`name.cmd`、`name.ps1` 三份（npm 自带的 `npm.ps1` / `npx.ps1`
 * 就是这么来的）。`claude` 与 `codex` 在 Windows 上正是 npm 装的，
 * `npm install -g pyright`、`gem install ruby-lsp` 这类启用命令同理。少了它，
 * 干净的 Windows 上得到的是那条人尽皆知的
 * `xxx.ps1 cannot be loaded because running scripts is disabled on this system`
 * ——**「Windows 上会话能起」这条设计承诺当场不成立**，而 LSP 页的每一个「启用」
 * 按钮点下去也都是同一句红字。
 *
 * 探测那一路不受影响：`-Command` 收的是内联脚本，不是脚本**文件**，不受执行策略管辖。
 *
 * **边界**：它盖不过组策略（`MachinePolicy` / `UserPolicy` 的优先级高于 Process）。
 * 企业环境里被 GPO 锁死的机器上仍然跑不起来，那属于「认不出就跳过」。
 */
internal fun shellArgs(dialect: ShellDialect): List<String> =
    when (dialect) {
        ShellDialect.POSIX -> listOf("-l", "-i", "-c")
        ShellDialect.POWERSHELL -> listOf("-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command")
    }

/**
 * 把一串命令拼成「**哪一步失败就停在哪**」的一行，按方言选写法。
 *
 * **POSIX 分支必须与改动前的 `commands.joinToString(" && ")` 逐字节相同**——那是
 * macOS 上正在工作的行为，由 `ShellDialectTest` 用字面量钉住。
 *
 * **PowerShell 不能用 `&&`。** `&&` / `||` 是 PowerShell **7.0** 才引入的 pipeline
 * chain operator，而 Windows 自带的是 5.1（[resolveShell] 的兜底正是 `powershell.exe`）。
 * 5.1 见到它直接报解析错误
 * `The token '&&' is not a valid statement separator in this version.`：
 * 整条链一个命令都不会跑，而「二进制缺 + 配置缺」正是干净 Windows 上最常见的形态。
 * 改用显式的 `$LASTEXITCODE` 检查，扁平串接、不嵌套。
 *
 * **为什么是 `$LASTEXITCODE` 而不是 `$?`。** 5.1 里原生命令只要往 stderr 写东西就可能
 * 把 `$?` 置为 false，而 `npm install` 恰恰会把进度写 stderr——用 `$?` 会在第一步
 * **成功**时就把链掐断，比不检查更糟。
 *
 * **已知局限：命令根本不存在时（CommandNotFound）`$LASTEXITCODE` 不会被更新**，
 * 链不会停在那一步。后果是用户看到一条错误、后面的命令继续跑（多半也跟着失败），
 * 而这些在终端里全都看得见。这比 `&&`（100% 解析失败、一条都不跑）严格更好。
 * **刻意不引入 `$ErrorActionPreference='Stop'` 去补它**：它在 5.1 上对原生命令 stderr
 * 的行为我们没有条件验证，拿一个不确定性换另一个不划算。
 *
 * 只有一条命令时两个方言都直接返回它本身，不加任何检查（`joinToString` 天然如此）；
 * 空列表两个方言都是空串。
 */
internal fun commandChain(
    dialect: ShellDialect,
    commands: List<String>,
): String =
    when (dialect) {
        ShellDialect.POSIX -> commands.joinToString(" && ")
        ShellDialect.POWERSHELL -> commands.joinToString("; $POWERSHELL_EXIT_ON_ERROR; ")
    }

/** 上一条命令非 0 就把整条链的退出码原样抛出去；[commandChain] 用它顶替 `&&`。 */
private const val POWERSHELL_EXIT_ON_ERROR = "if (\$LASTEXITCODE -ne 0) { exit \$LASTEXITCODE }"

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
 *
 * **PowerShell 分支不用字符串插值（`"$_`t$p"`）拼制表符，用 `[char]9` 拼接。**
 * `BinaryProbe` 的 `ShellBinaryProbe` 是 `src/main` 里唯一的裸 `ProcessBuilder`，
 * Java 在 Windows 上给含空格的参数整体裹一层双引号，内层双引号是否被转义取决于
 * JDK 走的是 `VERIFICATION_LEGACY` 还是 `VERIFICATION_WIN32_SAFE`。走前者时
 * `CommandLineToArgvW` 会把内层引号吃掉，制表符不出现，`parseProbeOutput` 靠
 * 制表符筛行，整片丢弃——症状与「这个功能没做」完全一样，且不报错。
 * `[char]9` 避免了双引号，从根上消除这类风险。
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
                "\$p = (Get-Command \$_ -ErrorAction SilentlyContinue | Select-Object -First 1).Source; \$_ + [char]9 + \$p }"
        }
    }

/**
 * 让 shell 把自己的 pid 写进 [pidFile] 的那一小段命令；POSIX 方言返回 null。
 *
 * **只有 Windows 需要它。** macOS 与 Linux 靠读目标进程的环境变量认领
 * （`ps eww` / `/proc/&#42;/environ`），启动命令一个字都不加。Windows 上
 * **读不到别的进程的环境变量**——环境块在目标进程的 PEB 里，要 `ReadProcessMemory`
 * 加调试权限，`Get-Process` 不给，JDK 也不给（`ProcessHandle.Info.arguments()`
 * 在 Windows 上恒为空）。于是换一条通道：启动命令本来就由 imux 全权拼接，
 * 让 shell 把自己的 pid 留下来，之后从 CLI 进程沿父链上溯即可对号入座。
 *
 * 写进的是 `PathManager.getSystemPath()` 下 imux 自己的目录，**不碰用户的任何配置**。
 *
 * `-Encoding ascii` 是必须的：PowerShell 5 的 `Set-Content` 默认写 UTF-16LE 带 BOM，
 * 读回来 `"100".toLong()` 会失败，而失败是静默的（该标签认不出来）。
 *
 * **已知的洞：Windows 上用 POSIX shell（Git Bash、MSYS2）的人拿不到漂移探测。**
 * [dialectOf] 按可执行文件名判方言，Git Bash 判成 POSIX，于是这里返回 null、不写 pid 文件；
 * 而 `ProcessProbes.readTabId` 仍按 `SystemInfo.isWindows` 走 pid 文件那条分支，
 * 找一个永远不存在的文件——静默地认不出任何标签。
 *
 * **不要试图用 `echo $$` 把这个洞补上，那是错的。** Git Bash 的 `$$` 是 MSYS 自己的
 * pid 空间，与 `ProcessHandle` 看到的 Windows pid 对不上号，写进去只会让父链上溯
 * 撞到一个毫不相干的进程——那正是「认错比不迁移更糟」的那一类错。真要补，
 * 得先解决 MSYS pid 到 Windows pid 的映射（`ps -W`、`/proc/<pid>/winpid`），
 * 不是加一段 shell 命令的事。
 *
 * 保持现状的理由：改动前 Windows 上什么都不工作，这个洞不是回退，只是没覆盖到。
 */
internal fun pidFileRecordCommand(
    dialect: ShellDialect,
    pidFile: String,
): String? =
    when (dialect) {
        ShellDialect.POSIX -> null
        ShellDialect.POWERSHELL ->
            "\$PID | Set-Content -LiteralPath ${quote(dialect, pidFile)} -Encoding ascii"
    }
