# CLI LSP 体检 —— 已知遗留项

功能已合并（`e7d3a6b`..`034d3b7`，13 个提交）。以下是执行过程中被逐层审查发现、经裁定**不阻塞合并**的遗留项，按建议处理顺序排列。每条都附了发现它的理由和修法，可以直接开工。

设计文档：`docs/superpowers/specs/2026-08-20-cli-lsp-diagnostics-design.md`
实现计划：`docs/superpowers/plans/2026-08-20-cli-lsp-diagnostics.md`

## 1. `readText()` 先于 `waitFor()`，超时仍非硬上界

`lsp/BinaryProbe.kt`

最终审查已修掉一类死锁（stderr 管道无人读取 → 写满 64KB 后子进程阻塞 → stdout 永不关闭）。但**结构性问题原样保留**：`readText()` 要求 stdout 写端的**所有持有者**都关闭才返回 EOF。

登录 shell 的 rc 里凡是 `&` 后台起的常驻进程——`ssh-agent`、`gpg-agent`、powerlevel10k 的 `gitstatusd`、`zsh-async` worker、`nohup ... &`——只要没自己重定向 fd 1，就会在 shell 退出后继续持有写端，`readText()` 永久阻塞，`waitFor(timeout)` 那行依然执行不到。

后果与原缺陷完全相同：页面永久停在「正在检测…」→ 按钮永久禁用（重新启用写在那个永不执行的回调里）→ 用户重开设置页触发 `createPanel().also { refresh() }` → 再泄漏一个登录 shell，可无限累积。

**这条与第 4 条（`invokeLater` 缺 `Disposable`）是同一条故障链，建议一起修。**

修法：把 stdout 的读放到独立线程并 `Future.get(timeout)`；或先 `waitFor(timeout)`，超时后 `destroyForcibly()` 再读已缓冲的内容。

## 2. 两个断言盲区：回归防线上的洞

`lsp/BinaryProbeTest.kt`、`settings/ImuxLspUiSourceTest.kt`

两条都不是当前缺陷，而是**现有断言拦不住的复发路径**。

**`Redirect.PIPE`**：`stderr 必须丢弃…` 那条只挡了 `redirectErrorStream(false/true)`，没挡 `.redirectError(ProcessBuilder.Redirect.PIPE)`——那是 `redirectErrorStream(false)` 的逐字等价物，也是「我想把 stderr 抓下来一起写进日志」这个念头的自然写法。而这次修复恰恰把方向推向「多记日志」，所以复发路径不是假想。补 `assertFalse(body.contains("Redirect.PIPE"))` 即可。

**`.align(...)`**（更隐蔽，来自平台内部逻辑）：`CellImpl.align(Align)` 的实现是

```
if (component is DslLabel && component.maxLineLength == -1)
    component.limitPreferredSize = (horizontalAlign == FILL)
```

而 `DslLabel.getPreferredSize()` 只在 `maxLineLength == -1 && limitPreferredSize` 时才把宽度压到 70 列。`RowImpl.text()` 之所以能折行，正是因为它在 `maxLineLength == -1` 时自动补了一次 `.align(AlignX.FILL)`。

于是 `text(readySummary(ready)).align(AlignX.LEFT)` 会把 `limitPreferredSize` 覆写成 `false`，**撑宽对话框的缺陷 100% 复活**，而两条断言全绿。改的人完全不会意识到自己关掉了折行——他只是嫌 FILL 的 editor pane 跟图标挨得不好看。

补 `assertFalse(source.contains("text(readySummary(ready)).align"))` 一类挡板。

## 3. 组级提示的前景色被降级

`settings/ImuxLspConfigurable.kt`

组级提示改 `comment()` 后前景色由正常变灰。但这一行配的是 `AllIcons.General.Warning`，紧跟其后就是可复制的安装命令，是全页**最需要用户执行动作**的一行；而同一次提交里，正面的 ready 汇总反而因为「灰字会把结论降级成脚注」选了 `text()`。同一把尺子量出了相反结论。

字节码上 `text()` 与 `comment()` 走同一个 `DslLabel`、同样的 `maxLineLength=-1 → AlignX.FILL → limitPreferredSize=true`，**折行效果完全一致**。改成 `text(groupMessage(cliReport))` 能在不丢折行的前提下保住前景色。

## 4. `invokeLater` 回调缺 `Disposable` / `expired` 条件

`settings/ImuxLspConfigurable.kt`

