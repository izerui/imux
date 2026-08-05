# Agent 标签页图标设计

## 目标

为 imux 打开的编辑器终端标签页显示 Agent 品牌图标：

- Codex 标签页使用用户提供的 OpenAI 图标。
- Claude Code 标签页使用用户提供的 Claude 图标。

图标只改变标签页展示，不改变终端创建、会话标识、标题更新或关闭生命周期。

## 视觉处理

采用用户确认的“标签栏适配”方案：

- OpenAI 图标去除原图白色背景，保留标志轮廓。
- OpenAI 图标提供浅色和深色主题版本：浅色主题显示黑色轮廓，深色主题显示白色轮廓。
- Claude 图标保留橙色圆角背景和浅色标志。
- 资源按 IntelliJ 编辑器标签页的 16×16 逻辑尺寸制作，并提供高分辨率版本，避免缩放模糊。

`AllIcons` 不包含 OpenAI 或 Claude 品牌标志，因此此处使用自定义资源，而不以平台通用图标替代。

## 平台集成

新增 `FileIconProvider` 实现并注册到 `com.intellij.fileIconProvider`：

1. 收到 `AgentTerminalVirtualFile` 时读取其 `agentType`。
2. `AgentType.CODEX` 返回 OpenAI 图标。
3. `AgentType.CLAUDE` 返回 Claude 图标。
4. 对其他 `VirtualFile` 返回 `null`，不影响 IDE 里的其他文件。

这是 IntelliJ Platform 262 为虚拟文件自定义图标提供的官方扩展点。实现不直接修改编辑器标签组件，也不拆分现有 `AgentTerminalFileType`。

## 资源加载

图标放在插件资源目录下，通过平台 `IconLoader` 加载。文件命名遵循 IntelliJ 的主题和高分辨率资源约定，使平台负责主题选择与 UI 缩放。

## 验证

- 测试 `plugin.xml` 已注册 `fileIconProvider`。
- 测试图标资源存在、尺寸正确并具备预期透明度。
- 测试 provider 对 Codex、Claude 和非 imux 虚拟文件返回正确结果。
- 运行完整 Gradle 测试和插件构建。
- 在 IDEA 2026.2 中检查浅色、深色主题下新建和恢复会话标签页的图标。
