# 会话报告 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 会话轨迹弹窗改为显示自适应网页报告：后台智能体输出固定格式 JSON，插件用内置模板渲染成 `.imux/reports/<会话>.html`，弹窗内嵌 JCEF 显示手机排版，可在系统浏览器打开。

**Architecture:** 生成链路沿用 `ProgressOverviewService` + `TrailStateStore` + `OverviewCache`（一次性智能体、增量、缓存），只把产出从「一行一个节点」换成 JSON（`SessionReport`），并由插件补上扫描会话记录得出的事实。渲染是纯字符串替换：内置模板 + JSON → 单个 HTML 文件。显示层在 `SessionTrailOverlay` 内容区里放一个可选的 JCEF 视图，JCEF 不可用时退回「在系统浏览器中打开」。

**Tech Stack:** Kotlin、IntelliJ Platform 262（JCEF 为可选内置插件 `com.intellij.modules.jcef`）、Gson、JUnit 4。

**Spec:** `docs/superpowers/specs/2026-11-16-session-report-design.md`

## Global Constraints

- 只支持 IntelliJ Platform build 262 及以上，不加旧版兼容分支。
- 测试只用 JUnit 4，方法名中文反引号；用例名不得承诺得比断言宽（AGENTS.md）。
- 运行期读源文件 / 资源的测试复用 `SourceCode`，被读文件须在 `tasks.test` 的 `inputs` 里（`src/main/resources` 已在）。
- 改动平台依赖后运行 `./gradlew verifyPlugin`。
- 本机网络受限：Gradle 命令一律加 `--offline`。
- 报告数据 `version` 固定 1；条数上限：时间线 20、关键决定 6、关键改动 6（每段代码 20 行）、验证 8、遗留问题 8、下一步 8。
- 枚举取值（原样）：`status` = `done` / `in_progress` / `failed`；`kind` = `add` / `del` / `ctx`；`result` = `passed` / `failed` / `not_run`；`severity` = `high` / `medium` / `low`；时间线 `type` = `NodeSymbol` 七种名字。
- `lang` 取 `PluginLanguage.id`：`en`、`zh-CN`、`zh-TW`、`ja`、`ko`、`de`、`fr`、`es`、`pt-BR`、`ru`。
- 弹窗默认尺寸 520×1127（未缩放），最小宽 320；尺寸存储键 `imux.sessionReport.width` / `imux.sessionReport.height`。
- 报告文件：`<工程根>/.imux/reports/<安全会话键>.html`；`.imux/.gitignore` 内容为 `*`。
- 在功能分支 `feat/session-report` 上做（从 `main` 切出）。

## Review Focus

1. 智能体写进字符串里的 `</script>`、`<img onerror=…>` 之类 → 页面上原样显示为文字，不截断脚本、不执行（Task 4 测试）。
2. 旧版本留下的缓存（只有 `nodes`）→ 显示只有时间线的报告并标过期，更新时走完整生成而不是增量（Task 3 测试）。
3. 会话记录里没有任何可解析时间戳、或文件改动全在工程目录外 → 时长缺省不显示、路径保留绝对路径，不报错（Task 2 测试）。
4. IDE 打开的是大仓库的子目录（工程根下没有 `.git`）→ `.imux/` 仍被忽略（Task 4 测试：`.gitignore` 不依赖 `.git`）。
5. 智能体输出夹着说明文字或 ```json 围栏、或只输出了半截 JSON → 前者能解析，后者判失败并重试一次（Task 1 测试）。

---

### Task 1: 报告数据模型与 JSON 解析清洗

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/overview/report/SessionReport.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/overview/report/ReportJson.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/overview/report/ReportJsonTest.kt`

