# JetBrains Marketplace 预览图

上传到插件页 Media 区的 7 张预览图，16:10，2560×1600。仓库 README 引用的是 `readme/` 下的压缩版（1520×950，pngquant 压缩，每张约 200 KB），只用于 README，不用于上传 Marketplace。风格沿用宣传片的暮色外景（方向记录见 `direction-approved.md`，规格核验见 `product-facts.md`）。

| 文件 | 功能 |
|---|---|
| `01-sessions.png` | 会话面板：按智能体分组、运行与未读状态、双击接着聊 |
| `02-session-trail.png` | 会话轨迹：需求变更、回滚、旁支、报错、进行中 |
| `03-clarify.png` | 先聊再做：目标、逐题提问、推荐选项 |
| `04-ide-mcp.png` | IDEA MCP：语义重命名、断点调试、数据库、IDE 检查、运行配置 |
| `05-phone-bridge.png` | 手机桥接：手机发消息进终端、截图与文件发到手机、署名 |
| `06-pair-programming.png` | 结对编程：审查进度、审查意见自动发回 |
| `07-session-handoff.png` | 会话转移：右键交给 Codex 或 Pi |

## 重新生成

图由 `slides.html?s=1…7` 渲染，界面复用 `docs/promo-video/assets/ui/` 的重建组件；界面或文案变了，先改那里，再重新导出。按 1.6 倍渲染 2560×1600 的原图（上传 Marketplace 用），再缩到 1520×950 并压缩成 `readme/` 下的 README 版：

```bash
cd docs/marketplace
CH="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
for pair in 1:01-sessions 2:02-session-trail 3:03-clarify 4:04-ide-mcp 5:05-phone-bridge 6:06-pair-programming 7:07-session-handoff; do
  "$CH" --headless=new --disable-gpu --hide-scrollbars --allow-file-access-from-files \
    --force-device-scale-factor=1.6 --window-size=1600,1000 --virtual-time-budget=4000 \
    --screenshot="$PWD/${pair#*:}.png" "file://$PWD/slides.html?s=${pair%%:*}"
  ffmpeg -y -loglevel error -i "${pair#*:}.png" -vf scale=1520:950:flags=lanczos "/tmp/${pair#*:}-1520.png"
  pngquant --quality=80-95 --speed 1 --force --output "readme/${pair#*:}.png" "/tmp/${pair#*:}-1520.png"
done
```

功能标注由脚本按目标元素的实际位置自动画箭头，改界面后不用手调坐标；标签控制在 9 个字以内，否则会超出两侧留白。
