# imux 三平台支持设计

让 imux 在 macOS / Linux / Windows 上都能用，三个 CLI（claude / codex / pi）一个不少。

**统领约束（用户原话）：「都支持，但是不要产生 bug，导致其他原有支持的平台有问题」。**
macOS 是唯一被真机验证过的平台，也是仓库所有者日常使用的平台。本次改动对 macOS
的要求不是「不出错」，而是**返回值逐字节不变**。

## 现状

imux 有五层，跨平台成色各不相同：

| 层 | 干什么 | Linux | Windows |
| --- | --- | --- | --- |
| 1 会话列表 | 读 `~/.claude` `~/.codex` `~/.pi` 下的会话文件 | 已可用 | 已可用 |
| 2 会话启动 | `$SHELL -l -i -c "claude"` | 大概可用，未验证 | 不可用 |
| 3 LSP 体检页 | 探测二进制 + 一键启用 | 部分 | 不可用 |
| 4 会话漂移探测 | 认出用户在终端里敲了 `/clear`、`/new` | 需换实现 | 不可用 |
| 5 完成通知 | 气泡 + 系统通知 | 已可用 | 已可用 |

第 1、5 层不在本次范围内——它们只用 `java.nio.file` 与平台通知 API，本来就跨平台
（`TurnNotifier` 里那处 `SystemInfo.isMac` 守的是 macOS 独有的 Dock 角标，其余路径通用）。

根因集中在一个函数：

```kotlin
// terminal/AgentCommand.kt:132
internal fun resolveShell(shellEnv: String?): String = shellEnv?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
```

Windows 上取不到 `SHELL`，退回 `/bin/zsh`，四个调用点全部当场 `Cannot run program /bin/zsh`：

| 调用点 | 消费者 | Windows 上的表现 |
| --- | --- | --- |
| `terminal/TerminalHost.kt:570` `newCommand` | 终端标签 | 会话标签起不来 |
| `terminal/TerminalHost.kt:582` `resumeCommand` | 终端标签 | 同上 |
| `lsp/BinaryProbe.kt:88` | `ProcessBuilder` | 探测失败，18 门语言全落 `UNKNOWN` |
| `settings/ImuxLspConfigurable.kt:712` 启用按钮 | 终端标签 | 已被 `canRun` 的 `hasPosixShell` 挡下 |

第 4 层另有独立的根因，见「组件五」。

## 统领原则：平台判断一律注入

**所有平台分支必须以参数注入，不得在纯函数里直读 `SystemInfo`。**

这不是洁癖，是本项目唯一可行的验证手段。仓库**未**引入平台 test-framework
（`build.gradle.kts` 记了原因：需从 JetBrains 仓库下载，本机网络不可达），测试只有
JUnit 4。一旦在纯函数里读 `SystemInfo`，这个函数就只能在开发者那台 macOS 上被测到
一条路径，而本次改动最要紧的分支恰恰是「不在 macOS 上会怎样」。

`lsp/LspRemedyRun.kt` 已经是这个形状（`canRun(remedy, isMac, hasPosixShell)`），
它的文件头注释把理由写清楚了。本次新增的每一个平台相关函数都照抄这个做法。

推论，也是本设计的验收线：

- **三个平台的分支都能在 macOS 上被普通 JUnit 真调用断言**
- **macOS 分支的返回值用 `assertEquals` 逐字节钉死**——任何人改动它当场变红
- 平台判断在整个 `src/main` 里只允许出现在**一处**：把 `SystemInfo` 读成参数的那个
  调用点。`ImuxLspUiSourceTest` 已有「壳里不得有第二处平台判断」的断言，本次扩展到
  新增的调用点

---

## 组件一：`ShellDialect`

新增 `terminal/ShellDialect.kt`。一个 shell 方言 = 三件事：参数形状、引号规则、
「查一个命令在不在 PATH 里」的写法。

```kotlin
internal enum class ShellDialect { POSIX, POWERSHELL }

/** 按 shell 可执行文件名判方言，**不按操作系统判**。 */
internal fun dialectOf(shellPath: String): ShellDialect
```

**为什么按二进制名判而不是按 `SystemInfo.isWindows` 判。** Windows 上 Git Bash
很常见，它是 POSIX；反过来判会给 Git Bash 用户发 PowerShell 的引号规则，当场错。
判据是路径末段（去掉 `.exe`、大小写不敏感）：

| 末段 | 方言 |
| --- | --- |
| `pwsh`、`powershell` | POWERSHELL |
| 其余一切（`zsh`/`bash`/`fish`/`sh`/`dash`/…） | POSIX |

**为什么没有 CMD 方言。** cmd 的引号与转义规则（`^` 转义、`%` 会被二次展开、
引号内规则随上下文变化）写对的难度远高于收益，而写错的后果是把用户的初始 prompt
拼成一条别的命令。IntelliJ 在 Windows 上的终端默认依次探测 `pwsh` → `powershell`
→ `cmd`，PowerShell 是绝大多数机器上的实际默认值。因此：**解析到 cmd 时不使用它，
改用 `powershell.exe`**（见组件二）。这是本设计里唯一一处「不听用户配置」，理由写进
KDoc：宁可换一个我们能正确转义的 shell，也不能拼出一条转义错误的命令行。

### 三个方言函数

```kotlin
internal fun shellArgs(dialect: ShellDialect): List<String>
internal fun quote(dialect: ShellDialect, value: String): String
internal fun probeScript(dialect: ShellDialect, binaries: List<String>): String
```

| | POSIX | POWERSHELL |
| --- | --- | --- |
| `shellArgs` | `["-l", "-i", "-c"]` | `["-NoLogo", "-NoProfile", "-Command"]` |
| `quote` | `'…'`，内部 `'` → `'\''` | `'…'`，内部 `'` → `''` |

