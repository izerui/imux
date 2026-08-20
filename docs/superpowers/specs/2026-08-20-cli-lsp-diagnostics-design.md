# CLI LSP 体检

给 imux 加一个设置子页，检测 Claude Code、pi、Codex 三个 CLI 的 LSP 覆盖情况，列出还缺哪些语言，并给出可直接复制的安装命令。

**只读。** 不修改任何 CLI 的配置文件，不代替用户安装任何二进制。imux 在这件事上的角色与它对会话库的角色一致——只做视图，不做托管。

## 为什么值得做

三个 CLI 的 LSP 都需要用户自己张罗，而实际用户（尤其是 IDEA 用户）通常不知道该配什么、配了之后二进制在不在。本机实测的真实缺口：

- Claude Code 装了 5 个官方 LSP 插件，二进制全部就绪；但 `kotlin-lsp` 插件没启用，而 `kotlin-lsp` 二进制其实已经装在 `/opt/homebrew/bin/kotlin-lsp`——**纯配置缺口，一行命令即可补上，用户却无从得知**。
- pi 装了 pi-lens，但 Kotlin 属于 pi-lens 的 toolchain-gated 家族，server 二进制不自动安装，pi 的状态栏因此长期显示 `LSP Inactive`。
- Codex 完全没有 LSP，用户多半不知道可以用 pi-lens 的 MCP 模式补上。

## 三个 CLI 的 LSP 现状

以下事实均在本机实测确认。版本：`claude` 2.1.226、`pi` 0.84.2、`pi-lens` 4.0.1、`codex-cli` 0.148.0。

### Claude Code——原生 LSP

配置键 `lspServers`，settings.json 顶层键，与 `mcpServers`、`permissions`、`statusLine` 并列：

```json
{
  "lspServers": {
    "gopls": {
      "command": "gopls",
      "args": ["--background-index"],
      "extensionToLanguage": { ".go": "go" }
    }
  }
}
```

四个生效来源：

| 来源 | 范围 | imux 是否读取 |
| --- | --- | --- |
| `~/.claude/settings.json` | 全局 | ✅ |
| 已启用插件的 `plugin.json` | 随插件（全局启用） | ✅ |
| `<项目>/.claude/settings.json` | 项目（会进 git） | ❌ 见下 |
| `<项目>/.claude/settings.local.json` | 项目私有（不进 git） | ❌ 见下 |

第二种是官方 marketplace LSP 插件的载体，也是本机实际在用的方式：`~/.claude/settings.json` 的 `enabledPlugins` 里列出插件 id，插件定义在 `~/.claude/plugins/marketplaces/claude-plugins-official/.claude-plugin/marketplace.json`，其条目自带 `lspServers` 字段。

**项目级来源不读**：体检页是应用级设置（见「设计 / 1」），拿不到 `Project`，也不该为了读一个项目文件而把整个功能变成项目级。代价是——用户若只在某个项目的 `.claude/settings.json` 里配了 LSP，体检会误报为未配置。这是已知假阳性，页面上以脚注说明"仅检查全局配置"，不做隐藏。选择接受它，是因为逐项目配 LSP 是少数做法（官方插件机制本身就是全局的），而把功能项目化的复杂度要高得多。

官方 marketplace 当前提供 12 个 LSP 插件：

| 插件 | server 二进制 | 语言 |
| --- | --- | --- |
| `clangd-lsp` | `clangd` | c, cpp |
| `csharp-lsp` | `csharp-ls` | csharp |
| `gopls-lsp` | `gopls` | go |
| `jdtls-lsp` | `jdtls` | java |
| `kotlin-lsp` | `kotlin-lsp` | kotlin |
| `lua-lsp` | `lua-language-server` | lua |
| `php-lsp` | `intelephense` | php |
| `pyright-lsp` | `pyright-langserver` | python |
| `ruby-lsp` | `ruby-lsp` | erb, ruby |
| `rust-analyzer-lsp` | `rust-analyzer` | rust |
| `swift-lsp` | `sourcekit-lsp` | swift |
| `typescript-lsp` | `typescript-language-server` | javascript(react), typescript(react) |

