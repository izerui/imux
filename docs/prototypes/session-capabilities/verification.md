# 初稿验证

2026-11-04。此轮仅新增 HTML 设计原型，没有修改插件生产代码。

## 已执行

- `node --check prototype.js`、`node --check generate.mjs`。
- 通过 agent-browser 打开三份离线 HTML。
- 1440×900 与 375×812 两个视口分别验证三个方向。
- 每个方向分别切换 Codex、Claude Code、Pi，并访问每个可见分类。
- 展开条目来源；搜索无结果；清除搜索。
- 关闭详情并从工具栏重新打开。
- 点击刷新前后逐项比较状态，确认不把配置改成连接成功。
- 打开、关闭原生数据口径对话框。
- 六组页面均无横向文档溢出、无损坏图片。
- 浏览器未报告脚本错误。
- 人工查看桌面与移动截图，调整弹窗密度及详情窗口高度。

截图位于 design-demos/：
- dir-1.png、dir-2.png、dir-3.png。
- dir-1-mobile.png、dir-2-mobile.png、dir-3-mobile.png。
- thumbs.png。

## 限制

- huashu-design 的 Python Playwright 检查与比较脚本因依赖未安装跳过，改用 agent-browser 实测与截图检查。
- 当前工具发现未提供 JetBrains lint_files/get_file_problems，未执行 IDE 检查。
- 未运行 Gradle：此次不涉及 Kotlin、平台 API 或插件运行代码。
- 尚未接入真实会话数据；演示数据的条目数、时间、名称不构成本机运行状态声明。

## 无障碍处理

使用原生 button/select/details/dialog、可见焦点、命名输入框及单一 polite 状态区。
分类使用 aria-pressed 按钮组，不冒用缺少键盘行为的 ARIA tabs。
数据口径使用 showModal，由浏览器处理焦点与背景 inert。
