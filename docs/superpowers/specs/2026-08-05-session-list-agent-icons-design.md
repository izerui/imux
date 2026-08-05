# 会话列表 Agent 图标设计

## 目标

在 imux 会话树中增加 Claude 与 Codex 品牌图标，同时显示运行中、未读和已打开状态。

## 展示规则

- Claude Code 分组使用 Claude 品牌图标。
- Codex 分组使用 OpenAI 品牌图标。
- 具体会话行不显示品牌图标，避免品牌图标和文字之间因状态槽位产生过大间距。
- 会话行只保留原有状态图标：
  - 运行中使用平台 `AnimatedIcon.Default.INSTANCE` 加载动画。
  - 未读继续使用 `AllIcons.General.Modified`。
  - 已在编辑器标签页中打开时使用 `AllIcons.General.ProjectTab`。
- 状态优先级为：运行中 > 未读 > 已打开 > 普通。
- 普通会话、等待首条消息的新会话和“显示更多”节点使用平台 `EmptyIcon.ICON_16` 占位，使所有子节点标题对齐。

## 平台集成

分组标题直接使用共享品牌图标；会话行使用 IntelliJ Platform 262 的官方加载动画、未读图标和空图标。树组件设置 `AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED`，让渲染器中的加载动画按平台机制自动刷新。

将现有标签页图标加载逻辑抽取为共享 `AgentIcons`，由 `FileIconProvider` 和会话树共同使用，确保同一 Agent 在不同界面使用同一资源。

## 验证

- 测试共享图标映射仍分别返回 16×16 Claude/Codex 图标。
- 测试运行中、未读、已打开和普通会话按优先级返回对应的平台图标。
- 测试树渲染器启用了平台动画刷新属性。
- 保留源码约束测试，确认状态展示继续使用平台图标。
- 运行完整测试、插件构建和结构校验。
