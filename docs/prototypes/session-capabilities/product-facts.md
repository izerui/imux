# 事实边界

复用本会话已核实的接口和本机实测，不另行声称最新版本。
- Claude Code 2.1.308：插件清单实测；规则加载 Hook 有官方依据。
- Codex 0.166.0：MCP 配置、skills/list、config/read 实测；不能据此断言普通终端的实时 MCP 连接状态。
- Pi 1.4.0：getAllTools、getActiveTools、getCommands 以及模型/上下文接口实测。
- imux 的 Pi reporter 已存在；能力元数据上报尚未实现。

官方参考：
- https://code.claude.com/docs/en/hooks
- https://code.claude.com/docs/en/statusline
- https://developers.openai.com/codex/app-server/
- https://pi.dev/docs/latest/extensions

原型中的条目数量、时间、工具数量、会话名称和状态是设计示例。
不包含真实令牌、真实模型消费量或完整个人配置清单。