设置页在探测返回前被关闭时，回调照常执行，对已脱离容器的面板做 `removeAll` / `revalidate` / `setEnabled`——纯 Swing 下是 no-op，不抛异常，所以单独看不算缺陷。但它与第 1 条组成同一条故障链：探测挂死时这个回调永远悬着。修第 1 条时一并挂上 `BoundConfigurable` 自带的 disposable。

## 5. 日志的 KDoc 承诺与实现不符

`lsp/LspDiagnostics.kt`、`lsp/ClaudeCodeLspProbe.kt`、`lsp/PiLspProbe.kt`

message 字符串本身是干净的（只有硬编码文件名），**探测到的绝对路径也从未进入任何日志**——这是最要紧的一条，没问题。问题出在把 `it` 传给 `LOG.warn` 第二参数时，异常自带的信息越过了 KDoc 的承诺：

- `LspDiagnostics` 的 KDoc 写「只写相对路径」，但 `NoSuchFileException` / `AccessDeniedException` 带的是**绝对路径**（含用户名），而无权限正是这条 warn 的主要触发场景
- 两个解析器的 KDoc 写「不写文件内容」，但配置文件顶层退化成 JSON 标量时，Gson 会把内容原样嵌进异常消息（实测 `Not a JSON Object: "sk-ant-..."`）。触发面窄，但正中「配置里可能有令牌」这个担心

烈度低（`idea.log` 本来就满是用户路径），但声明与实现不符应当消除：要么把 KDoc 改成「不打完整文件内容」，要么 Gson 那两处不传 `it`、改记 `it.javaClass.simpleName`。

正常语法错误路径是安全的——`MalformedJsonException` 只给 `line/column/path $.apiKey`，有键名无值。

## 6. `groupMessage` 用 `else ->` 兜底

`settings/ImuxLspConfigurable.kt`

今天不是缺陷（三个枚举值，CLAUDE 到不了这里）。但**这正是酿成过 Critical 的那个形状**——加第四个 CLI 时会静默继承「pi-lens 未安装」这句话，而 `when` 的穷尽性检查因为有 `else` 不会报错。

加重情节：`ImuxLspUiSourceTest` 有一条断言把这个脆弱形状**钉死了**——改成穷尽形式（`AgentType.PI ->` + `CLAUDE -> error(...)`）会让那条测试变红。正确的修法现在有摩擦成本，错误的形状被固化。**修时必须同时改断言。**

## 7. 源码级断言对格式敏感

`settings/ImuxLspUiSourceTest.kt`

该文件有多条逐字符字面量断言，含具体空白与换行。任何一次重排版会同时打红多个测试。建议统一改成对规范化后的源码匹配：

```kotlin
private val normalized by lazy { source.replace(Regex("\\s+"), " ") }
```

守卫强度不变，但对格式化免疫。

## 8. `tomlSectionContains` 对行内注释误判

`lsp/TomlSectionScanner.kt`

`command = "other" # was pi-lens-mcp` 会被判为已挂载，于是**不给** `codex mcp add` 建议——而那正是 Codex 用户最高价值的输出。触发需要用户在 `[mcp_servers.*]` 段内写一条恰好提到 `pi-lens-mcp` 的行内注释，概率极低，且失败模式是少给一条建议（软失败）而非说假话。

修法：`substringAfter('=')` 后先剥离未被引号包裹的 `#` 及其后内容；内联表分支加 `inSection` 约束。现有纯函数测试夹具直接能覆盖。

顺带把 KDoc 的「已知不支持」补全为「多行数组、多行字符串、行内注释、非顶层的 `mcp_servers.` 前缀」。

## 9. `UNKNOWN` 在聚合计数上被抹平

`lsp/LspReport.kt`

四态语义从探针到逐行渲染全程未被混淆，逐行文案也正确（UNKNOWN 显示「无法确定」且不给假建议）。但 `CliReport.gaps = findings.filter { it.status != READY }` 把 UNKNOWN 并进了「待补充（n）」并配警告图标。

探测超时 + 装了 pi-lens 的场景下，用户看到「待补充（11）」配警告，而真相是「11 项都没查成」。逐行说真话，只有表头计数和图标在说「缺失」。

修法：UNKNOWN 单独分一档，或让表头在 gaps 全为 UNKNOWN 时改用「无法确定」语义。

## 10. 安装命令只有 macOS 一版

`lsp/LspCatalog.kt`

