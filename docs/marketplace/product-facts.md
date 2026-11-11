# 事实核验（2026-11-11）

## JetBrains Marketplace 插件页媒体图
- 最小建议尺寸 1200×760；所有截图保持同一宽高比。（官方：https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html）
- 可放功能截图，也可放说明性的幻灯片（informational slides）。
- 要展示 JetBrains 产品里的真实界面；不要放桌面背景、浏览器窗口、与插件无关的内容，不要夸大插件能力，不要给第三方产品打广告。
- 轮播区实际显示约 800px 宽（第三方工具 mpshots 的经验值），文字在这个宽度下要看得清。
- 主题插件另有 1280×800（16:10）的建议，可作为比例参考。（https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html）

## 本次决定
- 统一 16:10。先按 2560×1600 渲染，再缩到 1520×950 并用 pngquant 压缩（每张约 200 KB），Marketplace 与仓库 README 共用；仍高于最小建议 1200×760，缩到 800px 宽时正文仍可读。
- 界面一律复用 docs/promo-video/assets/ui/ 的真实界面重建，文案取 ImuxBundle_zh_CN.properties。