**Interfaces:**
- Produces（`SessionReport.kt`）：
  ```kotlin
  data class SessionReport(
      val goal: String? = null,
      val summary: String? = null,
      val status: ReportStatus? = null,
      val decisions: List<ReportDecision> = emptyList(),
      val changes: List<ReportChange> = emptyList(),
      val verification: List<ReportCheck> = emptyList(),
      val timeline: List<OverviewNode> = emptyList(),   // 复用现有 OverviewNode / NodeSymbol
      val issues: List<ReportIssue> = emptyList(),
      val nextSteps: List<String> = emptyList(),
      val facts: ReportFacts = ReportFacts(),
  )
  enum class ReportStatus(val wire: String) { DONE("done"), IN_PROGRESS("in_progress"), FAILED("failed") }
  data class ReportDecision(val title: String, val reason: String?)
  data class ReportChange(val file: String, val summary: String?, val lines: List<SnippetLine>, val note: String?)
  data class SnippetLine(val kind: LineKind, val text: String)
  enum class LineKind(val wire: String) { ADD("add"), DEL("del"), CTX("ctx") }
  data class ReportCheck(val what: String, val result: CheckResult, val detail: String?)
  enum class CheckResult(val wire: String) { PASSED("passed"), FAILED("failed"), NOT_RUN("not_run") }
  data class ReportIssue(val severity: Severity, val text: String)
  enum class Severity(val wire: String) { HIGH("high"), MEDIUM("medium"), LOW("low") }
  /** 插件扫描会话记录得出的事实。 */
  data class ReportFacts(val durationMinutes: Long? = null, val files: List<String> = emptyList())
  ```
- Produces（`ReportJson.kt`）：
  - `internal fun parseAgentReport(output: String): SessionReport?` —— 解析智能体输出；清洗后 `timeline` 为空返回 null。`facts` 保持默认。
  - `internal fun agentReportJson(report: SessionReport): JsonObject` —— 只含智能体字段（goal…nextSteps），供增量提示词和缓存用；时间线节点写 `{type, time?, text}`，`time` 为 ISO-8601。
  - `internal fun reportFromJson(obj: JsonObject): SessionReport?` —— `agentReportJson` 的逆，同样走清洗；供缓存读取。
  - `internal fun factsJson(facts: ReportFacts): JsonObject` / `internal fun factsFromJson(obj: JsonObject?): ReportFacts`。
  - 常量：`MAX_TIMELINE = 20`、`MAX_DECISIONS = 6`、`MAX_CHANGES = 6`、`MAX_SNIPPET_LINES = 20`、`MAX_CHECKS = 8`、`MAX_ISSUES = 8`、`MAX_NEXT_STEPS = 8`。

- [ ] **Step 1: 写失败的测试** `ReportJsonTest`，至少这些用例：
  - `` `完整 JSON 解析出各节` ``：给一份含全部字段的输出，断言 goal、summary、status=IN_PROGRESS、decisions[0].title、changes[0].lines[1].kind=ADD、verification[1].result=NOT_RUN、timeline 数量与第 3 条 symbol=CHANGE、issues[0].severity=HIGH、nextSteps 数量。
  - `` `围栏和前后说明文字之间的 JSON 能取出` ``：输入 ``"好的，报告如下：\n```json\n{…}\n```\n以上"``，断言解析非 null 且 goal 相同。
  - `` `半截 JSON 解析失败返回 null` ``。
  - `` `时间线为空视为失败` ``：其他字段齐全、`timeline: []` → null。
  - `` `枚举越界按约定兜底` ``：status=`"wip"` → null；kind=`"x"` → CTX；result=`"ok"` → NOT_RUN；severity=`"urgent"` → LOW；timeline type=`"FOO"` → MAIN。
  - `` `超出上限的条目被截掉` ``：timeline 25 条 → 20；decisions 9 → 6；changes 8 → 6；某段 lines 30 → 20；verification、issues、nextSteps 各 10 → 8。
  - `` `缺字段或类型错误的单项被丢弃不影响其他项` ``：decisions 里一项缺 title、一项 title 为数字、一项正常 → 只剩 1 项；空字符串字段视同缺失（summary=`"  "` → null）。
  - `` `智能体字段经 agentReportJson 往返不变` ``：`reportFromJson(agentReportJson(r)) == r`（facts 为默认）。
  - `` `facts 往返不变且缺省时为空` ``：`factsFromJson(null) == ReportFacts()`。