设计文档第 153 行原文是「`installCommands` 按平台分（macOS / Linux / Windows）…取当前平台那条展示」，实现只做了一版。Windows 用户会看到 `brew install llvm`。

实现计划里称「这是 spec 认可的取舍」——**核对后 spec 并没有认可**，那是一句失实的追认。`LspCatalog` 的 KDoc 把取舍与迁移路径写清楚了，纯数据改动、调用点不变，实质影响可控。但应在文档上把偏离显式记为决策，别让下一个人以为两边一致。

**「激活 / 安装」按钮上线后，这条的性质变了。** 命令不再只是显示给人复制，而是点一下直接执行，所以 `RemedyKind.INSTALL` 的执行按钮只在 macOS 出现（`lsp/LspRemedyRun.kt` 的 `canRun`）。补齐平台分版之后，`canRun` 里 `|| isMac` 这一半应随之放开——否则 Linux 用户明明有了对的命令，按钮却还是不给。

## 10.1 `resolveShell` 在 Windows 上退回 `/bin/zsh`，而只有 LSP 页挡了这一道

`terminal/AgentCommand.kt` 的 `resolveShell`（根），四个调用点

```kotlin
internal fun resolveShell(shellEnv: String?): String = shellEnv?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
```

Windows 上 `SHELL` 通常没有值 → 退回 `/bin/zsh` → 拿它去 `ProcessBuilder` / `shellCommand` 一律是 `Cannot run program /bin/zsh`。而 `-l -i -c` 这套参数本身也只有 POSIX shell 认。**这是根，不在 LSP 那一侧。**

四个调用点，只有最后一个挡了：

| 调用点 | Windows 上的表现 | 挡了吗 |
| --- | --- | --- |
| `terminal/TerminalHost.kt:555` `newCommand` | 会话标签起不来 | 否 |
| `terminal/TerminalHost.kt:567` `resumeCommand` | 同上 | 否 |
| `lsp/BinaryProbe.kt:71` | 探测失败 → 全部语言落到 `UNKNOWN`（语义上恰好是对的：「没查出来」） | 否，但降级无害 |
| `settings/ImuxLspConfigurable.kt` 执行按钮 | —— | **是**（`canRun` 的 `hasPosixShell`） |

**这个偏斜本身要记一笔，否则下一个人会以为全项目都挡了。** 只有 LSP 页挡，是因为只有它**在 Windows 上本来是能用的**：`[复制]` 加文档链接是一份完整产出，旁边多一个点下去报错的按钮，是拿能用的换不能用的。会话启动在 Windows 上从来没能用过，多一个坏入口用户什么也没失去——那不是同一件事，所以不该顺手一起「修」成同一个形状。

真正的修法是给 `resolveShell` 加 Windows 分支（PowerShell / cmd，参数不再是 `-l -i -c`，`singleQuote` 的转义规则也要跟着换）。做完之后，`canRun` 的 `hasPosixShell` 维度应随之去掉。

**别只改 `canRun` 的调用点**——闸门语义住在纯函数里，是这一层唯一被真调用测试钉住的东西。同理，`canRun` 的三个维度是分开的：目录表补齐平台分版之后该放开的是 `|| isMac` 那一半，不是 `hasPosixShell`。

## 11. 缺两处承重契约的测试

- **`ShellBinaryProbe.locate()` 的失败分支零测试**。不需要真实 shell 也能测两条：`locate(emptySet())` 应返回空映射；`ShellBinaryProbe(shell = "/nonexistent").locate(setOf("x"))` 应走异常分支返回空映射。各 2 行、确定性、无新依赖。而「失败/超时 → 空映射」正是整条 UNKNOWN 语义的承重契约——现在只测了下游怎么消费空映射，没测上游会不会真的产出空映射。
- **「只读」这个头号承诺没有自动化守卫**。已人工逐条核验属实（`src/main/**` 里只有 `Files.readString` / `Files.isRegularFile`，唯一的 `ProcessBuilder` 跑 `printf` + `command -v`，无任何 write/mkdir/delete），但代码里没有东西拦住下一次修改。`LspDiagnosticsTest` 已有 `TemporaryFolder` 夹具，加一条「`run()` 前后对临时目录做文件树快照并断言相等」即可机检。

## 12. `LspCatalog` displayName 的 HTML 安全

`lsp/LspCatalogTest.kt`

