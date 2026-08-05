# 会话终端滚动到底部设计

## 目标

在 imux 会话的 Editor 终端内提供一个可发现的“滚动到底部”图标按钮。用户查看历史输出后，可以一次点击回到最新输出；普通标签切换继续保留原滚动位置。

## 方案

- 在 `AgentTerminalFileEditor` 的终端内容右下角叠加一个 IntelliJ Action Toolbar 图标按钮。
- 使用接近 OpenAI/ChatGPT 样式的简洁图标 `AllIcons.General.ArrowDown`，tooltip 为“滚动到底部”。
- 点击时调用 `TerminalHost.scrollToBottom(TerminalView)`。
- 终端已在底部时隐藏按钮；用户向上滚动离开底部后显示。
- `scrollToBottom` 每次从 `TerminalView.component` 的 DataContext 重新解析当前终端 Editor，再通过 `ScrollingModel.scrollVertically(Int.MAX_VALUE)` 无动画滚动。

不能缓存终端 Editor：Codex 等 TUI 切换 alternate screen buffer 时，`TerminalView` 内部的当前 Editor 会更换。

## 布局与交互

- 终端组件占满 Editor。
- 按钮以透明浮层放在底部水平居中位置。
- 按钮只执行一次滚动，不改变焦点、不启用自动跟随输出；点击后立即隐藏。
- 每个 `AgentTerminalFileEditor` 实例拥有自己的浮层组件；关闭或重建 Editor 时沿用现有生命周期清理。

## 验证

- 源码测试锁定按钮使用 Action System、平台向下图标和 tooltip。
- 源码测试锁定按钮调用公开滚动入口，滚动入口继续动态解析当前终端 Editor。
- 运行全部测试并执行 `buildPlugin`，验证 Kotlin 编译与插件打包。
