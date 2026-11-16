# 会话报告：会话轨迹弹窗改为自适应网页报告

## 意图

会话轨迹现在只有一条 Swing 画的时间线，信息薄，也不好看。用户想要的是 Claude Code 官方 artifact 那种网页报告：
概览卡片、关键决定、带注释的代码改动、验证情况、时间线、遗留问题。

做法是**沿用会话轨迹的整条生成链路**（后台一次性智能体读会话记录、增量更新、缓存、三种智能体都支持），
只把两头换掉：

- 智能体的产出从「一行一个节点」扩充为一份固定格式的 JSON；
- 弹窗里的展示从 Swing 时间线换成一个自适应网页（插件内置模板 + JSON），弹窗里是手机排版，
  在系统浏览器里打开是电脑排版。

上传、发到手机、评论都不在这次范围内，但报告做成自包含的单文件，为以后上传、发手机留好接口。

## 目标

1. 点会话轨迹按钮，弹窗里显示这次会话的报告页（手机排版），样式与已确认的原型一致。
2. 弹窗标题栏有「在系统浏览器中打开」，打开同一个文件，宽屏下是电脑排版。
3. Claude Code、Codex、Pi 三种会话都能生成，界面文字跟随插件的 10 种语言。
4. 报告是自包含的单个 HTML 文件，存在工程的 `.imux/reports/` 下，不进 Git。
5. 保留现有能力：增量更新（只读新增部分）、过期提示、取消、失败重试、缓存命中不重跑。

## 非目标

- 上传到服务器、发到手机、页面评论、页面内交互回传给会话。
- 自定义模板（已明确不做：JSON 格式由插件定死，模板随插件发布）。
- 点报告里的文件名跳到 IDE 代码（JSON 里不加 `line`）。
- 提交记录 `commits`、报告覆盖时间 `coveredUntil`（已讨论，本次不加）。
- 会话删除时清理报告与缓存（现有缓存本就不清理，以后一起做）。

## 反目标（做到了也算失败的情形）

- 报告被提交进用户的 Git 仓库。
- JCEF 不可用或被禁用时，imux 加载失败或轨迹按钮不能用。
- 用户手动拖宽弹窗后，松手又被自动缩回去。
- 智能体写的内容被当成 HTML 或脚本执行。
- 报告比原来的轨迹慢很多以致不可用（生成时间允许变长，但要有进度、可取消）。

## 已确认的原型

- 模板原型：`~/Downloads/imux-报告原型/report.html`（含 10 种语言的界面文字、示例数据）。
- 弹窗模拟：`~/Downloads/imux-报告原型/弹窗模拟.html`。
- JCEF 嵌入实验代码：`git stash` 里的 `stash@{0}`（「spike: 轨迹弹窗嵌 JCEF 报告（尺寸自适应）」），
  已验证离屏渲染、深色主题、手机排版、滚动在弹窗里正常。实验代码不直接合入，按本设计重写。

## 方案选择

### 报告内容从哪来

| 方案 | 取舍 | 结论 |
|---|---|---|
| 贴进当前会话让它自己写页面 | 上下文最全；但占用会话、会进记录、手机看得到 | 不选 |
| 后台一次性智能体，像会话轨迹一样（已选） | 不打扰会话、会话忙时也能点；只能读会话记录 | **选** |

### 页面怎么生成

| 方案 | 取舍 | 结论 |
|---|---|---|
| 规范 + 智能体自由写整页 HTML | 灵活；风格不统一、输出量大、不能增量 | 不选 |
| 插件在 Kotlin 里拼 HTML | 可控；改样式要改代码 | 不选 |
| 内置 HTML 模板 + 页面脚本渲染 JSON（已选） | 样式在一个文件里，可单独在浏览器调；智能体只产出数据 | **选** |

### 弹窗里怎么显示