**POSIX 的 `shellArgs` 与 `quote` 必须与现有实现逐字节相同**——`quote(POSIX, x)`
就是现在的 `singleQuote(x)`，`shellArgs(POSIX)` 就是现在硬编在三处的
`"-l", "-i", "-c"`。现有的 `singleQuote` 保留为 `quote(POSIX, …)` 的别名，
不改签名、不改行为（`lsp/CodexLspProbe.kt` 与 `terminal/AgentCommand.kt` 共 6 处调用
它，且 `CodexLspProbe` 生成的是给用户看的 `codex mcp add` 命令文本，与 shell 无关）。

**PowerShell 为什么是 `-NoProfile` 而 POSIX 是 `-l -i`。** `-l -i` 存在的唯一理由是
macOS/Linux 的 GUI 程序拿不到用户 shell 的 PATH（从 Dock 启动的 IDE 只有
`/usr/bin:/bin:/usr/sbin:/sbin`）。Windows 的 PATH 在环境变量块里，IDE 直接继承，
不需要读 profile；而读 profile 会引入几百毫秒到数秒的启动延迟与用户 profile 报错的
风险。**这一条要在 KDoc 里写明，否则后来者会以为是漏了。**

已知取舍：Windows 上把 CLI 配成 PowerShell **函数或别名**的用户拿不到它
（`-NoProfile` 不读 profile）。macOS 上 `-i` 是为了 alias 才加的（本机的 `claude`
就是个带 `--dangerously-skip-permissions` 的 alias）。Windows 上 npm 装的 CLI 是
PATH 里的 `.cmd` shim，不是别名，所以这个取舍的触发面很窄。记入遗留。

### `probeScript`

给 `lsp/BinaryProbe.kt` 用，一次问完一批二进制在不在 PATH 里，输出
`名字<TAB>路径`（查不到时路径为空）。

POSIX（**现有实现原样保留**）：

```
printf '%s\t%s\n' 'gopls' "$(command -v 'gopls' 2>/dev/null)"; …
```

PowerShell：

```powershell
$ErrorActionPreference='SilentlyContinue'
@('gopls','jdtls') | ForEach-Object {
  "$_`t$((Get-Command $_ -ErrorAction SilentlyContinue | Select-Object -First 1).Source)"
}
```

两侧的输出格式必须一致（现有的解析函数不改），这一条要有测试钉住。

---

## 组件二：shell 解析平台化

`terminal/AgentCommand.kt` 的 `resolveShell` 改为：

```kotlin
internal fun resolveShell(
    shellEnv: String?,          // System.getenv("SHELL")
    isWindows: Boolean,         // SystemInfo.isWindows，注入
    configuredShell: String?,   // TerminalOptionsProvider 的 shellPath，注入
): String
```

行为：

- **`isWindows == false`：完全保持现状**，`shellEnv?.takeIf { isNotBlank() } ?: "/bin/zsh"`。
  一个字节不变，与 `configuredShell` 无关——换数据源会改变 macOS 行为，与统领约束冲突
- **`isWindows == true`**：`configuredShell` 取其方言；是 POSIX 或 POWERSHELL 就用它，
  是 cmd（或取不到）就退回 `"powershell.exe"`

`TerminalOptionsProvider.getInstance().shellPath` 是用户在 Terminal 设置里配的那个
shell，也就是他自己终端里跑的东西——Windows 上比 `System.getenv("SHELL")` 可靠得多，
且能正确覆盖 Git Bash 用户。

四个调用点各自把 `SystemInfo.isWindows` 与 `TerminalOptionsProvider` 读进来传给它。
`BinaryProbe` 在池线程上跑，`TerminalOptionsProvider` 是应用级 service，可安全获取。

---

## 组件三：`launchCommand` 与 LSP 执行按钮

`terminal/AgentCommand.kt:launchCommand` 与 `lsp/LspRemedyRun.kt:runCommandLine`
都改成经 `ShellDialect` 拼：

```kotlin
listOf(shell) + shellArgs(dialect) + command
```

`launchCommand` 内部拼 `--session-id` / `--resume` / 初始 prompt 时用
`quote(dialect, …)` 而不是写死的 `singleQuote`。

**验收：`launchCommand(shell = "/bin/zsh", …)` 对三个 agent、有无 resumeId、
有无 initialPrompt 的每一种组合，返回值与改动前逐字节相同。** 这是 `TerminalIntegrationSourceTest`
之外新增的一组 `assertEquals`，是 macOS 不回归的主防线。

### `canRun` 的闸门收缩

`lsp/LspRemedyRun.kt:canRun` 现在是：

```kotlin
hasPosixShell && remedy.commands.isNotEmpty() &&
    (isMac || remedy.commands.none { it in LspCatalog.macOnlyCommands })