- [ ] **Step 2: 运行确认失败**
  Run: `./gradlew test --offline --tests '*ReportJsonTest'`
  Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现** `SessionReport.kt`、`ReportJson.kt`。取 JSON 对象：先整体 `JsonParser.parseString`，不是对象则从第一个 `{` 起按括号配对（跳过字符串内的括号与转义）截出第一段再解析。时间线时间沿用 `ProgressOverview.kt` 里 `parseNodeTime` 的规则（把它改为 `internal` 复用）。

- [ ] **Step 4: 运行确认通过**
  Run: `./gradlew test --offline --tests '*ReportJsonTest'`
  Expected: PASS。

- [ ] **Step 5: 提交** `feat(report): 报告数据模型与 JSON 解析清洗`

---

### Task 2: 从会话记录统计时长与改动文件

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/overview/report/TranscriptFacts.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/overview/report/TranscriptFactsTest.kt`

**Interfaces:**
- Consumes: `ReportFacts`（Task 1）、`AgentType`。
- Produces: `internal fun transcriptFacts(agentType: AgentType, file: Path, limitBytes: Long, projectRoot: Path?): ReportFacts`

已从本机真实记录确认的格式（每行一个 JSON，时间戳都在最外层 `timestamp`，ISO-8601）：
- Claude Code：`message.content[]` 中 `type=tool_use` 且 `name` ∈ `Edit`、`MultiEdit`、`Write` 取 `input.file_path`；`NotebookEdit` 取 `input.notebook_path`。
- Codex：`type=response_item`，`payload.type` ∈ `custom_tool_call`、`function_call`，`payload.name=apply_patch`；补丁正文在 `custom_tool_call` 的 `payload.input`，或 `function_call` 的 `payload.arguments`（JSON 字符串，取其 `input`）。按行取 `*** Add File: `、`*** Update File: `、`*** Delete File: `、`*** Move to: ` 之后的路径。
- Pi：`message.content[]` 中 `type=toolCall` 且 `name` ∈ `edit`、`write` 取 `arguments.path`。

- [ ] **Step 1: 写失败的测试**，样例行直接按上面格式手写（字段与真实记录一致），写入临时文件：
  - `` `Claude 的 Edit Write MultiEdit NotebookEdit 都计入改动文件` ``：四种各一条，断言 files 依次为四个路径。
  - `` `Codex 的 apply_patch 新增 修改 删除 移动都计入` ``：一条 custom_tool_call 含 Add/Update/Delete/Move to 四个头，另一条 function_call 形式含一个 Update，断言五个路径。
  - `` `Pi 的 edit 和 write 计入，bash 不计入` ``。
  - `` `同一文件只记一次并保留首次出现顺序` ``。
  - `` `工程目录内的写相对路径，目录外的保留绝对路径` ``：projectRoot=`/p`，`/p/src/A.kt` → `src/A.kt`，`/tmp/x.md` → `/tmp/x.md`；projectRoot=null 时全部原样。
  - `` `截止位置之后的行不统计` ``：limitBytes 设在第二行末尾，第三行的文件不出现、时长只算前两行。
  - `` `时长按首末时间戳向下取整到分钟` ``：首 `…T09:00:00Z`、末 `…T11:15:59Z` → 135。
  - `` `没有可解析时间戳时时长为空` ``；`` `坏行跳过不影响其他行` ``。

- [ ] **Step 2: 运行确认失败** `./gradlew test --offline --tests '*TranscriptFactsTest'` → 编译失败。

- [ ] **Step 3: 实现** 按字节上限逐行读（只处理以换行结束的完整行，与 `trailCursor` 一致），每行 `JsonParser` 解析失败则跳过。

- [ ] **Step 4: 运行确认通过**。

- [ ] **Step 5: 提交** `feat(report): 从会话记录统计时长与改动文件`

---

### Task 3: 报告提示词、生成与缓存

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/overview/report/ReportPrompt.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/overview/OverviewPrompt.kt`（删去旧的时间线提示词与输出格式说明，保留并改为 `internal` 的 `recordFormatZh/En`、只读约束部分；`incrementalStartLine` 保留）
- Modify: `src/main/kotlin/com/github/izerui/imux/overview/ProgressOverview.kt`（`OverviewResult`、`OverviewCache` 格式、`runTrailGeneration`、`generateTrail`→`generateReport`、删 `parseOverviewOutput`、`generateOverview`）
- Modify: `src/main/kotlin/com/github/izerui/imux/settings/PluginLanguage.kt`（不需要改：`toString()` 已返回本地名）
- Test: `src/test/kotlin/com/github/izerui/imux/overview/report/ReportPromptTest.kt`；改写 `src/test/kotlin/com/github/izerui/imux/overview/ProgressOverviewTest.kt`、`IncrementalTrailTest.kt`

