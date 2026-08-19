# AGENTS.md

## 工作方式

- 基于项目交付层面理解用户需求；如果可以一次完成，就一次性完成。
- 主动定位、实现并验证问题，不把可以自行确认的事项反复交给用户决定。
- 修改前先阅读现有代码和项目约定，优先沿用已有架构与实现模式。

## IntelliJ Platform 版本

- 本项目只支持最新的 IntelliJ IDEA 2026.2，对应 IntelliJ Platform build `262`。
- 不为旧版 IntelliJ Platform 增加兼容分支、反射回退或旧 API 适配，除非用户明确要求。

## IntelliJ Platform 原生 UI 优先

实现界面、交互和视觉效果时，优先复用 IntelliJ Platform 262 自带能力，不要在已有官方实现的情况下自行绘制图标、仿造控件或重复实现事件机制。

### 图标

- 优先使用 `com.intellij.icons.AllIcons` 中语义匹配的官方图标，例如：
  - `AllIcons.General.ArrowDown`
  - `AllIcons.Actions.Refresh`
  - `AllIcons.RunConfigurations.Scroll_down`
- 选择图标时先确认其实际语义和视觉效果，不要只根据字段名猜测。
- 官方图标能够自动适配 IDE 明暗主题、缩放和平台视觉规范，应优先于自定义 SVG。
- 只有 `AllIcons` 和平台已有资源确实无法表达需求时才新增自定义图标，并说明不能复用官方图标的原因。
- 选择或比较图标时，优先打开本地 `AllIcons` 浏览器：
  `.reference/icon-gallery/index.html`。该索引覆盖 Platform 262 的全部公开
  `AllIcons` 常量，支持搜索、分类筛选、明暗主题预览和点击复制常量名。
- IDEA 版本更新后，运行 `node .reference/icon-gallery/generate.mjs` 重新生成索引；
  生成器默认读取 `/Applications/IntelliJ IDEA.app/Contents`，也可通过
  `IDEA_HOME` 指定其他安装目录。详细说明见 `.reference/README.md`。

可通过本机 262 平台 jar 检索可用图标：

```bash
javap -classpath \
  "/Applications/IntelliJ IDEA.app/Contents/lib/intellij.platform.util.ui.jar" \
  'com.intellij.icons.AllIcons$General'
```

### 组件和视觉效果

- 优先使用 IntelliJ Platform 组件与工具类，例如 `ActionToolbar`、`ToolbarDecorator`、`JBPanel`、`JBLabel`、`JBScrollPane`、`JBLayeredPane`、`JBPopupFactory` 和 UI DSL。
- 图标按钮优先通过 Action System 构建，使用 `AnAction` / `DumbAwareAction`、官方 hover/pressed/disabled 状态和 tooltip。
- 尺寸、间距、边框和颜色优先使用 `JBUI`、`UIUtil`、Named Color 等平台 API，不硬编码不能适配缩放或主题的视觉值。
- 优先复用平台已有的工具栏、弹窗、通知、加载状态、空状态和编辑器装饰效果，不手工仿造相似 Swing 控件。

### 事件和状态

- 优先使用对应领域的官方事件与监听 API，例如：
  - 编辑器滚动使用 `VisibleAreaListener`
  - 文件标签切换使用 `FileEditorManagerListener`
  - IDE 服务状态使用 Message Bus
  - Terminal buffer 切换使用公开的 `StateFlow`，如 `TerminalOutputModelsSet.active`
- 有事件通知时不要使用定时轮询；有局部组件监听时不要使用全局 AWT 监听。
- 监听器、协程和订阅必须绑定正确生命周期，优先传入 parent `Disposable`；无法绑定时必须在 `dispose()` 中显式移除或取消。
- Terminal 内部 Editor 可能因 alternate screen buffer 切换而变化，不缓存会失效的 Editor、组件或 DataContext；应监听官方切换信号并重新解析。

### API 确认原则

1. 先在 `.reference/jetbrains/` 中检索官方文档和示例。
2. 再通过本机 IDEA 2026.2 的 jar、`javap`、`jar tf` 或 SDK 源码确认实际类、字段和方法。
3. 查找平台内相同语义的现有实现，优先沿用其图标、Action、组件和事件模式。
4. 只有官方能力确实不足时才自定义，并在代码注释中记录原因、替代方案及生命周期风险。
5. 不为绕过 Kotlin `internal`、私有 API 或旧版本兼容而使用反射；本项目直接面向 262 的公开 API 实现。

## JetBrains 官方参考资料

项目根目录下保存了 JetBrains 官方资料的本地副本，目录 `.reference/` 已被 Git 忽略，不应提交到项目仓库。

- IntelliJ Platform SDK 文档：
  `.reference/jetbrains/intellij-sdk-docs`
- IntelliJ Platform SDK 官方示例：
  `.reference/jetbrains/intellij-sdk-code-samples`
- 本地资料来源和版本记录：
  `.reference/README.md`
- Embedded Terminal 文档：
  `.reference/jetbrains/intellij-sdk-docs/topics/reference_guide/embedded_terminal.md`

处理 IntelliJ Platform API、扩展点、生命周期、Terminal API 或插件兼容性问题时：

1. 优先使用 `rg` 检索上述本地官方文档和示例。
2. 结合项目使用的 2026.2 SDK 源码/API 校验具体接口，不凭旧版本经验猜测。
3. 本地资料找不到或可能已经更新时，再查询 JetBrains 官方在线文档或官方源码。
4. 不直接修改 `.reference/jetbrains/` 下的上游资料。

文档正文都在 `intellij-sdk-docs/topics/` 下，常用子目录：

- `topics/reference_guide/`：平台能力参考，含 `embedded_terminal.md`、`editors.md`、
  `icons.md`、`messaging_infrastructure.md`、`accessibility.md`、`settings_guide.md`
- `topics/user_interface_components/`：官方 UI 组件用法，含 `tool_windows.md`、
  `popups.md`、`lists_and_trees.md`、`kotlin_ui_dsl_version_2.md`、`dialog_wrapper.md`
- `topics/basics/`：`action_system.md`、`disposers.md`、`persisting_state_of_components.md`
  等基础机制
- `topics/ui/`：JetBrains UI 设计规范（视觉、交互、文案）
- `reference_guide/api_changes_list_2026.md`：262 的 API 变更清单

`images/`（69M）和 `topics/_generated/` 是噪音，检索时优先限定到具体子目录。

检索示例：

```bash
# 全量检索
rg "TerminalToolWindowTabsManager" .reference/jetbrains

# 限定文档正文，避免命中图片和生成文件
rg "VisibleAreaListener" .reference/jetbrains/intellij-sdk-docs/topics

# 在官方示例中找可直接参考的实现
rg -l "ToolWindowFactory" .reference/jetbrains/intellij-sdk-code-samples
```

更新本地文档：

```bash
git -C .reference/jetbrains/intellij-sdk-docs pull --ff-only
git -C .reference/jetbrains/intellij-sdk-code-samples pull --ff-only
```

如果 `.reference/` 不存在，可重新下载：

```bash
git clone --depth=1 --filter=blob:none --single-branch --branch main \
  https://github.com/JetBrains/intellij-sdk-docs.git \
  .reference/jetbrains/intellij-sdk-docs

git clone --depth=1 \
  https://github.com/JetBrains/intellij-sdk-code-samples.git \
  .reference/jetbrains/intellij-sdk-code-samples
```
