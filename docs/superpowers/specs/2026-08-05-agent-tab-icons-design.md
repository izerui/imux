# Agent 标签页图标设计

## 目标

为 imux 打开的编辑器终端标签页同时显示会话状态和 Agent 品牌图标：

- Codex 标签页使用用户提供的 OpenAI 图标。
- Claude Code 标签页使用用户提供的 Claude 图标。
- 状态槽位与会话列表一致：忙碌、未读或空闲占位。

图标只改变标签页展示，不改变终端创建、会话标识、标题更新或关闭生命周期。

## 视觉处理

采用用户确认的“标签栏适配”方案：

- OpenAI 图标去除原图白色背景，保留标志轮廓。
- OpenAI 图标提供浅色和深色主题版本：浅色主题显示黑色轮廓，深色主题显示白色轮廓。
- Claude 图标保留橙色圆角背景和浅色标志。
- 资源按 IntelliJ 编辑器标签页的 16×16 逻辑尺寸制作，并提供高分辨率版本，避免缩放模糊。
- 标签页使用固定双槽布局：`[状态图标][品牌图标]`，状态变化时宽度不变。
- 忙碌使用 `AnimatedIcon.Default.INSTANCE`，未读使用 `AllIcons.General.Modified`，空闲使用 `EmptyIcon.ICON_16`。

`AllIcons` 不包含 OpenAI 或 Claude 品牌标志，因此此处使用自定义资源，而不以平台通用图标替代。

## 平台集成

新增 `FileIconProvider` 实现并注册到 `com.intellij.fileIconProvider`：

1. 收到 `AgentTerminalVirtualFile` 时读取其 `agentType` 和 `sessionKey`。
2. 从项目级 `SessionMonitor` 查询该会话的忙碌和未读状态。
3. 使用平台 `RowIcon` 将状态图标放在品牌图标左侧。
4. 忙碌优先于未读；状态变化不替换或隐藏品牌图标。
5. 对其他 `VirtualFile` 返回 `null`，不影响 IDE 里的其他文件。

这是 IntelliJ Platform 262 为虚拟文件自定义图标提供的官方扩展点。实现不直接修改编辑器标签组件，也不拆分现有 `AgentTerminalFileType`。

`SessionMonitor` 在运行态集合或未读状态变化时，通过 `FileEditorManager.updateFilePresentation()` 请求平台重新查询标签图标。该刷新复用现有状态更新节奏，不新增定时器或全局监听器。

## 资源加载

图标放在插件资源目录下，通过平台 `IconLoader` 加载。文件命名遵循 IntelliJ 的主题和高分辨率资源约定，使平台负责主题选择与 UI 缩放。

## 验证

- 测试 `plugin.xml` 已注册 `fileIconProvider`。
- 测试图标资源存在、尺寸正确并具备预期透明度。
- 测试 provider 对 Codex、Claude 和非 imux 虚拟文件返回正确结果。
- 测试忙碌、未读和空闲三种状态都保留品牌图标，且组合宽度一致。
- 测试运行态和未读状态变化会通过官方文件呈现 API 刷新标签。
- 运行完整 Gradle 测试和插件构建。
- 在 IDEA 2026.2 中检查浅色、深色主题下新建和恢复会话标签页的图标。