`text()` 产出 `JEditorPane`，入参按 HTML 解析。当前 13 个 displayName（含 `C++` / `C#` / `TypeScript/JavaScript`）与 10 个语种的 `settings.lsp.ready` 均不含 `<` `>` `&`，且这两个组件的入参**全部来自本仓库静态表 + bundle，从不来自用户数据**，所以是「未来有人往静态表里加 `<` 才会踩」的风险。

但踩到时是**静默丢字**而非崩溃（未命中禁用标签的 `<...>` 被 HTML 解析器吞掉），更难发现。加一行断言所有 displayName 不含 `<>&` 即可。

## 13. Claude 侧按二进制名而非语言匹配

`lsp/ClaudeCodeLspProbe.kt`

`claudeReport` 判定 `language.claudeBinary in configuredCommands`。两个反向误判各存一例，都要求用户使用非标准 command 名，概率低：

- 用户用自定义 command 名配了 Go（`{"lspServers":{"gopls":{"command":"my-gopls"}}}`）→ 集合里只有 `my-gopls` → Go 判为 MISSING_CONFIG → 劝用户装一个其实不需要的插件
- 插件启用 + settings 覆盖成别的 command → 两个名字都进集合 → 按 `gopls` 是否在 PATH 判 READY，而实际启动的是 `my-gopls`

顺带：设计文档「测试」一节要求的「合并优先级要有用例：settings.json 与插件同时定义同一语言时前者胜出」，在集合并集模型下失去了意义，因此也没有对应用例。**这是需求在实现中悄悄消解的一例**，不是遗漏测试，但值得在文档上对齐。

## 14. i18n 三处术语润色

- **术语漂移**：同一概念 es 用 `complemento`、fr 用 `extension`，其余 8 种语言都用 plugin/плагин/プラグイン/插件。而配套的命令字面量是 `claude plugin install …`，本地化会削弱与 CLI 子命令的对应关系。建议 es/fr 保留 `plugin`
- **计数表头用单数形容词**：`gaps` / `ready` 后跟计数，es `Faltante/Disponible`、fr `Manquant/Disponible`、pt `Ausente/Disponível`、ru `Отсутствует` 都是单数形式
- **ko 语义偏窄**：`gaps=미설정`（未配置）只覆盖 MISSING_BINARY 与 UNKNOWN 中的一种，建议改「누락」或「미비」

（法语的 MessageFormat 转义已核验完全正确：带 `{0}` 的用 `n''est`，无占位符的用单引号。zh_TW 是真本地化而非简繁机转。）

## 15. 两处代码整洁

- `ClaudeCodeLspProbe` 私有实现了一套 Gson 安全封装，而 `PiLspProbe` 用裸 `JsonParser` + `runCatching` 各写各的。两处都正确，但同一包内对同一件事有两套写法
- C 与 C++ 共用 `clangd-lsp`/`clangd`，UI 上会连续出现两条一模一样的 `claude plugin install clangd-lsp@claude-plugins-official`（各带一个复制按钮）。纯观感

---

# 全量列表改造的遗留（`812a637`..`27c2121`，6 轮修复）

体检页从「只显示有问题的语言」改成「每组全量列出 18 门语言」。功能已交付，以下是审查中发现、经裁定不阻塞的遗留项。

`ImuxLspUiSourceTest` 这个文件在这次改造里返工六轮，全部是同一类问题：**项目未引入平台 test-framework，UI 只能做源码文本断言，而文本断言挡不住「换一种写法达到同样效果」**。下面的 1–5 都是这个结构性约束的产物。

## 1. carve-out 按名字减而非按声明减（最值得先做，改动 = 三个字符串）

`ImuxLspUiSourceTest.kt` 约 417 行的 `Regex("""\b(?:val|var)\s+(\w+)\s*=\s*\1\s*[({<]""")` 从 `imported ∩ declared` 里减名字。问题在于它减的是**名字**：文件里任何一处出现 `val X = X(`，名字 `X` 就在**全文件范围**失去保护，而那处诱饵与真正的遮蔽声明可以毫不相干。

实测四种形态全部 GREEN（且在父提交 `c772a22` 上是 RED）：
- 攻击 `private val panel: (…) -> DialogPanel = { panel { } }`（整页空白）+ 别处一行局部 `val panel = panel { }`
- 同上，诱饵换成 `var`
- 同上，诱饵换成**纯字符串常量** `const val SHAPE_HINT = "val panel = panel(…)"` —— 连真绑定都不需要，`normalized` 不剥字符串
- 攻击 `private object JBUI`（高度封顶失效）+ 同款字符串诱饵

