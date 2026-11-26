# 视觉来源

优先级：仓库现有产品截图 > 已确认原型 > 风格探索。

- 终端布局参考：docs/images/pair-review.png、docs/images/session-trail.png。
- 尺寸与色值参考：docs/prototypes/terminal-tools-size.html。
- 智能体标志：src/main/resources/icons/claude@2x.png、codex@2x.png、pi@2x.png 及暗色变体。
- 工具图标：.reference/icon-gallery/assets/ 中的 JetBrains 官方 SVG。
- 字体：系统中文无衬线；代码与路径使用 Menlo/monospace。
- 浅色：#ffffff / #f7f8fa，文字 #1e1f22。
- 深色：#1e1f22 / #2b2d30，文字 #dfe1e5。
- 强调色：#3574f0 / #548af7；绿色用于有明确依据的积极状态。
- 配置状态使用蓝色；未知使用中性灰；提醒使用琥珀色。

所有标志与图标内嵌在生成的 HTML 中，不依赖网络。
HTML 模拟平台外观仅用于评审，生产实现仍应使用 IntelliJ 原生组件。