**Interfaces:**
- Consumes: Task 1 全部、`transcriptFacts`（Task 2）。
- Produces:
  - `OverviewResult` 增加 `val report: SessionReport? = null`；`nodes` 保持，由 `report.timeline` 经 `sanitizeNodeTimes` 得来。
  - `internal fun reportPrompt(isChinese: Boolean, language: PluginLanguage, agentType: AgentType, sessionFile: Path, cached: CachedOverview?, cursor: TrailCursor): String`
  - `internal fun generateReport(prompt: String, runAgent: (String) -> String, isCancelled: () -> Boolean = { false }): SessionReport` —— 重试 `TRAIL_ATTEMPTS` 次，仍失败抛 `OverviewParseException`。
  - `runTrailGeneration(...)` 增加参数 `facts: (TrailCursor) -> ReportFacts`，结果的 `report.facts` 由它填。
  - `incrementalStartLine` 在 `cached.result.report == null` 时返回 null。
  - 缓存 JSON 新增 `"report": { ...agentReportJson..., "facts": {...} }`；`nodes` 照旧写出。读取时有 `report` 则还原；没有（旧缓存）则 `report = SessionReport(timeline = nodes)`，并让 `CachedView.stale = true`、`OverviewResult.legacy = true`（新增布尔字段，供界面显示「格式已升级」）。

- [ ] **Step 1: 写失败的测试**
  - `ReportPromptTest`：
    - `` `完整生成读第一行到截止行并写明输出语言` ``：`reportPrompt(true, JAPANESE, CLAUDE, f, null, TrailCursor(120, …))` 包含 `第 1 行到第 120 行`、`日本語`、示例 JSON 里的 `"timeline"`。
    - `` `增量生成附上上次报告 JSON 并从上次截止的下一行读起` ``：cached 带 report、lineCount=100，cursor.lines=150 → 包含 `第 101 行`、`第 150 行`、上次报告的 goal 文本。
    - `` `旧缓存没有报告时退回完整生成` ``：cached.report=null → 包含 `第 1 行到`、不含上次报告。
    - `` `英文提示词同样写明输出语言` ``：isChinese=false、language=GERMAN → 包含 `Deutsch`。
  - `ProgressOverviewTest` / `IncrementalTrailTest` 改写：原断言时间线节点的用例改为喂 JSON 输出；新增
    - `` `生成结果带上插件统计的事实` ``：facts lambda 返回 `ReportFacts(135, listOf("a.kt"))`，结果 `report.facts` 相同。
    - `` `报告随缓存往返且时间线同步写入 nodes` ``。
    - `` `旧缓存读出只有时间线并标为过期和格式升级` ``：手写一份只有 `nodes` 的缓存 JSON → `result.report.timeline` 与 nodes 一致、`legacy == true`、`CachedView.stale == true`。
    - `` `解析不出报告时重试一次后报错` ``：runAgent 两次都返回闲聊 → 抛 `OverviewParseException`，调用 2 次。

- [ ] **Step 2: 运行确认失败** `./gradlew test --offline --tests '*ReportPromptTest' --tests '*ProgressOverviewTest' --tests '*IncrementalTrailTest'`。

- [ ] **Step 3: 实现**。提示词结构见 spec 第 3 节：角色说明 → 会话记录（路径、`recordFormat`、读取范围）→ 增量时附上次报告 JSON 并要求输出合并后的完整 JSON → 只读约束 → 各字段含义与上限（与 Global Constraints 一致）→ 内容约束（代码片段必须摘自实际编辑工具调用；verification 只写实际运行过的；goal 取最初请求）→ 「所有文字用 `$language` 书写」→ 示例 JSON → 「只输出一个 JSON 对象」。中英两套。`generateOverview` 里：`language = ImuxBundle.currentLanguage()`，`facts = { cursor -> transcriptFacts(session.agentType, session.filePath, cursor.bytes, project.basePath?.let(Path::of)) }`。