```

改动两处：

1. **`hasPosixShell` 维度删除**。它存在的理由是「Windows 上这一页本来能用，
   多一个点了报错的按钮是拿能用的换不能用的」——`resolveShell` 支持 Windows 之后
   这个前提消失了。`LspRemedyRun.kt` 的 KDoc 亲口写着「`resolveShell` 支持 Windows
   之后，第 1 条该去掉」
2. **`macOnlyCommands` 收窄**，见组件六

删掉一个维度会连带删掉 `ImuxLspConfigurable.kt:574` 的 `!SystemInfo.isWindows` 实参
与 `LspRemedyRunTest` 里对应的用例。**删除的每一条断言都要在实现报告里逐条说明
「它守的东西是不是真的没了」**——这个文件因「重构时顺手删老断言让出地盘」被代码审查
打回过两次。

---

## 组件四：安装命令只跨该跨的

`lsp/LspCatalog.kt` 里 13 个 language server，7 个用的是语言自带的包管理器，
本来就跨平台：

| 跨平台，三平台都给按钮 | 只在 macOS 上核实过 |
| --- | --- |
| `go install golang.org/x/tools/gopls@latest` | `brew install jdtls` |
| `npm install -g typescript-language-server typescript` | `brew install --cask kotlin-lsp` |
| `npm install -g pyright` | `brew install lua-language-server` |
| `npm install -g intelephense` | `brew install llvm`（C/C++） |
| `gem install ruby-lsp` | 前置工具 `brew install --cask dotnet-sdk` |
| `dotnet tool install --global csharp-ls` | 前置工具 `brew install rustup` |
| `dotnet tool install --global fsautocomplete` | 前置工具 `brew install opam` |

**改动：`macOnlyCommands` 从「目录表里所有安装命令的并集」收窄成「`requiredTool`
是 `brew` 或 `opam` 的那些」。** 纯数据/纯逻辑改动，调用点不变。

**为什么不补 Linux / Windows 版的 brew 系命令。** Linux 要分 apt / dnf / pacman /
zypper 四套，Windows 要 winget 包 ID，**本次没有任何办法验证它们**（见「验证边界」）。
编一条跑不通的命令挂在「启用」按钮上，正是用户明确不要的那类 bug。这 4 门语言在
非 macOS 上保持现有退路：短目标名 + 完整命令 tooltip + 上游文档链接——那是一份完整
产出，不是缺失。

`LspCatalog` 的 KDoc 里那句「Linux/Windows 用户看到的是同一条命令……这是已知取舍」
要改写：现在只有 brew/opam 那几条是取舍，其余已经是对的。将来有人在真机核实过
Linux/Windows 的命令，补数据即可，调用点不用动。

---

## 组件五：第 4 层——会话漂移探测

### 它是什么，为什么不能猜

会话 id 不是终端的固有属性。用户在终端里敲 `/clear`（claude）或 `/new`（codex），
CLI 换一个会话 id 并另起会话文件，**而进程自始至终没变**。插件若不跟进，终端会一直
记在旧 id 下：标题停更、未读清不掉、完成通知盯着一个不再增长的文件；用户在列表里点
新会话时会以真实 id 再开一个终端，与仍在运行的原进程抢同一个会话。

`LiveSessionProbe` 的 KDoc 立了一条铁律：**认不出就跳过，不能猜**——
「认错会把终端迁到别人的会话上，比不迁移更糟」。本设计不放松这一条。

它需要两个映射：

- **进程 → 哪个终端标签**（身份）
- **进程 → 此刻在跑哪个会话**（内容）

### 好消息：`LiveSessionProbe` 一行都不用改

它的 KDoc 写着「所有 IO 以函数注入，本类因此完全脱离进程表与文件系统，可直接测试」。
当初为可测性做的设计，正好就是跨平台需要的接缝。四个注入点
（`pidsOf` / `tabIdOf` / `claudeSessionOf` / `rolloutsHeldBy`）保持签名不变，
**只换实现**。

### 三平台方案

| | macOS | Linux | Windows |
| --- | --- | --- | --- |
| **claude** | `ps eww` + `~/.claude/sessions/<pid>.json` **不动** | `/proc/<pid>/environ` + 同一个文件 | shell 自报 pid + 同一个文件 |
| **codex** | `lsof -p` 取句柄 **不动** | `/proc/<pid>/fd/` 读软链 | **读 codex 自己的运行态 sqlite**（`CodexRuntimeIndex`） |
| **pi** | `-e` 扩展 → HTTP 上报 **不动** | 同左 | 同左 |

#### Linux：改用 `/proc`，是净收益

`session/ProcessProbes.kt` 的两个实现在 Linux 上换成直接读 `/proc`：

- `readTabId(pid)` → 读 `/proc/<pid>/environ`（NUL 分隔的 `KEY=VALUE`），取 `IMUX_TAB`
- `readHeldRollouts(pid)` → 遍历 `/proc/<pid>/fd/`，`Files.readSymbolicLink` 取目标

好处有三：不起子进程（现在每次探测起两个）、**不依赖 `lsof`**（很多发行版默认没装，
现在的实现在那些机器上静默返回空）、没有输出格式解析。

macOS 分支（`ps eww` / `lsof` + 现有的两个纯解析函数）**原样保留**，
`tabIdFromPsOutput` 与 `rolloutPathsFromLsof` 一个字节不改。

#### Windows / claude：shell 自报 pid

Windows 上**读不到别的进程的环境变量**——环境块在目标进程的 PEB 里，要
`ReadProcessMemory` + 调试权限；`Get-Process` 不给，JDK 也不给
（`ProcessHandle.Info.arguments()` 在 Windows 上恒为空，`commandLine()` 因此只有
可执行文件路径）。所以 `IMUX_TAB` 这条身份通道在 Windows 上不成立。

替代：**启动命令由 imux 全权拼接，让 shell 把自己的 pid 写下来。**

```powershell
$PID | Set-Content -LiteralPath '<systemPath>/imux/tabs/<tabId>.pid'; & claude
```

然后 `CLI pid → parent → …` 向上走，撞到某个 pid 文件里的值就认领对应 tabId。
向上走而不是向下找，是因为前者是 O(链长)；**深度上限 8**，因为 Windows 上 npm 装的
CLI 是 `.cmd` shim，链路可能是 `powershell → cmd → node → claude`，比 POSIX 深。

`ProcessHandle.parent()` 三平台都有，不需要任何特权。

约束：

- pid 文件只写进 `PathManager.getSystemPath()` 下 imux 自己的目录，**不碰用户的任何
  配置**
- 标签关闭时删除；IDE 启动时清扫整个目录（残留来自崩溃退出）
- 只在 Windows 分支生成这一段命令。**macOS / Linux 的启动命令一个字不加**

#### Windows / codex：读 codex 自己的运行态 sqlite

> **本节以下关于 hook 的内容已被取代，保留作为记录。**
> 实现落地时改成了读 codex 的运行态 sqlite，整套 hook 机制已删除
> （`CodexHookOverride.kt`、`codex-imux-reporter.ps1`、`CODEX_REPORT_PATH`、
> `handlesCodexReport` 全部不复存在）。下面那些实证是真的，只是这条路不再走了。
> 换掉的理由：hook 那条路要注入 `-c`、要随包分发 `.ps1`、要扩一条 HTTP 端点，
> **还会让用户首次开 codex 标签时被 codex 的 hook 信任复核屏挡一次**，
> 另带一处本仓库无法验证的 PowerShell 5.1 UTF-8 编码风险。读 sqlite 一样都不需要。

**现行做法。** 本设计当初断言「codex 没有运行态文件」，那条断言是**错的**。
codex 的 `logs_<n>.sqlite` 里 `logs.process_uuid` 的字面格式就是 `pid:<PID>:<uuid>`，
同一行带 `thread_id`；`state_<n>.sqlite` 的 `threads.rollout_path` 由 `thread_id`
给出路径。两跳即可由 pid 查到「此刻在写哪个 rollout」，本机实证过这条链的产出与
`lsof -p` 报的**逐字相同**（`CodexRuntimeIndex.kt`）。

存放目录由 codex 的 `sqlite_home` 配置键决定，读不到就用 `~/.codex`。
只读打开，绝不干扰正在写库的 codex；任何异常一律降级为 null，上层据此跳过本轮认领。

`readHeldRollouts` 的 Windows 分支因此接的是 `CodexRuntimeIndex.rolloutPathOf(pid)`
（`ProcessProbes.kt`）。**「进程 → 哪个终端标签」那一半仍走 Task 9 的 pid 文件**——
`tabPidFileFor` 只看 `SystemInfo.isWindows`，不看 agent 类型，codex 标签同样写 pid 文件。
被 sqlite 取代的只是「此刻在跑哪个会话」那一半。

---

**以下为已被取代的 hook 方案（保留作为记录）。**

当初的判断是：codex 既没有运行态文件（`state_5.sqlite` 的 `threads` 表无 pid 字段），
又要靠读打开的文件句柄——后者在 Windows 上需要 Sysinternals `handle.exe`，不自带、
要管理员权限，两条观测面全断。前半句已被上面推翻。

但 codex 支持 hooks，且 hooks 可由命令行覆盖。本机实证：

```
$ codex debug models -c 'hooks.SessionStart=42'
Error: invalid type: integer `42`, expected a sequence in `hooks`