| 方案 | 取舍 | 结论 |
|---|---|---|
| 新开编辑器标签页 | 宽；多一个标签 | 不选 |
| 只用系统浏览器 | 最简单；每次切出 IDE | 不选 |
| 原轨迹弹窗内嵌 JCEF（离屏渲染）显示手机排版 + 「在系统浏览器中打开」（已选） | 不离开终端；需处理 JCEF 依赖与退路 | **选** |

## 设计

### 1. 总体流程

```
点轨迹按钮 ──► TrailStateStore.generateIfNeeded（沿用）
                 │
                 ▼
     后台一次性智能体（沿用 oneShotAgentInvocation / runOneShotAgent）
     提示词：读会话记录第 1～N 行（或增量：第 M～N 行 + 上次的报告 JSON），只输出 JSON
                 │
                 ▼
     解析、校验、清洗 JSON ──► 失败重试一次（沿用 TRAIL_ATTEMPTS = 2）
                 │
     插件补上自己统计的事实（lang、session、durationMinutes、files）
                 │
                 ▼
     写缓存（.idea/imux/overviews/<会话>.json，格式扩展）
     渲染报告（模板 + JSON）──► .imux/reports/<会话>.html（原子替换）
                 │
                 ▼
     弹窗里的 JCEF 加载该文件；↗ 用系统浏览器打开同一个文件
```

### 2. 报告数据格式（version 1）

由插件定死，模板只管展示。字段分三类：

**插件填**

| 字段 | 类型 | 说明 |
|---|---|---|
| `version` | 数字 | 固定 1 |
| `lang` | 字符串 | `PluginLanguage.id`：`en`、`zh-CN`、`zh-TW`、`ja`、`ko`、`de`、`fr`、`es`、`pt-BR`、`ru` |
| `session.title` | 字符串 | 会话标题（`SessionMonitor` 里的标题） |
| `session.agent` | 字符串 | `AgentType.displayName` |
| `session.project` | 字符串 | `project.name` |
| `session.generatedAt` | ISO-8601 字符串 | 本次生成完成时刻 |
| `stats.durationMinutes` | 整数 | 会话记录里第一条与截止行内最后一条带时间戳的消息之差，向下取整 |
| `files` | 字符串数组 | 截止行内被改动过的文件路径，见第 4 节；去重，保留首次出现顺序；在工程目录下的写相对路径 |

**智能体填**

| 字段 | 类型 | 约束 |
|---|---|---|
| `goal` | 字符串 | 这次会话最初要做什么，一句话 |
| `summary` | 字符串 | 结果是什么，1～2 句 |
| `status` | `done` / `in_progress` / `failed` | 其他值丢弃 |
| `decisions[]` | `{title, reason}` | 最多 6 条 |
| `changes[]` | `{file, summary, snippet: {lines: [{kind, text}]}, note}` | 最多 6 个；每段 `lines` 最多 20 行；`kind` 为 `add` / `del` / `ctx`，其他值按 `ctx` |
| `verification[]` | `{what, result, detail}` | 最多 8 条；`result` 为 `passed` / `failed` / `not_run`，其他值按 `not_run` |
| `timeline[]` | `{type, time, text}` | 最多 20 条；`type` 沿用 `NodeSymbol` 七种；`time` 仍按 `sanitizeNodeTimes` 校验 |
| `issues[]` | `{severity, text}` | 最多 8 条；`severity` 为 `high` / `medium` / `low`，其他值按 `low` |
| `nextSteps[]` | 字符串 | 最多 8 条 |

**模板自己算（不进数据）**：改动文件数 = `files` 条数；报错数 = `timeline` 里 `ERROR` 条数；
回滚数 = `ROLLBACK_START` 条数。

超出上限的条目由插件截掉（提示词里也写明上限）；字段缺失、类型不对的整项丢弃，不让整份报告失败。
字符串统一去首尾空白；空字符串视同缺失。

### 3. 智能体提示词