- [ ] **Step 4: 运行确认通过**，再跑一次全部 overview 测试 `./gradlew test --offline --tests 'com.github.izerui.imux.overview.*'`。

- [ ] **Step 5: 提交** `feat(report): 智能体改为输出报告 JSON，缓存扩展并兼容旧格式`

---

### Task 4: 模板、渲染与报告文件

**Files:**
- Create: `src/main/resources/report/session-report.html`（由 `~/Downloads/imux-报告原型/report.html` 整理：数据占位改为 `{}`、删去示例数据与说明注释，样式与语言表保持）
- Create: `src/main/kotlin/com/github/izerui/imux/overview/report/ReportPage.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/project/ImuxDirectory.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/terminal/ProjectAttachmentStore.kt`（`save` 里创建目录后调用 `ensureImuxIgnored(root)`；原 exclude 逻辑不动）
- Modify: `src/main/kotlin/com/github/izerui/imux/overview/ProgressOverview.kt`（`ProgressOverviewService.writeReport`）
- Test: `src/test/kotlin/com/github/izerui/imux/overview/report/ReportPageTest.kt`、`src/test/kotlin/com/github/izerui/imux/project/ImuxDirectoryTest.kt`、`src/test/kotlin/com/github/izerui/imux/overview/report/ReportTemplateTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `agentReportJson`、`factsJson`；Task 3 的 `OverviewResult.report`。
- Produces:
  - `internal data class ReportSession(val title: String, val agent: String, val project: String, val generatedAt: Instant)`
  - `internal fun pageDataJson(report: SessionReport, lang: String, session: ReportSession): String` —— 组装页面数据：`version=1`、`lang`、`session{title,agent,project,generatedAt}`、`stats{durationMinutes}`（为 null 时不写 `stats`）、`files`、其余智能体字段。
  - `internal fun renderReportPage(template: String, dataJson: String): String` —— 把唯一的 `<script id="imux-data" type="application/json">{}</script>` 中的 `{}` 换成转义后的数据（`<`→`\u003c`、`>`→`\u003e`、`&`→`\u0026`），并在 `<head>` 里写入 `<meta name="imux-template" content="<模板 SHA-256 前 12 位>">`；占位不存在或不唯一时抛 `IllegalStateException`。
  - `internal fun reportFile(projectRoot: Path, sessionKey: String): Path` —— `.imux/reports/` + 与 `OverviewCache.cacheFile` 相同的安全名规则 + `.html`。
  - `internal fun writeReportFile(projectRoot: Path, sessionKey: String, html: String): Path` —— 内容与现有文件相同则不写；否则临时文件 + 原子替换；写前调用 `ensureImuxIgnored`。
  - `fun ensureImuxIgnored(projectRoot: Path)`（`ImuxDirectory.kt`）—— 确保 `.imux/.gitignore` 存在且内容为 `*\n`；已存在则不改。
  - `ProgressOverviewService.writeReport(sessionKey: String, result: OverviewResult): Path?` —— 取 `SessionMonitor` 的标题与智能体、`project.name`、`ImuxBundle.currentLanguage().id`、`result.generatedAt`，读模板（类路径 `/report/session-report.html`，缓存在内存），渲染并写文件；`report == null` 或工程无 basePath 返回 null；IO 失败记日志返回 null。

- [ ] **Step 1: 写失败的测试**
  - `ReportPageTest`：
    - `` `正文里的 script 结束标签和 HTML 被转义成普通文字` ``：summary=`"</script><img src=x onerror=alert(1)>&"` → 产物中只有模板自身的 `</script>`（数一下出现次数与模板一致），数据段含 `\u003c/script\u003e` 与 `\u0026`。
    - `` `占位缺失或重复时报错` ``。
    - `` `页面数据带上插件字段且时长为空时不写 stats` ``：断言 `version`、`lang`、`session.title`、`files`；facts.durationMinutes=null → 数据里无 `stats`。
    - `` `写入报告文件后再写相同内容不改动文件` ``：比较两次写入后的修改时间。
    - `` `报告文件名按安全会话键生成` ``：`"a/b:c"` → `.imux/reports/a_b_c.html`。
  - `ImuxDirectoryTest`：
    - `` `没有 git 目录时也生成内容为星号的 gitignore` ``。
    - `` `已有 gitignore 时不覆盖` ``。
  - `ReportTemplateTest`（用 `SourceCode` 读 `src/main/resources/report/session-report.html`）：
    - `` `模板语言表覆盖插件的每种语言` ``：对 `PluginLanguage.entries` 每个 `id` 断言模板里有 `"<id>": {`。
    - `` `每种语言的界面文字键齐全` ``：以 `en` 的键集合为准，逐语言断言键集合相等（解析模板里 `I18N` 对象字面量的方式由实现者定，可用正则按语言块截取后收集 `键:`）。
    - `` `数据占位恰好一处且为空对象` ``。

- [ ] **Step 2: 运行确认失败**。
- [ ] **Step 3: 实现**。
- [ ] **Step 4: 运行确认通过**；并在生成后用浏览器打开一份由测试数据渲染的报告目视确认（可写个临时 main 或在测试里写到 `build/tmp` 再 `open`）。
- [ ] **Step 5: 提交** `feat(report): 内置报告模板与报告文件渲染，.imux 目录自带忽略`

---

### Task 5: 弹窗尺寸

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/terminal/SessionTrailLayout.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/terminal/SessionTrailOverlay.kt`（`layout`、`ResizeHandler.mouseReleased`）
- Test: `src/test/kotlin/com/github/izerui/imux/terminal/SessionTrailLayoutTest.kt`

**Interfaces:**
- Produces:
  - `TrailSizeSetting.DEFAULT_WIDTH = 520`、`DEFAULT_HEIGHT = 1127`、键改为 `imux.sessionReport.width/height`、`fun isCustomized(): Boolean`（任一键已设置）。
  - `internal fun fitKeepingRatio(desiredWidth: Int, desiredHeight: Int, availableWidth: Int, availableHeight: Int, minWidth: Int): Dimension` —— 行为同 `stash@{0}` 实验版：不放大；按 `min(1, availW/w, availH/h)` 同比缩；宽度不低于 `min(minWidth, desiredWidth)`。
  - `layout()`：显示报告页面时（内容区是报告视图），未拖拽中且 `!isCustomized()` 才走 `fitKeepingRatio(…, JBUI.scale(320))`；显示提示 / 加载 / 错误面板时沿用 `desiredCardHeight` 的紧凑逻辑。
  - 松手保存当前显示的宽和高（两边都存）。

- [ ] **Step 1: 写失败的测试**（`SessionTrailLayoutTest` 追加）：
  - `` `放得下时按原尺寸不放大` ``：(520,1127) 可用 (2000,2000) → (520,1127)。
  - `` `放不下时宽高同比缩小` ``：(520,1127) 可用 (2000,800) → 宽 369、高 800（按实现取整规则写确切值）。
  - `` `宽度缩到下限后只缩高度` ``：(520,1127) 可用 (2000,500) → 宽 320、高 500。
  - `` `可用宽度不足时也同比缩小` ``：(520,1127) 可用 (400,2000) → 宽 400、高 866。
- [ ] **Step 2–4: 失败 → 实现 → 通过**（`--tests '*SessionTrailLayoutTest'`）。
- [ ] **Step 5: 提交** `feat(report): 轨迹弹窗默认手机比例并按比例自适应`

---

### Task 6: 可选的 JCEF 报告视图

**Files:**
- Modify: `build.gradle.kts`（`bundledPlugin("com.intellij.modules.jcef")`）
- Modify: `src/main/resources/META-INF/plugin.xml`（`<depends optional="true" config-file="imux-jcef.xml">com.intellij.modules.jcef</depends>`）
- Create: `src/main/resources/META-INF/imux-jcef.xml`（`<idea-plugin/>`）
- Create: `src/main/kotlin/com/github/izerui/imux/terminal/report/ReportView.kt`（接口、工厂、退路视图）
- Create: `src/main/kotlin/com/github/izerui/imux/terminal/report/JcefReportView.kt`（唯一引用 JCEF 类的文件）
- Test: `src/test/kotlin/com/github/izerui/imux/terminal/report/ReportViewSourceTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  internal interface ReportView : Disposable {
      val component: JComponent
      /** 加载或重新加载报告文件。 */
      fun show(file: Path)
      fun setDark(dark: Boolean)
  }
  internal object ReportViews {
      /** JCEF 插件已启用且 JBCefApp.isSupported() 时返回 JCEF 视图，否则返回退路视图。 */
      fun create(onEscape: () -> Unit, openExternal: (Path) -> Unit): ReportView
  }
  ```
  - 退路视图：居中文字 `action.overview.report.noBrowser` + 链接 `action.overview.report.openInBrowser`（调用 `openExternal`）。
  - JCEF 视图（行为同 `stash@{0}` 的 `SpikeReportBrowser`）：`JBCefBrowser.createBuilder().setOffScreenRendering(true)`；加载完成时设 `data-theme` 并注入 Esc 监听（`JBCefJSQuery` → `invokeLater(onEscape)`）；`setDark` 在已加载时重设 `data-theme`；请求处理器拦截导航，目标不是当前文件的 URL 时交给 `BrowserUtil.browse` 并取消导航。
  - JCEF 插件是否启用：`PluginManagerCore.getPlugin(PluginId.getId("com.intellij.modules.jcef"))?.isEnabled == true`，为 true 才去加载 `JcefReportView`（再在其中判断 `JBCefApp.isSupported()`）。

- [ ] **Step 1: 写失败的测试** `ReportViewSourceTest`（用 `SourceCode` 读源码）：
  - `` `JCEF 类只在 JcefReportView 里引用` ``：遍历 `src/main/kotlin` 下所有 `.kt`，除 `JcefReportView.kt` 外都不含 `com.intellij.ui.jcef` 与 `org.cef`。
  - `` `plugin.xml 以可选依赖声明 JCEF` ``：含 `optional="true"` 与 `config-file="imux-jcef.xml"` 且依赖 id 为 `com.intellij.modules.jcef`；不存在非可选的同名 `<depends>`。
- [ ] **Step 2–4: 失败 → 实现 → 通过**；`./gradlew compileKotlin --offline` 通过。
- [ ] **Step 5: 运行** `./gradlew verifyPlugin --offline`，Expected：无 Critical 问题（若本机 `.verify/` 不全，记下实际输出如实汇报）。
- [ ] **Step 6: 提交** `feat(report): 可选 JCEF 报告视图与不可用时的退路`

---

### Task 7: 弹窗接入报告

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/terminal/SessionTrailOverlay.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/overview/TrailViews.kt`（删 `buildResultPanel`、`buildUpdatingPanel`、`UpdatingView`、`buildFailedWithPrevious`、`statusBanner`、`bannerRow`、`regenerateLink`、`ScrollablePanel`、`createNodePanel` 及节点时间 / 图标列相关函数——先 grep 确认无其他引用；保留 `buildErrorPanel`、`buildFailedBanner`、`buildHintPanel`、`LoadingRow`、`overviewProgressText`、`overviewErrorText`、`elapsedText`、`toolActionText`）
- Modify: 10 个 `src/main/resources/messages/ImuxBundle*.properties`
- Test: 改写 `src/test/kotlin/com/github/izerui/imux/terminal/SessionTrailOverlaySourceTest.kt`；删除只测已删代码的用例（如 `TrailGlyphRenderTest` 中对应部分）

**Interfaces:**
- Consumes: `ProgressOverviewService.writeReport`（Task 4）、`ReportViews.create`（Task 6）、`TrailSizeSetting` / `fitKeepingRatio`（Task 5）、`OverviewResult.legacy`（Task 3）。
- 新增文案键（10 种语言）：
  - `action.overview.report.update` = 更新报告
  - `action.overview.report.openInBrowser` = 在系统浏览器中打开
  - `action.overview.report.noBrowser` = 当前环境无法内嵌显示报告
  - `action.overview.report.stale` = 会话有新进展
  - `action.overview.report.legacy` = 报告格式已升级

行为（spec 第 7 节）：
- 标题栏：标题不变；右侧 ⟳（`AllIcons.Actions.Refresh`，调 `store.generate`）、↗（`AllIcons.Ide.External_link_arrow`，`BrowserUtil.browse(file)`）、收起；有报告文件时才显示 ⟳ 和 ↗。按钮写法沿用现有 `collapseButton`（`ActionButton` + `SYSTEM_LOOK`）。
- 内容区：

  | 状态 | 内容 |
  |---|---|
  | `Unavailable` / `Empty` | 现有提示面板 |
  | `Loading` 无旧结果 | 现有加载面板 |
  | `Loading` 有旧结果 | 顶部 `LoadingRow` + 取消链接，下面报告视图 |
  | `Ready` | 报告视图；`stale` 时顶部一条 `stale`（`legacy` 时用 `legacy` 文案）+ 「更新报告」链接 |
  | `Failed` 无旧结果 | 现有错误面板 |
  | `Failed` 有旧结果 | 顶部 `buildFailedBanner`，下面报告视图 |

- 报告视图：每个 overlay 一个，第一次需要时 `ReportViews.create` 创建、`Disposer.register(this, view)`；每次进入上表带报告的状态时调 `writeReport` 得到文件（内容不变则不写）再 `view.show(file)`；同一文件且内容未变时不重载（以 `writeReport` 是否写了新内容判断，可让其返回 `Pair<Path, Boolean>` 或在 overlay 记下上次内容的哈希——由实现者定）。
- 主题：创建时 `setDark(!JBColor.isBright())`；订阅 `LafManagerListener.TOPIC`（连接挂在 overlay 的 Disposable 上）重设。
- 语言切换监听（已有 `addLanguageListener`）：重新 `writeReport` 并 `show`。
- Esc：视图回调 `setExpanded(false)`。

- [ ] **Step 1: 改写 `SessionTrailOverlaySourceTest`**：
  - `` `标题栏带更新报告和在系统浏览器中打开按钮` ``：源码含 `action.overview.report.update`、`action.overview.report.openInBrowser`。
  - `` `报告视图通过 ReportViews 创建并随面板释放` ``：含 `ReportViews.create`、`Disposer.register(this`。
  - `` `主题切换时重设报告深浅色` ``：含 `LafManagerListener.TOPIC` 与 `setDark(`。
  - `` `每种语言都有新增的报告文案` ``：对 10 个 properties 文件逐一断言 5 个新键都存在且非空。
- [ ] **Step 2: 运行确认失败**。
- [ ] **Step 3: 实现**，删掉不再使用的 Swing 时间线代码与测试。
- [ ] **Step 4: 全量测试** `./gradlew test --offline`，Expected：BUILD SUCCESSFUL。
- [ ] **Step 5: 手动验证**（`./gradlew runIde --offline`，用户配合）：三种会话各生成一次报告；弹窗显示手机排版；↗ 打开电脑排版；深浅主题切换；切换插件语言后界面文字变化；Esc、点外面收起；拖宽后不回缩、双击左下角恢复；生成中显示进度条与旧报告、取消可用；更新失败时显示错误条与旧报告；删除 `.imux/reports/` 后再打开弹窗能重新生成文件（不跑智能体）。
- [ ] **Step 6: 提交** `feat(report): 会话轨迹弹窗显示网页报告`

---

### Task 8: 文档收尾

**Files:**
- Modify: `README.md`（「会话轨迹」一节：说明弹窗显示的是会话报告、包含哪些内容、可在系统浏览器中打开、报告存放在 `.imux/reports/`）

- [ ] **Step 1: 修改 README 该节文字**（截图与宣传视频不在本计划内）。
- [ ] **Step 2: 最终验证**：`./gradlew test --offline` 全绿；`./gradlew verifyPlugin --offline` 结果如实记录。
- [ ] **Step 3: 提交** `docs: README 会话轨迹一节改为会话报告`