$ codex debug models -c 'zzz_nonexistent.foo=42'
（正常输出，未知键被静默忽略）
```

`hooks.SessionStart` 是 codex 认识的、有类型的配置键。因此 imux 可以在启动时注入一个
`SessionStart` hook，**不写任何配置文件**——与 pi 的 `-e <扩展路径>` 是同一个形状，
也与「imux 不修改用户的 CLI 配置」这条产品承诺一致（`plugin.xml` 的描述里写着
「imux does not host or modify your sessions」）。

**条目 schema（逐层实证得出，非推断）：**

```toml
hooks.SessionStart = [
  { matcher = "<字符串，可选>",
    hooks = [ { type = "command", command = "<字符串>" } ] }
]
```

各层的错误信息依次是：`expected struct MatcherGroup`（顶层元素）、
`matcher` → `expected a string`、`hooks` → `expected a sequence`、
其元素 → `expected internally tagged enum HookHandlerConfig`、
`type="command"` 时缺 `command` → `missing field 'command'`、
`command` 非字符串 → `expected a string`。与 Claude Code 的 hook 结构同族。

`command` 指向 imux 随插件分发的一个脚本，**与 pi 的 `piReporterScript()` 同一套做法**：
脚本从环境变量拿 `IMUX_TAB`（hook 进程继承 codex 的环境）与 imux 的端点地址+令牌，
从 stdin 的 hook payload 取 `session_id`，POST 回去。脚本随平台选型
（Windows 上是 PowerShell 脚本）。

整条 `-c` 实参由 `quote(dialect, …)` 包裹后拼进启动命令——**它是嵌套引号最深的一处，
必须有针对性用例**：TOML 字符串里嵌着脚本路径，外面再套一层 shell 引号。

#### HTTP 端点泛化（已被取代，保留作为记录）

> 这一节描述的是 hook 方案的服务端一半，随 hook 一起删除。
> `session/PiSessionReportHandler.kt` 已**逐字节回到** pi-only 的形态
> （与 commit `4eaeea7` 的同名文件完全相同），`CODEX_REPORT_PATH`、
> `handlesCodexReport`、`parseCodexReport`、`codexEndpointOf` 均不复存在。
> pi 那一侧从头到尾一个字节都没有变过——这正是当初并列一条新路径而不是在 body 里
> 加判别字段的目的：删起来同样不必碰 pi。

`session/PiSessionReportHandler.kt` 当时只认一个路径、只调
`SessionMonitor.onPiSessionReported`。当时的改动：

- **新增一条独立路径**给 codex，不在现有路径的 body 里塞 agent 类型判别字段。
  理由：`handlesPiReport(uri, isPost)` 是纯函数且已被用例钉住，加判别字段要改
  `parsePiReport` 的解析与它的全部用例；新路径只是并列多一个 `if`，pi 那一侧
  **逐字节不变**
- **令牌校验原样复用**。KDoc 写着「令牌是这个接口唯一的门禁：平台在本层不做任何校验，
  本机任意进程都能打进来」——新路径必须走同一个 `piReportTokenMatches`，
  不得另写一套比较逻辑（该函数的 KDoc 列了四种写宽就会漏的写法）
- **pi 的既有路径逐字节不变**，包括 `handlesPiReport` 对所有既有输入的返回值

### 跨平台路径处理的两处已知缺陷

顺带修掉，它们是纯函数、可直接测：

1. `session/ProcessProbes.kt:65` `codexPids()` 用
   `command().substringAfterLast('/') == "codex"` 认进程。Windows 上路径分隔符是 `\`、
   可执行文件叫 `codex.exe`，两头都不匹配 → 一个 codex 进程都认不出来
2. `session/LiveSessionProbe.kt` 的 `fileNameOf` 同样只切 `/`。Windows 上 rollout 路径
   是 Windows 原生写法，用 `\`（现在它来自 `state_<n>.sqlite` 的 `threads.rollout_path`；
   当初设想的来源是 hook payload，来源换了而这条缺陷不变）

两处都改成同时切 `/` 与 `\`，并在比较可执行文件名时去掉 `.exe`（大小写不敏感）。
**在 macOS 上这两处改动必须是恒等的**——现有输入不含 `\`、不含 `.exe`，要有用例钉住。

---

## 错误处理与降级

原则：**读不到就是读不到，不猜。** 每一处降级的可见形态都必须与「正常但为空」区分开。

| 场景 | 行为 |
| --- | --- |
| Windows 上 `TerminalOptionsProvider` 取不到 shell | 退回 `powershell.exe` |
| 解析到 cmd 方言 | 改用 `powershell.exe`，记 INFO 日志 |
| `/proc/<pid>/environ` 无权限 / 进程已退出 | 该 pid 返回 null，本轮不认领（与现有 `ps` 失败同构） |
| `/proc/<pid>/fd/` 部分软链读不了 | 跳过该条，其余照常（不能整体失败） |
| pid 文件写入失败 | 会话照常启动，只是漂移探测认不出这个标签。**不能因此让会话起不来** |
| Windows 上 codex 的运行态 sqlite 读不到 / 表结构变了 | 一律降级为 null，本轮不认领，会话正常（`CodexRuntimeIndex` 把任何异常都吞成 null） |
| 探测整体失败 | 沿用现状：LSP 页显示「无法确定」，漂移探测本轮无产出 |

---

## 测试策略

项目约束：只能 JUnit 4，无平台 test-framework，测试方法中文反引号命名，不新增 Gradle 依赖。

**1. macOS 不回归（本次最重要的一组）**

对 `launchCommand`、`runCommandLine`、`probeScript`、`quote`、`shellArgs`、
`resolveShell`，各写一条「macOS 形态逐字节」用例，用 `assertEquals` 钉死完整返回值。
`probeScript(POSIX, …)` 的期望值直接取现有 `buildProbeScript` 的输出。
这些用例的失败信息要写明：**「这是 macOS 现网行为，改它等于改用户机器上正在工作的东西」**。

**2. 三平台分支在 macOS 上真调用**

所有平台参数注入，因此 `resolveShell(shellEnv = null, isWindows = true, configuredShell = "C:\\…\\pwsh.exe")`
这种调用在开发机上就能断言。每个平台 × 每个分支都要有用例。

**3. 变异验证**

对每一处关键改动，改回缺陷形态 → 确认对应用例 FAIL → 还原。实测时核对
`> Task :test` 那行不是 `UP-TO-DATE` / `FROM-CACHE`。

**4. 纯函数优先**

`dialectOf`、`quote`、`shellArgs`、`probeScript`、路径处理、pid 链上溯（注入
`parentOf: (Long) -> Long?`）全部做成纯函数。UI 壳里只留调用点。
`ImuxLspUiSourceTest` 因源码文本断言被打回过**七轮**，现有做法是「纯逻辑搬出去做真调用
测试，UI 壳只留调用点断言」——本次新增代码从一开始就照这个分层。

**5. 不得删既有断言让出地盘**

`LspRemedyRunTest` 与 `ImuxLspUiSourceTest` 因此被打回过两次。本次要删
`hasPosixShell` 相关用例，**每一条都要在报告里说明它守的东西是不是真的没了**。

---

## 验证边界（必须照实写进交付说明）

**这台机器上无法验证 Linux 与 Windows 的任何一行运行时行为。** 不得在交付时把推断
说成验证。

**已在本机实证：**

- `codex -c 'hooks.SessionStart=…'` 是 codex 认识的、有类型的配置键（错类型报
  `expected a sequence in 'hooks'`，未知键被静默忽略）
- **codex hook 条目的完整 schema**，逐层用错类型试出来（见组件五）：
  `[{ matcher = "…", hooks = [{ type = "command", command = "…" }] }]`
- `~/.claude/sessions/<pid>.json` 带 `pid` / `sessionId` / `cwd` / `kind`
- codex `state_5.sqlite` 的 `threads` 表仍无 pid 字段（老结论复核成立）
- 平台提供 `org.jetbrains.plugins.terminal.TerminalOptionsProvider`
- macOS 上现有 13 条安装命令与 3 条前置工具命令

**推断，未证实：**

- codex 的 `SessionStart` hook 在用户敲 `/new` 时是否触发
- `-c` 注入的 hook 是否触发信任提示（`config.toml` 有 `hooks.state.*.trusted_hash`
  机制，二进制里另有 `allow_managed_hooks_only` 这类企业策略开关）
- codex 自己的 hook 执行器在 Windows 上是否工作（二进制里有 `SHELL-lc` 字样，
  是 POSIX 形状）
- Windows 上 PowerShell 的 `$PID | Set-Content` 与父子链上溯是否如设计所想
- Linux 上 `/proc/<pid>/environ` 与 `/proc/<pid>/fd/` 的权限表现
- **Linux / Windows 上任何一条命令能否跑通**

对 macOS 的保护是硬的：平台判断全部注入、macOS 分支逐字节钉死，改动它当场变红。

---

## 不做什么

- **不补 Linux / Windows 版的 brew 系安装命令**（4 门语言 + 3 个前置工具）。无法验证，
  保持文档链接退路
- **不支持 cmd 方言**。改用 PowerShell
- **不改 macOS 与 Linux 的启动命令形状**。pid 自报只在 Windows 分支生成
- **不动第 1 层与第 5 层**。它们本来就跨平台
- **不把 hook 机制推广到 macOS/Linux 的 codex**。那会改动正在工作的路径，与统领约束
  冲突。（后续结论：hook 机制已整套删除，这一条自然作废）
- **不给 claude 装 hook**。Windows 上 claude 走 pid 自报，够用且不依赖 claude 的特性

## 遗留

1. ~~**codex 的 hook 上报应当取代三平台的 `ps`/`lsof`/`/proc` 轮询**~~
   **已作废**：hook 那条路已整套删除，Windows 上改读 codex 的运行态 sqlite。
   若将来还想把三平台统一到「推」模型上，起点也不该是 hook——它带一次
   什么都换不到的信任复核屏。
2. **Windows 上 `-NoProfile` 意味着配成 PowerShell 函数/别名的 CLI 拿不到**。触发面窄
   （npm 装的是 PATH 里的 `.cmd` shim），但要记一笔
3. **brew 系 4 门语言的 Linux / Windows 安装命令**。等有真机的人核实后补数据
4. **`resolveShell` 支持 Windows 之后，`terminal/` 侧的 Windows 路径仍未真机验证**。
   会话启动在 Windows 上从未工作过，本次是第一次
5. `docs/superpowers/specs/2026-08-21-cli-lsp-diagnostics-followups.md` 第 10 / 10.1 条
   记录的就是本设计要解决的问题，实现完成后应回填结论

---

## 交付状态

全量构建与测试通过（`./gradlew clean test --offline` 与
`./gradlew buildPlugin --offline`，**全量套件 844 个测试方法**全绿、0 失败 0 跳过，
`build/distributions/imux-0.3.7.zip` 已产出）。打包产物里的脚本
**只剩 `imux/scripts/pi-imux-reporter.js` 一个**——`codex-imux-reporter.ps1` 已随整套
hook 机制删除（`unzip -l build/distributions/imux-0.3.7.zip | grep -E "ps1|js"`
只有那一行）。

> **本节四个子节（已验证 / 推断，未证实 / 真机验证清单 / 仍未补齐的能力）里的每一个
> 行号、符号名与数字，都在「删掉 codex hook 机制」那次改动中逐条 grep 核对过**，
> 不是从旧版抄下来的。源码一改就会漂，读到对不上时以符号名为准。
>
> 851 → 844：删掉 hook 之后不再存在的 **35** 条用例，新补 **5** 条（净 −30）。
> 上一版记的 851 与删除前的实测基线 874 之间还差 23 条，那是 851 落笔之后若干次提交
> 陆续新增的，与本次无关。
>
> 那次改动同时查出并修正了本节原有的一批失实引用，分三类：**十余处行号对不上**
> （其中若干在更早之前就已经错了，例如 `pidFileRecordCommand` 的 POSIX 分支被同时
> 标成第 150 行与第 210 行两个值）、**一处代码写法抄成了编译不过的 Kotlin**
> （`takeIf { isNotBlank() }`，源码是 `takeIf { it.isNotBlank() }`）、
> **一处结论整条过时**（`readHeldRollouts` 的 Windows 分支仍被写作「直接返回空」，
> 而它早已改成读运行态 sqlite——照那句话读会得出「Windows 上漂移探测根本没做」
> 的错误结论）。逐处清单与核对方式见该次提交的实现报告。

### 已验证

**在 macOS 上实证（CI + 本机）：**

- 三平台分支（macOS / Linux / Windows）均在 macOS 上由普通 JUnit 4 以参数注入的方式
  真调用并断言。**844 是全量套件的总数，不是三平台分支的用例数**——本仓库的绝大多数
  用例与平台无关（会话解析、轮次判定、UI 源码断言等），三平台分支只占其中一小部分
- macOS 分支的返回值用 `assertEquals` 钉死：`launchCommand`、`resolveShell`、
  `quote`、`shellArgs` 对 macOS 的输出写的是**字面量**期望值
  （`listOf("/bin/zsh", "-l", "-i", "-c", "claude")`、`listOf("-l", "-i", "-c")` 等），
  与改动前逐字节相同
- `probeScript` 对 macOS 的输出用的是**自指等式**
  `assertEquals(buildProbeScript(b), probeScript(POSIX, b))`——它钉住的是
  「POSIX 分支就是原来那个函数、一个字节不加工」，**不是**脚本文本的字面量。
  脚本文本本身另由 `BinaryProbeTest` 的字面量断言（`command -v`、`2>/dev/null`、
  `'%s\t%s\n'`）守着
- `ShellDialect.POSIX` 的 `shellArgs` 返回 `["-l", "-i", "-c"]`、`quote` 使用
  `'\''` 转义——与现有 `singleQuote` 行为逐字节相同
  （`ShellDialect.kt` 第 85 行、第 143 行）
- `ShellDialect.POWERSHELL` 的 `shellArgs` 返回
  `["-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command"]`（第 86 行）、
  `quote` 使用 `''` 转义（第 144 行）。`-ExecutionPolicy Bypass` 是 Process 作用域、
  不写任何配置文件（npm 的 `cmd-shim` 会铺出 `.ps1`，而 Windows 客户端 SKU 的默认
  执行策略是 `Restricted`）。这段论证原先挂在 `codexHookOverrideArg` 的 KDoc 上，
  该函数已删除，论证已搬进 `shellArgs` 自己的 KDoc
- 命令链按方言拼装：`commandChain(POSIX, …)` 仍是 `commands.joinToString(" && ")`
  （`ShellDialect.kt` 第 120 行，与改动前逐字节相同、有字面量断言），
  `commandChain(POWERSHELL, …)` 改用 `$LASTEXITCODE` 显式检查
  （常量 `POWERSHELL_EXIT_ON_ERROR`，第 125 行）——`&&` 是 PowerShell 7.0 才引入的
  操作符，Windows 自带的 5.1 见到它直接报解析错误、整条链一个命令都不跑
- `resolveShell(shellEnv, isWindows=false, configuredShell)` 完全保持旧行为：
  `shellEnv?.takeIf { it.isNotBlank() } ?: "/bin/zsh"`，忽略 `configuredShell`
  （`AgentCommand.kt` 第 181-196 行，非 Windows 那一句在第 186 行）
- `canRun` 已删除 `hasPosixShell` 维度（`LspRemedyRun.kt` 第 80-85 行）。
  **`src/main` 全树 grep `hasPosixShell` 只有 1 处命中**——`LspRemedyRun.kt` 第 71 行，
  正是解释「这个维度已删除」的那段 KDoc，没有任何可执行代码引用它
- `macOnlyCommands` 收窄为 `requiredTool` 在 `NON_PORTABLE_TOOLS`（`setOf("brew", "opam")`）
  中的命令集（`LspCatalog.kt` 第 134-136 行、第 198 行）
- `executableMatches` 同时切 `/` 与 `\`、Windows 分支去 `.exe` 后大小写不敏感比较，
  macOS 输入上是恒等的（`ProcessProbes.kt` 第 171-182 行）
- `fileNameOf` 的反斜杠切分**只在 Windows 分支做**，POSIX 侧只切 `/`——
  `\` 在 POSIX 上是合法文件名字符。`codexCwdKey` 的分隔符替换同样只在 Windows 分支做。
  两处与 `executableMatches` 的 POSIX 侧对齐，各有一条「POSIX 上反斜杠属于名字本身」
  的断言（`LiveSessionProbe.kt`、`CodexSessionReader.kt`）
- `readHeldRollouts` 三平台分派（`ProcessProbes.kt` 第 131-145 行）：
  **Windows 读 codex 的运行态 sqlite**（`rolloutOfPid` 注入点，生产实现
  `codexRolloutOfPid` 走 `CodexRuntimeIndex(~/.codex).rolloutPathOf(pid)`），
  Linux 读 `/proc`，macOS 走 `lsof`。三支各有一条真调用断言，
  其中 macOS 那条把 `runCommand` 注入进来、直接断言它收到的是 `lsof -p <pid>`
  ——这是「走了 lsof 但没结果」与「压根没走 lsof」唯一的分水岭。
  **这一条与旧版不同**：旧版写的是「Windows 直接返回空，codex 改走 hook 上报」，
  那是 hook 方案下的形态，已被取代
- tabId 判据（`isTabId`）三条读取通道共用一条：`ps`、`/proc`、Windows 的 pid 文件名
- `pidFileRecordCommand` 对 `POSIX` 方言返回 `null`（`ShellDialect.kt` 第 210 行），
  macOS/Linux 启动命令不添加任何内容；PowerShell 那一支生成
  `$PID | Set-Content -LiteralPath <quoted> -Encoding ascii`（第 211-212 行）
- Linux 的 `/proc` 探针（`readTabIdFromProc` 第 48 行、`readHeldRolloutsFromProc`
  第 68 行）以伪 `/proc` 目录在 macOS 上真调用断言（`ProcLinuxProbe.kt`）
- Windows 的 pid 文件通道（`tabIdByParentChain` 第 51 行、`tabPidFilesIn` 第 88 行、
  `imuxTabPidDir` 第 163 行）以注入的 `parentOf` 与临时目录在 macOS 上真调用断言，
  深度上限 8（`WindowsTabPidFile.kt` 第 55 行 `maxDepth: Int = 8`）。
  **这套机制在删掉 hook 之后原样保留**：Windows 上「这个 shell 属于哪个标签」
  仍然只有它能回答，codex 标签同样写 pid 文件
- 上报端点只剩 pi 一条：`PI_REPORT_PATH = "/imux/pi-session"`
  （`PiReportEndpoint.kt` 第 14 行）。`CODEX_REPORT_PATH` 已删除，
  `PiSessionReportHandler.kt` 与 commit `4eaeea7` 的同名文件**逐字节相同**

**在真 codex TUI 上实证（Task 10 实现者用隔离 `CODEX_HOME` 验证）：**

> **以下三条都是真的，但这条路已不再使用。** hook 机制已整套删除，Windows 上的
> codex 改读运行态 sqlite。保留这三条是因为它们是真做过的实证——直接删掉会让读者
> 以为从没验证过；同时它们也是「为什么不走 hook」的证据本身（第二条尤其）。

- codex 的 `SessionStart` hook **在用户敲 `/new` 时确实会触发第二次**，带新的
  `session_id`
- **`-c` 注入的 hook 确实会触发信任提示**——
  `Hooks need review / Trust all and continue / Continue without trusting`。
  选信任后 **codex 自己**往 `~/.codex/config.toml` 写 `trusted_hash`。
  **imux 一个字节都不写。** 当时仓库所有者裁定接受首次的那一下提示；
  **后来这正是换掉 hook 的主要理由之一**——用户首次开 codex 标签被挡一次，
  而现在读 sqlite 什么都不需要
- **codex 的 hook 执行器确实能跑起来注入的命令**——shell 收到正确加引号的路径
  （含**路径带空格**的用例）、hook 真的触发、`IMUX_TAB` 被继承

### 推断，未证实

1. **Windows 上 codex 的运行态 sqlite 是否在同样的路径、同样的 schema。**
   `CodexRuntimeIndex` 的两跳（`logs_<n>.sqlite` 的 `logs.process_uuid` 形如
   `pid:<PID>:<uuid>` → `state_<n>.sqlite` 的 `threads.rollout_path`）
   是在 **macOS 上**对着真 codex 实证的。Windows 上未证实的有三层：
   `%USERPROFILE%\.codex\` 下是否同样有这两个库、`process_uuid` 里的 PID 是否就是
   `ProcessHandle` 看到的 Windows pid、以及 codex 是否对这些库加了独占写锁
   （只读打开通常不受影响，但未验证）。任一层不成立的症状都是「标签不跟随」，
   **不报错**——`CodexRuntimeIndex` 把任何异常都降级成 null
2. **codex 自己的 hook 执行器在 Windows 上是否工作。** codex 二进制里有 `SHELL-lc`
   字样，是 POSIX 形状；Windows 上的行为未知。
   **这一条已无关紧要**：hook 那条路已整套删除，留着只为记录当时的未知项
3. **Windows 上 PowerShell 的 `$PID | Set-Content` 与父子链上溯是否如设计所想。**
   `pidFileRecordCommand` 生成的命令（`ShellDialect.kt` 第 211-212 行）
   与 `tabIdByParentChain` 的父链遍历（`WindowsTabPidFile.kt` 第 57-72 行的函数体）
   都只在 macOS 上以注入参数测试，未在真 Windows 上运行过。
   **删掉 hook 之后这一条的分量更重了**：Windows 上 codex 与 claude 现在都靠它认
   「属于哪个标签」，它不成立就是两种 agent 一起不跟随
4. **Linux 上 `/proc/<pid>/environ` 与 `/proc/<pid>/fd/` 的权限表现。**
   `readTabIdFromProc` 与 `readHeldRolloutsFromProc`（`ProcLinuxProbe.kt`）
   在 macOS 上用伪目录验证了逻辑，但 `/proc` 在不同发行版上的权限策略可能不同
5. **Linux / Windows 上任何一条命令能否跑通。** 本仓库没有这两个平台的环境
6. **PowerShell 5.1 的执行策略绕过与命令链形式只验证了源码形态。**
   `shellArgs(POWERSHELL)` 的 `-ExecutionPolicy Bypass`、以及
   `commandChain(POWERSHELL, …)` 用 `$LASTEXITCODE` 顶替 `&&`，
   两者都只在 macOS 上以字面量断言钉住了**生成的文本**，**没有在真 Windows 上
   运行过一次**。已知的推理边界：
   - `-ExecutionPolicy Bypass` 盖不过组策略（`MachinePolicy` / `UserPolicy`
     优先级高于 Process），企业环境里被 GPO 锁死的机器上仍然跑不起来
   - `$LASTEXITCODE` 在命令**根本不存在**（CommandNotFound）时不会被更新，
     链不会停在那一步；后果是用户看到一条错误、后面的命令继续跑（多半也失败），
     这些在终端里全都看得见。刻意**不**引入 `$ErrorActionPreference='Stop'`
     去补它：那条在 5.1 上对原生命令 stderr 的行为无法在本仓库验证

### 真机验证清单（只有仓库所有者能做，按优先级）

1. **Windows：三个 CLI 的会话标签能否起来** —— 优先级最高，验证第 6 条的前一半
   （npm 装的 `claude.ps1` / `codex.ps1` 在默认执行策略下能否被 PowerShell 拉起）
2. **Windows**：LSP 页点「启用」时，**多条命令的链**能否跑完——验证第 6 条的后一半
   （`$LASTEXITCODE` 串接在 5.1 上的实际行为）；干净机器上「二进制缺 + 配置缺」
   必然产出两条以上命令，这是最常见的形态
3. **Windows：codex 标签里敲 `/new`，标签是否跟上** —— 验证第 1 条。
   这是运行态 sqlite 那条路唯一的端到端判据。先看
   `%USERPROFILE%\.codex\` 下有没有 `logs_<n>.sqlite` 与 `state_<n>.sqlite`，
   再看 `logs.process_uuid` 是不是同样的 `pid:<PID>:<uuid>` 字面格式
4. **Windows**：claude 里敲 `/clear`，标签是否跟上 —— 验证第 3 条的 pid 文件那一半
5. **Windows**：LSP 页 18 门语言不再全是「无法确定」
6. **Windows**：codex 若是 npm 装的 `.cmd` / node shim，`ProcessHandle.info().command()`
   可能返回 `node.exe`，`executableMatches`（`ProcessProbes.kt`）会匹配不上——需确认
7. **Linux**：会话能起、LSP 页能探；`/proc` 探针在无 `lsof` 的发行版上工作
8. **macOS**：整体回归——会话启动、LSP 页、漂移探测三者与改动前一致
9. ~~**Windows / 中文路径下的 codex 标签能否跟随**~~ —— **已降级**。
   它守的是 `codex-imux-reporter.ps1` 在 PowerShell 5.1 上的 UTF-8 编码风险，
   那个脚本连同整套 hook 机制已删除，这条路不存在了。中文路径现在只经过 sqlite 的
   `rollout_path` 与 JDBC 的 UTF-8 读取，与其他平台走同一段代码；
   若第 3 条通过，这一条不必单独验

### 仍未补齐的能力

- **brew 系 4 门语言（Java / Kotlin / Lua / C 与 C++）与 3 个前置工具
  （dotnet-sdk / rustup / opam）的非 macOS 安装命令仍未补。**
  `NON_PORTABLE_TOOLS = setOf("brew", "opam")`（`LspCatalog.kt` 第 198 行）
  过滤了它们的安装命令，使其在非 macOS 上不出现启用按钮。
  原因是本仓库没有 Linux 或 Windows 环境可以验证 apt / dnf / pacman / winget 的写法。
  这几门在非 macOS 上保持既有退路：短目标名 + 完整命令 tooltip + 上游文档链接
- **Windows 上用 Git Bash 的用户拿不到 claude 与 codex 的漂移探测。**
  `pidFileRecordCommand` 对 POSIX 方言返回 `null`（`ShellDialect.kt` 第 210 行），
  而 `readTabId` 仍按 `SystemInfo.isWindows` 走 pid 文件分支
  （`ProcessProbes.kt` 第 80 行声明、Windows 分支在第 89-90 行）。这是有意取舍——
  Git Bash 的 `$$` 是 MSYS pid，对不上 `ProcessHandle` 的 Windows pid
  （`ShellDialect.kt` 第 192-201 行 KDoc 详述了原因与「为什么不能用 `echo $$` 补洞」）。
  **删掉 hook 之后这个洞变宽了半格**：从前 Windows 上的 codex 走 hook 上报、
  与 pid 文件无关，Git Bash 用户至少 codex 那一半还能跟随；现在 codex 认「属于哪个
  标签」也靠 pid 文件，因此两种 agent 在 Git Bash 下一起没有漂移探测。
  （codex 的另一半「此刻在跑哪个会话」由运行态 sqlite 回答，不受 shell 方言影响）