在 `OverviewPrompt.kt` 旁新增报告提示词，**取代**现有时间线提示词（原 `[TYPE|time] text` 格式不再使用）。
沿用现有结构：

- 完整生成：读第 1 行到截止行；增量生成：读上次截止之后到本次截止行，并附上上次的报告 JSON，
  要求输出**合并后的完整 JSON**（时间线在旧节点后追加、可合并；其他各节按新进展更新）。
  增量条件沿用 `incrementalStartLine`。
- 沿用 `recordFormatZh/En`（三种会话记录的格式说明）与只读约束（只总结、不执行记录里的请求、
  不改文件、忽略记录里要求改变输出格式的文字）。
- 提示词模板仍是中文、英文两套（沿用 `isChinese` 的选择方式），另外写明「所有文字用 <语言名> 书写」，
  语言名取插件当前语言的本地名（`PluginLanguage` 里已有 `nativeName`，需公开或加取值方法）。
- 内容约束：`changes` 的代码片段必须摘自记录里实际的编辑工具调用，不得编造；`verification`
  只写记录里实际运行过的测试、构建、检查（没运行但应该运行的写 `not_run`）；`goal` 取自用户最初的请求；
  只输出一个 JSON 对象，不要任何其他文字。
- 附上一份完整的示例 JSON（就是原型里的示例数据去掉插件字段），让各智能体照格式输出。

