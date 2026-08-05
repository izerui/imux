# imux

IntelliJ IDEA 插件：左侧工具窗口列出当前项目的 Claude Code / Codex 会话，点击会话即在编辑器标签页中打开对应的 CLI 终端。

插件本身是个**壳子**——它是两个 CLI 自有会话库的视图，加一个终端宿主。不生成会话 id、不解析对话内容、不存储任何自己的状态。

- 设计文档：`docs/superpowers/specs/2026-08-03-imux-design.md`
- 实现计划：`docs/superpowers/plans/2026-08-03-imux.md`

## 环境要求

| 项 | 版本 | 说明 |
|---|---|---|
| IntelliJ IDEA | 2026.2+ | 实测构建号 IU-262.8665.337；插件 `sinceBuild=262`，不设 `untilBuild` |
| JDK | 21 | `jvmToolchain(21)` |
| Gradle | 9.6.1 | 由 wrapper 提供。IntelliJ Platform Gradle Plugin 2.18.1 **要求 Gradle 9+** |
| Kotlin | 2.3.21 | **必须匹配平台自身的 Kotlin 版本**：IDEA 2026.2 的 jar 元数据是 2.3.0，用 2.1.x 编译会报 `incompatible version of Kotlin` |

平台依赖用 `local("/Applications/IntelliJ IDEA.app")`，即**直接使用本机安装的 IDEA**，不从网络下载。若你的 IDEA 装在别处，改 `build.gradle.kts` 里这一行。

## 构建

```bash
./gradlew test          # 跑测试
./gradlew buildPlugin   # 产出 build/distributions/imux-<version>.zip
./gradlew runIde        # 起一个带本插件的 IDE 实例
```

### 开发时重启沙箱加载改动

插件包含 `sqlite-jdbc`。该驱动会向 JVM 全局 `DriverManager` 注册实例，使旧插件
ClassLoader 无法可靠热卸载；IDEA 会提示 `Failed to unload modified plugins: imux`。
因此构建配置已关闭 `autoReload`，不再支持 `prepareSandbox` 热更新。

用原始 Gradle 命令启动沙箱：

```bash
./gradlew runIde
```

代码修改后，在运行 `runIde` 的终端按 `Ctrl+C` 停止沙箱，再重新执行
`./gradlew runIde`。

沙箱 IDEA 中仍可通过 `File → Open` 打开真实项目进行实机验证。重启会终止沙箱内
由 imux 启动的 Claude Code / Codex 终端，重启前应确认没有需要保留的运行中会话。

### 如果你在需要代理的网络环境下

**JVM 不读 macOS 的系统代理设置**，必须显式传参，否则 Gradle 解析依赖时会报
`SSLHandshakeException: Remote host terminated the handshake`：

```bash
./gradlew test \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 \
  -Dhttp.proxyHost=127.0.0.1  -Dhttp.proxyPort=7890
```

或写进 `~/.gradle/gradle.properties`（不要提交到仓库）：

```properties
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7890
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7890
```

构建配置里已针对受限网络做了两处让步，都写在 `build.gradle.kts` 的注释里：

- `instrumentCode = false` —— 插桩需要 `com.jetbrains.intellij.java:java-compiler-ant-tasks`，该仓库在受限网络下不可达。本插件不用 GUI Designer 的 `.form`，关掉无影响。
- 未引入 `testFramework(TestFrameworkType.Platform)` —— 同样是仓库不可达。它只服务于 `BasePlatformTestCase` 类的平台测试；核心逻辑测试仅依赖 JUnit 4。被搁置的那个测试在 `docs/parked/`，网络允许时可恢复。

`settings.gradle.kts` 的 `pluginManagement` 里配了阿里镜像兜底，因为 `plugins.gradle.org` 在此网络下时通时断。

## 现状

**已验证**：28 个纯逻辑测试全绿（会话读取、合并排序、pending 绑定），插件可打包。

**未验证**（需要 GUI，无法自动化）：

- IDEA 的 Reworked 终端能否正常渲染并交互 Claude Code / Codex。这是整个插件的承重假设，验证步骤见实现计划的 Task 0
- 关闭标签页后进程是否存活（所有权规则的核心）
- detached terminal 关闭后，`TerminalView` 的 CoroutineScope 与 backend 会话是否同步释放

跑 `./gradlew runIde` 后按实现计划里 Task 0 / Task 6 Step 7 / Task 7 Step 5 的清单逐项核对。
