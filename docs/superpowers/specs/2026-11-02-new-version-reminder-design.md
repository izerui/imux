# 新版本提醒

## 需求

Marketplace 上有比当前安装版本更新、且与当前 IDE 兼容的 imux 版本时，用气泡通知主动提醒用户升级。通知带三个操作：去升级、查看更新内容、忽略此版本。

不做：一键后台安装（依赖 IDE 内部 API）、推送到手机渠道、插件设置里的开关。

## 方案

插件自己调用 Marketplace 公开接口查询最新兼容版本，不复用 IDE 内部的 `UpdateChecker`（internal API，Plugin Verifier 会报，跨版本易失效）。

接口：

```
GET https://plugins.jetbrains.com/plugins/list?pluginId=com.github.izerui.imux&build=<ApplicationInfo 的完整 build 号，如 IU-262.1234>
```

返回 XML，Marketplace 已按 `build` 过滤，只给出当前 IDE 能安装的最新版本：

```xml
<plugin-repository><category name="AI"><idea-plugin ...><name>imux</name><id>com.github.izerui.imux</id>
  ...<version>1.3.1</version>...</idea-plugin></category></plugin-repository>
```

取 `id` 为 `com.github.izerui.imux` 的 `idea-plugin` 下的 `version` 文本。找不到插件节点或 `version` 时按"无结果"处理。

请求用 IDE 的 `HttpRequests`，自动走 IDE 代理设置；连接和读取超时各 10 秒。

## 检查时机

- 应用级服务 `UpdateReminderService`（`@Service(Service.Level.APP)`），由一个 `postStartupActivity` 启动；服务内用标志保证只启动一次，多个项目不会重复调度。
- 首次检查在启动后约 30 秒，之后每 24 小时一次，在服务的协程作用域里运行，随应用销毁取消。
- 同一个版本在一次 IDE 运行期间只提醒一次（内存记录已提醒的版本）。

## 是否提醒

纯函数，便于单测：

```kotlin
internal fun shouldRemind(current: String, latest: String?, ignored: String?, alreadyShown: String?): Boolean
```

满足以下全部条件时返回 true：

- `latest` 非空，且 `VersionComparatorUtil.compare(latest, current) > 0`
- `latest != ignored`
- `latest != alreadyShown`

当前版本从 `PluginManagerCore.getPlugin(PluginId.getId("com.github.izerui.imux"))?.version` 读取，取不到时不检查。

"忽略此版本"记录的版本号存在 `PropertiesComponent.getInstance()`（应用级），键名 `imux.update.ignoredVersion`。只忽略这一个版本，之后发布更新的版本照常提醒。

## 通知

新增通知组 `<notificationGroup id="imux.update" displayType="BALLOON"/>`，用户可在 IDE 的 Settings → Notifications 里关闭。

- 标题：imux 有新版本
- 内容：`{latest} 已可用（当前 {current}）`
- 操作：
  - **去升级**：`ShowSettingsUtil.getInstance().showSettingsDialog(project, PluginManagerConfigurable::class.java)` 打开插件设置页（project 取当前任一打开的项目，可为 null），由 IDE 负责下载、安装和重启提示；通知关闭。
  - **查看更新内容**：`BrowserUtil.browse("https://plugins.jetbrains.com/plugin/33412-imux/versions")`；通知保留。
  - **忽略此版本**：写入忽略版本，通知关闭。

通知不绑定具体项目（`notify(null)`），避免多窗口重复弹出。

## 错误处理

网络失败、超时、非 2xx、XML 解析失败一律静默：记一条 `LOG.debug`，等下个周期再查。不弹错误通知，不重试。

## 多语言

新增文案键补齐 `messages/` 下全部 10 个语言包（默认、de、es、fr、ja、ko、pt_BR、ru、zh_CN、zh_TW），`ImuxBundleTest` 校验键齐全。

## 代码位置

新包 `com.github.izerui.imux.update`：

| 文件 | 职责 |
|---|---|
| `MarketplaceVersionParser.kt` | 解析接口 XML，返回最新版本号或 null（纯函数） |
| `UpdateReminderPolicy.kt` | `shouldRemind`（纯函数） |
| `UpdateReminderService.kt` | 调度、HTTP 请求、读写忽略版本、发通知 |
| `UpdateReminderActivity.kt` | `postStartupActivity`，启动服务调度 |

## 测试

- `MarketplaceVersionParserTest`：正常返回取到版本；空 `plugin-repository`、缺 `version`、`id` 不匹配、非法 XML 都返回 null。
- `UpdateReminderPolicyTest`：远端更新时提醒；相等或更旧时不提醒；已忽略、本次运行已提醒过时不提醒；`latest` 为 null 时不提醒；`1.3.10` 比 `1.3.9` 新。
- `PluginXmlRegistrationTest`：补充断言 `imux.update` 通知组和启动活动已注册。

## 验收

- 本地安装的版本低于 Marketplace 兼容最新版本时，IDE 启动约 30 秒后弹出一次通知，多个项目窗口也只弹一次。
- 点"忽略此版本"后重启 IDE 不再提醒该版本。
- 断网启动无任何报错通知。
