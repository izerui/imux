# 新版本提醒气泡：模拟稿

- 豁免三方向门：本稿是还原 IntelliJ 平台原生 balloon 通知（`NotificationGroup` displayType=BALLOON）的外观与交互，外观由平台决定，插件只提供标题、内容和动作文案，不是新视觉设计。
- 用户原话（2026-11-02）：「可以用 /huashu-design 模拟下，我看看效果么」
- 依据：`src/main/kotlin/com/github/izerui/imux/update/UpdateReminderService.kt` 的 `notify()`，文案取自 `messages/ImuxBundle*.properties` 的 `update.*` 键。
- IDE 明暗配色沿用 `docs/prototypes/terminal-banner-c.html` 已校过的新 UI 色值。