**语言服务器二进制一律自备。** 插件里只写 `"command": "gopls"`，不在 PATH 里就启动失败，Claude Code 内部报 `lsp-server-start-failed` / `lsp-config-invalid` / `lsp-server-crashed`。

安装插件的命令：

```
claude plugin install <插件名>@claude-plugins-official
```

### pi——靠 pi-lens 扩展

pi core 没有 LSP：全量 `dist` 中 `lspServers`、`languageServer`、`textDocument/` 零命中，`docs/settings.md` 的全部设置项里也没有 LSP 条目，内建工具只有 `bash`、`edit`、`write`、`read`、`find`、`grep`、`ls`。

LSP 由第三方扩展 [pi-lens](https://github.com/apmantza/pi-lens) 提供，登记在 pi 的 settings 里：

```json
// ~/.pi/agent/settings.json（用户级）或 .pi/settings.json（项目级）
{ "packages": ["npm:pi-lens"] }
```

- 安装：`pi install npm:pi-lens`，加 `-l` 写项目级
- 用户级包落到 `~/.pi/agent/npm/`，项目级落到 `.pi/npm/`
- pi-lens 自身调优：`~/.pi-lens/config.json` + 项目 `.pi-lens.json`，零配置即可用，imux 不需要读它
- 覆盖 36+ 语言（`docs/language-coverage.md`）

**关键限制**：pi-lens 对部分语言的 server 会按 npm / pip / github 策略自动安装，但下列语言属于 **toolchain-gated，今天不自动安装**（pi-lens `docs/lsp-capability-matrix.md`，issue #241）：

```
go, java, kotlin, swift, lua, cpp, haskell, elixir, ocaml, nix, fsharp
```

这 11 个语言需要用户自己把 server 装进 PATH，是 pi 侧唯一需要体检的部分。

### Codex——无 LSP，可挂 pi-lens 的 MCP

二进制中 LSP 标记全部零命中：

```
textDocument/     0
lspServers        0
language_servers  0
lsp_servers       0
languageServer    0
```

Codex 有的是 `mcp_servers`（`~/.codex/config.toml`）和 `codex plugin`。而 pi-lens 自带 `pi-lens-mcp` 可执行文件（`dist/mcp/server.js`），其 `docs/mcp.md` 的既定目标就是把 pi-lens 暴露给 Claude Code 之类的 MCP 宿主。因此 Codex 的等价路径是：

```
codex mcp add pi-lens -- pi-lens-mcp
```

挂上后 Codex 获得与 pi 相同的语言覆盖，二进制缺口也与 pi 一致。

## 设计

### 1. 设置子页

新增 `settings/ImuxLspConfigurable.kt`，注册为现有设置页的子页：

```xml
<applicationConfigurable
        parentId="com.github.izerui.imux.settings"
        instance="com.github.izerui.imux.settings.ImuxLspConfigurable"
        id="com.github.izerui.imux.settings.lsp"
        displayName="LSP"/>
```

落在 **Tools → Imux → LSP**。现有 `ImuxSettingsConfigurable` 不改动——它已经有四个 group，再塞进去会过长，而且体检内容与"偏好设置"性质不同。

应用级而非项目级：**不探测项目语言**。体检目标是"三个 CLI 对主流语言的覆盖度"，与打开的是哪个项目无关。

页面是纯展示：没有可保存状态，`isModified()` 恒为 `false`，无 `apply()`。用 UI DSL `panel {}` 构建，与现有设置页同风格。

### 2. 语言清单

静态资源表，语言取「官方 12 个 Claude Code LSP 插件覆盖的语言」∪「pi-lens 的 11 个 toolchain-gated 语言」。每个语言一条记录：

```
语言 id
显示名
claudePlugin        对应的官方插件名（没有则为空）
claudeServerBinary  该插件要求的 server 二进制名
piLensGated         pi-lens 是否需要手动装 server
piLensServerBinary  pi-lens 使用的 server 二进制名
installCommands     各平台的安装命令
```

注意 Claude Code 与 pi-lens 对同一语言可能用**不同的 server**：Kotlin 上 Claude Code 官方插件用 `kotlin-lsp`，pi-lens 用 `kotlin-language-server`。两者要分别记录、分别探测，不能合并成一个字段。

`installCommands` 按平台分（macOS / Linux / Windows），取当前平台那条展示。某语言在当前平台没有已知安装命令时，展示为"需手动安装"并给出上游文档链接，不编造命令。

### 3. 三个探针

统一接口，各自返回「语言 → 状态」的映射。状态四态：

| 状态 | 含义 |
| --- | --- |
| `READY` | 配置到位且 server 二进制在 PATH 里 |
| `MISSING_CONFIG` | 二进制可能在，但 CLI 没配上 |
| `MISSING_BINARY` | 配置到位，但 server 不在 PATH |
| `UNKNOWN` | 探测超时或失败，信息不足以判断 |

**`ClaudeCodeLspProbe`**
读取并合并两个全局来源的 `lspServers`（`settings.json` 直接定义的覆盖插件提供的），其中插件那一路需要交叉 `enabledPlugins` 与 marketplace 清单。得到「已配置语言 → server command」后，逐个查二进制。
- 语言未出现在合并结果里 → `MISSING_CONFIG`，命令为 `claude plugin install <插件>@claude-plugins-official`
- 已配置但二进制不在 PATH → `MISSING_BINARY`，命令取自静态表

**`PiLspProbe`**
读 `~/.pi/agent/settings.json` 的 `packages`，查找 `npm:pi-lens`（需容忍 `npm:pi-lens@4.0.1` 这类带版本的写法）。项目级 `.pi/settings.json` 同样不读，理由与 Claude Code 一致。
- 未登记 → 整组 `MISSING_CONFIG`，命令 `pi install npm:pi-lens`
- 已登记 → 非 gated 语言直接算 `READY`（pi-lens 自动安装）；gated 语言查二进制

**`CodexLspProbe`**
读 `~/.codex/config.toml` 的 `mcp_servers`，查找 command 指向 `pi-lens-mcp` 的条目。
- 未挂载 → `MISSING_CONFIG`，命令 `codex mcp add pi-lens -- pi-lens-mcp`
- 已挂载 → 语言状态直接复用 `PiLspProbe` 的二进制探测结果（同一套 server）

三个探针共享一个二进制探测结果缓存，同一个 binary 不重复查。

### 4. 二进制探测与 PATH 陷阱

**必须经用户的登录 shell 探测，不能用 IDE 进程自己的 PATH。**

理由与 `terminal/AgentCommand.kt` 顶部注释记录的完全一致：从 Dock/Finder 启动的 IDE 只有系统默认 PATH（`/usr/bin:/bin:/usr/sbin:/sbin`），而语言服务器普遍装在 `/opt/homebrew/bin`、`~/go/bin`、`~/.nvm/versions/node/*/bin` 这些地方。本机实测五个已装的 server 分布在三个不同前缀下，没有一个落在系统默认 PATH 里。

从终端 `runIde` 起的沙箱继承了终端 PATH，所以**这个 bug 只在正式 IDE 上暴露**——项目里已经因为同样的原因踩过一次。

探测方式沿用 `launchCommand` 的形态：

```
$SHELL -l -i -c 'command -v gopls; command -v jdtls; ...'
```

`-l` 读 profile 拿 PATH，`-i` 读 rc。**一次调用批量查完所有二进制**——每个 binary 起一个 login shell 会慢到不可接受（登录 shell 启动本身就有可观开销，二十几个语言就是二十几次）。

输出按行解析：能解析出路径的算就绪，空行算缺失。整体超时（建议 10 秒）后全部标 `UNKNOWN`，不猜。

### 5. 异步与 EDT

shell 探测和文件读取一律不能落在 EDT 上。页面打开时先渲染加载态，后台协程跑完探测后回到 EDT 刷新。

这与 `session/PiReportEndpoint.kt` 里 `PiReportEndpointCache` 的教训是同一条：`BuiltInServerManager.waitForStart()` 曾因为在 EDT 上现算而卡住 UI，最后改成后台预算 + 缓存。此处同理，只是不需要缓存——设置页打开频率低，每次重新探测反而更准（用户可能刚装完东西回来复查）。

页面提供一个「重新检测」按钮，重跑整个流程。

### 6. 报告呈现

按 CLI 分三组，行动导向而非罗列：

```
Claude Code
  已可用 (5)    Go · Java · Lua · Python · TypeScript/JavaScript
  可补充 (7)    C/C++ · C# · Kotlin · PHP · Ruby · Rust · Swift
                claude plugin install kotlin-lsp@claude-plugins-official
                claude plugin install rust-analyzer-lsp@claude-plugins-official
                …                                              [复制全部]

pi
  ✅ pi-lens 已安装，自动覆盖 36+ 语言
  ⚠ 下列语言 pi-lens 不自动安装 server：
      已就绪    Go · Java · Lua
      缺失      Kotlin   brew install kotlin-language-server    [复制]
                Swift    …

Codex
  ❌ 未挂载 pi-lens MCP —— 挂上后即获得与 pi 相同的覆盖
     codex mcp add pi-lens -- pi-lens-mcp                       [复制]
```

图标用 `AllIcons` 语义匹配项（就绪/警告/错误），不自绘。复制按钮走 Action System，用平台的 hover / pressed 状态和 tooltip。

已可用的语言折叠成一行，不逐行展开——体检表一啰嗦就没人看。

### 7. 未安装的 CLI

某个 CLI 本身没装（`command -v claude` 查不到）时，整组显示"未安装"并跳过其余探测，不显示任何缺口。README 已经明确"装一个也能用"，不能因为用户只用 pi 就报一堆 Claude Code 的问题。

## 错误处理

| 情况 | 行为 |
| --- | --- |
| 配置文件不存在 | 当作"未配置"，正常出报告 |
| JSON / TOML 解析失败 | 当作"未配置"，不弹错误对话框；日志留一条 warn |
| marketplace 清单缺失或格式变化 | 该来源跳过，其余来源照常合并 |
| shell 探测超时 | 相关语言标 `UNKNOWN`，展示"无法确定"，不猜 |
| CLI 未安装 | 整组跳过 |

原则是**体检失败不能变成打断**。任何一环出问题都只降级该环的信息量，不影响其余部分，也不影响 imux 的主功能。

## 测试

沿用现有测试风格（`SessionRepositoryTest`、`TurnSignalParserTest` 那种纯函数 + 临时目录）：

- **三个探针的解析逻辑做成纯函数**：输入是配置文件内容字符串 + 二进制探测结果 map，输出是语言状态 map。直接喂字符串断言，不碰真实文件系统。
- **二进制探测抽成接口**，测试注入假实现返回预设的路径 map。真实 shell 调用只有一个实现类，不进单测。
- **合并优先级**要有用例：`settings.json` 与插件同时定义同一语言时，前者胜出。
- **带版本的包名**：`npm:pi-lens` / `npm:pi-lens@4.0.1` 都要能识别。
- **静态语言表**要有一致性测试：每条记录的 `claudePlugin` 必须能在 marketplace 清单里找到（防止上游改名后表失效而无人察觉）。

## 明确不做

- **不代替用户执行任何安装命令。** 只给可复制的命令。往用户机器上装东西是另一个量级的承诺（跨平台、网络、权限、失败回滚），且与 imux 的克制定位冲突。
- **不修改任何 CLI 的配置文件。** README 承诺的"一个字节都不往里写"继续成立。
- **不探测项目语言。** 报告是全局覆盖度，与当前项目无关。
- **不做启动时自动扫描、不弹通知。** imux 已有轮次完成提醒，再加一类噪音不划算。用户想看时自己打开设置页。
- **不自己实现 LSP 服务端，不把 IDE 的 PSI 包装成 language server。** 曾经考虑过，但那会让 imux 从"视图"变成"运行时"，与项目定位不符。