**最小硬化**：把 `panel`、`JBUI`、`RowLayout` 三个名字加进约 363 行那份「不适用 carve-out」的既有名单。**纯加三个字符串，不动任何机制。**

裁定不阻塞的理由：绕过需要额外写一行专为解除断言而存在的代码（无人使用的冗余绑定或常量），不符合这个文件立的标准——「函数体一字不改、看起来像正常代码」。

## 2. `compactArgs()` 会吃掉字符串字面量里的 `, 标识符=`

`label("a, b=c")` 会被归一成 `label("a,c")`。当前文件没有这种字面量，走 `compactArgs` 的断言比的也都是不含逗号的片段，**无活跃影响**，属潜伏项。

## 3. 反引号旁路两道正则网

`private fun \`minOf\`(...)` 与 `private val \`readyServerText\`: ...` 都能绕过白名单与定向检查——两个正则都用 `(\w+)` / `\Q$name\E` 紧跟关键字，反引号一挡就整条失配。而本项目**强制**用反引号命名测试方法，反引号是这个代码库的惯用语法。

修法：两处正则各加一个可选反引号。裁定不阻塞：需要维护者主动给一个普通声明加反引号，不是任何重构或 IDE 意图会产生的形态。

## 4. 同包另一个文件里的顶层声明

新建 `settings/LspShadow.kt` 放 `internal fun minOf(a: Int, b: Int): Int = a`，同包顶层优先于默认导入，测试完全盲。

结构性上限：该测试按设计只读一个文件。跨文件扫描是新的一层，误报面与维护成本跳一个量级；而新增文件在 PR 里肉眼可见，人工 review 这层能挡。

## 5. 逃逸清单是黑名单

`repeat(finding.status.ordinal.coerceAtMost(1)) { row { … } }` 不含任何被禁 token 也能让行消失。

**建议将来改写成链尾白名单**：`}` 之后只准出现 `{layout, topGap, bottomGap, gap, resizableRow, rowComment, customize}`。它对空白免疫、对行距微调友好，且与同文件「本文件只允许声明这些函数」用的是同一套论证（白名单而非黑名单）。这一改会把黑名单问题一并收掉。

## 6. 21 项函数白名单是本层单点

它是唯一挡得住「默认导入 / 顶层函数被遮蔽」的断言。删掉它，`String?.orEmpty`、`T .also`、`minOf` 三类立刻全线复活且其他断言毫无反应。**该被当成一条约束而不是一条断言来 review。**

## 7. 三层嵌套滚动的取舍

设置对话框自带滚动 + 三个内层 `JBScrollPane`。Swing 默认不做滚轮链式传递，内层滚到底后指针不移开就推不动外层。而 `MAX_VISIBLE_HEIGHT = 300`（约 12 行）相对 18 行只省 6 行，收益与嵌套代价不太成比例。真机确认后值得重新评估「干脆让整页滚」。

## 8. `:test` 的输入声明不能删

`build.gradle.kts` 里给 `test` 声明了 `src/main/{kotlin,resources,js}` 为输入。**删掉它，全仓库 11 个读源文件的测试类会静默退回假绿**——字节码等价的改动（删尾逗号、改注释、重排格式）会让 Gradle 判 `:test` 可复用。

已实测：修复前删尾逗号 → `:test FROM-CACHE`；修复后 → 真跑；不改任何东西 → 仍 `UP-TO-DATE`（增量没坏）。

代价是改任意一行注释都会重跑约 628 条测试（6~7 秒）。这是划算的交换，但要防着将来有人以「改注释也重跑」为由删掉它。

## 9. 天花板

`ImuxBundle.message(key)` 永远搬不出 UI 壳。这九轮补的都是**枚举**已知入口，不是**证明**。终局是引入平台 test-framework（`build.gradle.kts` 记了当初不引的原因：需从 JetBrains 仓库下载，本机网络不可达）。

## 方法论备忘

这次改造里实现者两次把「我修了什么」当成覆盖边界，共同点是**改完没在父提交上做反向对照**。第六轮起 harness 把上一轮的必红集整体作为回归集重跑，建议之后动这个文件的人保留这个习惯。

另一条来自实现者的启发式：**写完一条断言先问——这条断言失败时，用户看到的是什么？答不上来，它守的多半不是用户在乎的东西。**
