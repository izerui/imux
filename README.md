# imux

IntelliJ IDEA 插件：左侧工具窗口列出当前项目的 Claude Code / Codex 会话，点击会话即在编辑器标签页中打开对应的 CLI 终端。

插件本身是个**壳子**——它是两个 CLI 自有会话库的视图，加一个终端宿主。不生成会话 id、不解析对话内容、不存储任何自己的状态。

- 设计文档：`docs/superpowers/specs/2026-08-03-imux-design.md`
- 实现计划：`docs/superpowers/plans/2026-08-03-imux.md`

## 环境要求

| 项 | 版本 | 说明 |
|---|---|---|
| IntelliJ IDEA | 2026.1+ | 实测构建号 IU-261.25134.95；插件 `sinceBuild=261`，不设 `untilBuild` |
| JDK | 21 | `jvmToolchain(21)` |
| Gradle | 9.6.1 | 由 wrapper 提供。IntelliJ Platform Gradle Plugin 2.18.1 **要求 Gradle 9+** |
| Kotlin | 2.3.21 | **必须匹配平台自身的 Kotlin 版本**：IDEA 2026.1 的 jar 元数据是 2.3.0，用 2.1.x 编译会报 `incompatible version of Kotlin` |

平台依赖用 `local("/Applications/IntelliJ IDEA.app")`，即**直接使用本机安装的 IDEA**，不从网络下载。若你的 IDEA 装在别处，改 `build.gradle.kts` 里这一行。

## 构建

```bash
./gradlew test          # 跑测试
./gradlew buildPlugin   # 产出 build/distributions/imux-<version>.zip
./gradlew runIde        # 起一个带本插件的 IDE 实例
```

### 开发时不要反复重启沙箱

改一行代码就 `runIde` 一次是白等启动时间。**沙箱 IDE 起一次就别关**，改完代码重新打包一下，插件会自动重载。用 `dev.sh`，不用记 Gradle 任务名：

```bash
# 终端 A：起了就别动，Ctrl+C 会关掉沙箱
./dev.sh ide

# 终端 B：每次改完代码跑一次
./dev.sh reload
```

脚本会自己 `cd` 到项目根，在哪个目录调用都行。沙箱 IDE 里可以 `File → Open` 打开任何真实项目，拿真会话干活。

`reload` 底下是 `./gradlew prepareSandbox` 而不是 `buildPlugin`：自动重载监视的是沙箱的 plugins 目录，而 `prepareSandbox` 正是往那儿写文件的任务，`buildPlugin` 还要多打一个 zip。

**为什么能自动重载**——两个条件都满足：

- `intellij-platform-gradle-plugin` 的 `autoReload` **默认就是 `true`**（见 2.18.1 源码 `IntelliJPlatformExtension.autoReload` 的 KDoc）。它给沙箱 IDE 加上 `-Didea.auto.reload.plugins=true`，平台侧由 `DynamicPluginVfsListener` 监听插件目录的文件变化并重载。
- 本插件是**纯动态插件**：`plugin.xml` 注册的三个扩展点 `toolWindow`、`fileEditorProvider`、`notificationGroup` 在平台里全部声明为 `dynamic="true"`，`TerminalHost` 用的是 `@Service` 轻量服务而非老式 component。任何一条不满足，平台都会要求重启而不是热载。

**注意热重载会杀掉所有正在跑的会话**：`TerminalHost.dispose()` 会 cancel 所有终端的 CoroutineScope，插件一卸载，CLI 进程就都没了。所以别在正式版 IDEA 上装这个插件搞热更新——你真在用的会话会被自己的每一次编译干掉。

> 待验证：`runIde` 长驻期间，同目录并发跑 `prepareSandbox` 是否会卡在 Gradle 的项目锁上。若确实阻塞，改用 IDE 内的 Gradle 面板执行，或等一次 `runIde` 结束再打包。

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
- `shouldAddToToolWindow(false)` 的实际行为，以及 `TerminalView` 的 CoroutineScope 由谁最终释放

跑 `./gradlew runIde` 后按实现计划里 Task 0 / Task 6 Step 7 / Task 7 Step 5 的清单逐项核对。