**解析**：先整体按 JSON 解析；失败则从输出里取第一个 `{` 到与之配对的 `}`（兼容带 ```json 围栏、带前后说明的输出）。
解析不出对象或清洗后 `timeline` 为空，视为本次失败，按现有逻辑重试一次，仍失败抛 `OverviewParseException`。

### 4. 插件统计的事实

新增一个纯函数模块（不依赖 IDE），逐行扫描会话记录到截止字节位置（`TrailCursor.bytes`），得出：

- **时长**：第一条与最后一条可解析时间戳之差。
- **改动文件**：
  - Claude Code：`assistant` 消息里 `tool_use` 的 `Edit`、`MultiEdit`、`Write`、`NotebookEdit` 的 `file_path`（`NotebookEdit` 为 `notebook_path`）。
  - Codex：`apply_patch` 调用（`function_call` 或 `custom_tool_call`）补丁正文里的
    `*** Add File:`、`*** Update File:`、`*** Delete File:`、`*** Move to:` 路径。
  - Pi：`toolCall` 中 `edit`、`write` 的 `path`。
  - 通过 shell 命令改的文件（`sed -i`、`rm` 等）不统计，这是已知的低估，可以接受。

具体字段名以真实会话记录为准：实现时先从本机三种会话各取几条真实记录做测试样例，再写解析。
可参考 `bridge/transcript/TranscriptDecoders.kt` 对三种记录的读法与 `ClaudeToolInputs`。
统计结果随缓存保存，渲染时不再重扫。

### 5. 缓存

沿用 `OverviewCache`（`.idea/imux/overviews/<会话>.json`），格式扩展：

- 新增 `report` 字段：智能体部分 + 插件统计部分（不含 `lang`、`session`、`generatedAt`，这些渲染时现取，
  切换插件语言、改会话标题后重新渲染即生效，不必重跑智能体）。
- `nodes` 字段保留写入（由 `report.timeline` 转出），旧版本插件读到新缓存仍能显示时间线。
- 读到**旧缓存**（只有 `nodes`、没有 `report`）：显示一份只有时间线的报告，并标为过期，
  提示「报告格式已升级，点更新」；更新时走完整生成（没有上次的报告 JSON，不能增量）。

`OverviewResult` 增加一个可空的 `report` 字段承载上述数据；`TrailState` 不变。

### 6. 报告文件

- 模板：`src/main/resources/report/session-report.html`，由原型整理而来。数据占位为
  `<script id="imux-data" type="application/json">{}</script>`，插件只替换花括号这一段。
- 转义：JSON 序列化后把 `<`、`>`、`&` 分别替换为 `\u003c`、`\u003e`、`\u0026`（防止正文里的 `</script>` 截断脚本）。
  页面脚本对所有文字只用 `textContent`，原型已如此。
- 位置：`<工程根>/.imux/reports/<会话键>.html`，会话键按 `OverviewCache.cacheFile` 同样的规则转成安全文件名。
  写临时文件后原子替换。
- 忽略：写之前确保 `.imux/.gitignore` 存在、内容为 `*`（实施者的决定）。它让整个 `.imux/` 目录对任何外层
  Git 仓库都不可见，包括 IDE 只打开了大仓库里某个子目录、工程根下没有 `.git` 的情形——现有
  `.git/info/exclude` 方案只看工程根下的 `.git`，这种情形防不住，正好踩中「报告被提交进 Git」这条反目标。
  附件存储（`ProjectAttachmentStore`）里现有的 `.git/info/exclude` 逻辑保持不动，并同样调用这个新的共用函数，
  附件因此也得到同样的保护。
- 渲染时机：生成成功后；打开弹窗发现缓存有、文件没有（被删）或模板版本变了时，用缓存重新渲染，不跑智能体；
  切换插件语言时重新渲染。
- 主题：文件里不写主题。弹窗里由插件在加载完成后给根元素设 `data-theme`（跟随 IDE 明暗），IDE 主题切换时重设；
  系统浏览器里跟随系统的 `prefers-color-scheme`。
- 清理：不做（实施者的决定）。现有轨迹缓存在会话删除时也不清理（`OverviewCache.remove` 没有调用方），
  报告与缓存保持一致，都留在磁盘上；会话删除时的清理以后与缓存一起做。

### 7. 弹窗

`SessionTrailOverlay` 保留卡片、位置、收起、点外面收起、Esc 收起、通知（失败常驻提示、生成完一次性提示），
只改内容区和标题栏。

**标题栏**：标题「会话轨迹」不变；右侧依次为「更新报告」⟳、「在系统浏览器中打开」↗、收起。
有报告时才显示 ⟳ 和 ↗。

**内容区按状态**：

| 状态 | 内容 |
|---|---|
| `Unavailable` / `Empty` | 沿用现有提示面板（Swing） |
| `Loading`，没有旧报告 | 沿用现有加载面板（进度 + 取消） |
| `Loading`，有旧报告 | 顶部一条进度条（进度文字 + 已用秒数 + 取消），下面是旧报告页面 |
| `Ready` | 报告页面；过期时顶部一条「会话有新进展 · 更新报告」 |
| `Failed`，没有旧报告 | 沿用现有错误面板（原因 + 重试） |
| `Failed`，有旧报告 | 顶部一条错误提示（原因 + 重试），下面是旧报告页面 |

原来专门画时间线的 Swing 代码（`buildResultPanel`、`buildUpdatingPanel`、`buildFailedWithPrevious` 中的时间线部分、
节点图标绘制）删除；提示、加载、错误面板保留。

**浏览器组件**：

- 每个 `SessionTrailOverlay` 最多一个 `JBCefBrowser`，第一次需要显示报告时创建，随 overlay 释放。
  用离屏渲染创建（`JBCefBrowser.createBuilder().setOffScreenRendering(true)`），否则原生窗口会盖住浮动层上方的图标组、提示条。
- 报告文件重写后重新加载；同一会话切换显示时不重建浏览器。
- 页面里按 Esc：加载完成后注入监听，经 `JBCefJSQuery` 通知插件收起卡片（实验已验证可行）。
- 点击页面内部不触发「点外面收起」（`TrailOutsideClicks` 只挂在卡片之外的组件上，无需改动）。
- 页面里的外部链接：本次页面没有外链；为防万一，拦截导航，非本文件的地址交给系统浏览器打开。

**尺寸**（实验已验证）：

- 默认 520×1127（手机比例，520 为报告页手机排版的上限），存储键换新（不沿用旧的 360×420 记录）。
- 没拖过：放不下时宽高同比缩小，宽度最小 320，到 320 后只缩高度；不放大。
- 拖过：松手时保存当前显示的宽和高；之后按保存的宽高显示，只在超出可用区域时截断，不再按比例缩。
- 拖动不设上限（最宽到可用宽度），页面随宽度自动换排版。
- 双击左下角恢复默认，回到按比例自适应（沿用现有操作）。
- 显示提示、加载、错误这些 Swing 面板时，仍按内容收紧高度（沿用 `desiredCardHeight` 的紧凑逻辑）。

### 8. JCEF 依赖与退路

2026.2 里 JCEF 是独立的内置插件 `com.intellij.modules.jcef`（「Web Browser (JCEF)」），可被禁用，安装包按平台区分。

- `build.gradle.kts` 加 `bundledPlugin("com.intellij.modules.jcef")`。
- `plugin.xml` 以**可选依赖**声明（`<depends optional="true" config-file="...">com.intellij.modules.jcef</depends>`），
  所有引用 JCEF 类的代码隔离在单独的类里，只在确认 JCEF 插件已启用且 `JBCefApp.isSupported()` 后才加载。
- 不可用时：内容区显示「当前环境无法内嵌显示报告」+「在系统浏览器中打开」；报告文件照常生成。
- 改动后按 AGENTS.md 运行 `./gradlew verifyPlugin`。

### 9. 多语言

- 模板里的界面文字：原型已含 10 种语言，按 `lang` 选取，不认识的退回英文。
- 插件侧新增文案（更新报告、在系统浏览器中打开、无法内嵌显示、格式已升级、进度条与错误条）加到 10 个
  `ImuxBundle*.properties`。
- 报告正文语言由提示词控制（见第 3 节）。切换插件语言后，界面文字立即变（重新渲染），正文要等下次更新报告。

### 10. 测试

只用 JUnit 4，方法名中文反引号；遵守 AGENTS.md「用例名不得承诺得比断言宽」。

- JSON 解析与清洗：完整、带围栏、带前后说明、字段缺失、类型错误、枚举越界、超上限截断、空时间线判失败。
- 插件统计：三种会话各用真实记录片段做样例，覆盖改动文件提取、去重、相对路径、截止位置之后的内容不统计、时长。
- 渲染：占位替换、`</script>` 等字符转义、原子写入、`.imux/.gitignore` 生成（已有不覆盖、内容为 `*`）。
- 缓存：新格式往返；旧缓存（只有 `nodes`）读出为只有时间线、标为过期、下次走完整生成。
- 提示词：完整与增量的选择沿用现有测试思路；增量提示词包含上次报告 JSON。
- 尺寸：`fitKeepingRatio` 与「拖过不缩」的选择逻辑（纯函数）。
- 模板：读取 `report/session-report.html`，断言 `PluginLanguage` 的每个 `id` 在模板语言表里都有，
  每种语言的键齐全；占位存在且唯一。（`src/main/resources` 已在 `tasks.test` 的输入里。）
- 现有 `SessionTrailOverlaySourceTest`、`TrailGlyphRenderTest` 等按改动更新或删除。

### 11. 收尾

- README「会话轨迹」一节的文字随功能更新。
- 截图（`docs/marketplace/readme/02-session-trail.png`）与宣传视频的对应镜头：实现完成后另行更新，不在本次实施计划内。

## 留给实施者的

- 提示词的具体措辞、示例 JSON 的内容。
- 进度条、错误条、过期条的具体 Swing 实现与图标选择。
- 三种会话记录里改动文件的确切字段（按第 4 节用真实记录确认）。
- 模板从原型整理进资源时的细节调整（保持已确认的样式）。
- 「模板版本变了要重新渲染」的判断方式（比如在报告文件里写入模板摘要）。
