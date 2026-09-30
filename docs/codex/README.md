# Codex 官方文档快照

来源：

- 官方文档索引：`https://learn.chatgpt.com/docs/llms.txt`（`/llms.txt` 会重定向到这里）

目录：

- `pages/`：按官方索引下载的独立 Markdown 页面
- `llms.txt`：官方 Codex 文档索引
- `pages/docs/codex-manual.md`：官方按主题整理的合集手册（索引中的 Codex manual），带关键词索引与每节来源链接；它与 `pages/` 其它页面内容大量重复，grep 时会重复命中
- `llms-full.txt`：官方全量索引，是各页面的拼接，大量章节只有标题；按主题整理的合集用 `codex-manual.md`

当前快照（2026-09-30）包含 173 个 Markdown 页面（含 `codex-manual.md`）。索引中部分条目是资源索引、视频页或全量索引，不属于独立 Markdown 文档页面。

更新：`python3 docs/sync-docs.py codex`
