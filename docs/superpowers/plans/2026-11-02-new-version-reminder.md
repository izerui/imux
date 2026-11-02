# 新版本提醒 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Marketplace 上有对当前 IDE 兼容的更新版本时，弹一次气泡通知提醒升级，提供去升级、查看更新内容、忽略此版本三个操作。

**Architecture:** 新包 `com.github.izerui.imux.update`。两个纯函数（XML 解析、是否提醒）承载可测逻辑；应用级服务 `UpdateReminderService` 负责定时、HTTP、读写忽略版本和发通知；`postStartupActivity` 启动服务，服务内保证只调度一次。

**Tech Stack:** Kotlin、IntelliJ Platform（`HttpRequests`、`NotificationGroupManager`、`PropertiesComponent`、`VersionComparatorUtil`、`PluginManagerCore`）、kotlinx.coroutines、JUnit 4。

设计文档：`docs/superpowers/specs/2026-11-02-new-version-reminder-design.md`

## Global Constraints

- 插件 ID：`com.github.izerui.imux`
- 查询接口：`https://plugins.jetbrains.com/plugins/list?pluginId=com.github.izerui.imux&build=<ApplicationInfo.getInstance().build.asString()>`
- 版本页：`https://plugins.jetbrains.com/plugin/33412-imux/versions`
- 通知组 ID：`imux.update`，`displayType="BALLOON"`
- 忽略版本存储：`PropertiesComponent.getInstance()`，键 `imux.update.ignoredVersion`
- 首次检查延迟 30 秒，之后每 24 小时；HTTP 连接/读取超时各 10 秒
- 失败一律静默，只 `LOG.debug`
- 文案键补齐 10 个语言包：`ImuxBundle.properties`、`_de`、`_es`、`_fr`、`_ja`、`_ko`、`_pt_BR`、`_ru`、`_zh_CN`、`_zh_TW`（UTF-8 原文，不用 `\u` 转义）；带参文案不要出现 ASCII 撇号 `'`
- 测试命令：`./gradlew test --tests '<FQCN>'`；全量 `./gradlew test`
- 代码注释用中文，风格与周边代码一致

---

### Task 1: Marketplace 返回解析

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/update/MarketplaceVersionParser.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/update/MarketplaceVersionParserTest.kt`

**Interfaces:**
- Produces: `internal object MarketplaceVersionParser { fun latestVersion(xml: String, pluginId: String): String? }`

- [ ] **Step 1: 写失败的测试**

```kotlin
package com.github.izerui.imux.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarketplaceVersionParserTest {
    private val id = "com.github.izerui.imux"

    private fun repo(body: String) =
        "<?xml version='1.0' encoding='UTF-8'?><plugin-repository><ff>\"AI\"</ff>" +
            "<category name=\"AI\">$body</category></plugin-repository>"

    @Test
    fun `取到插件节点下的版本号`() {
        val xml =
            repo(
                "<idea-plugin downloads=\"747\"><name>imux</name><id>$id</id>" +
                    "<description><![CDATA[<p>desc</p>]]></description>" +
                    "<version>1.3.1</version><idea-version since-build=\"262.0\"/></idea-plugin>",
            )
        assertEquals("1.3.1", MarketplaceVersionParser.latestVersion(xml, id))
    }

    @Test
    fun `没有兼容版本时返回 null`() {
        assertNull(MarketplaceVersionParser.latestVersion("<plugin-repository/>", id))
    }

    @Test
    fun `缺少 version 时返回 null`() {
        val xml = repo("<idea-plugin><id>$id</id></idea-plugin>")
        assertNull(MarketplaceVersionParser.latestVersion(xml, id))
    }

    @Test
    fun `插件 ID 不匹配时返回 null`() {
        val xml = repo("<idea-plugin><id>other.plugin</id><version>9.9.9</version></idea-plugin>")
        assertNull(MarketplaceVersionParser.latestVersion(xml, id))
    }

    @Test
    fun `非法 XML 返回 null`() {
        assertNull(MarketplaceVersionParser.latestVersion("<html>502 Bad Gateway", id))
    }

    @Test
    fun `拒绝 DOCTYPE`() {
        val xml =
            "<?xml version='1.0'?><!DOCTYPE r [<!ENTITY x \"1.0\">]>" +
                repo("<idea-plugin><id>$id</id><version>&x;</version></idea-plugin>")
        assertNull(MarketplaceVersionParser.latestVersion(xml, id))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.update.MarketplaceVersionParserTest'`
Expected: 编译失败，`Unresolved reference: MarketplaceVersionParser`

- [ ] **Step 3: 实现**

```kotlin
package com.github.izerui.imux.update

import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 解析 Marketplace `plugins/list` 接口返回的 XML。
 *
 * 接口已按请求里的 build 号过滤，返回的 `idea-plugin` 就是当前 IDE 能装的最新版本。
 */
internal object MarketplaceVersionParser {
    /** 取 [pluginId] 对应插件的版本号；没有兼容版本、格式不对时返回 null。 */
    fun latestVersion(
        xml: String,
        pluginId: String,
    ): String? =
        try {
            val builder =
                DocumentBuilderFactory.newInstance()
                    .apply {
                        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                        isExpandEntityReferences = false
                    }.newDocumentBuilder()
            // 默认错误处理器会往 stderr 打印 [Fatal Error]，这里改成直接抛出。
            builder.setErrorHandler(ThrowingErrorHandler)
            val plugins = builder.parse(InputSource(StringReader(xml))).getElementsByTagName("idea-plugin")
            (0 until plugins.length)
                .map { plugins.item(it) as Element }
                .firstOrNull { it.childText("id") == pluginId }
                ?.childText("version")
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }

    private fun Element.childText(tag: String): String? {
        val children = childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && node.tagName == tag) return node.textContent.trim()
        }
        return null
    }

    private object ThrowingErrorHandler : ErrorHandler {
        override fun warning(exception: SAXParseException) = Unit

        override fun error(exception: SAXParseException): Unit = throw exception

        override fun fatalError(exception: SAXParseException): Unit = throw exception
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.update.MarketplaceVersionParserTest'`
Expected: 6 个测试全部 PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/github/izerui/imux/update/MarketplaceVersionParser.kt src/test/kotlin/com/github/izerui/imux/update/MarketplaceVersionParserTest.kt
git commit -m "新版本提醒：解析 Marketplace 返回的兼容最新版本号"
```

---

### Task 2: 是否提醒的判断

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/update/UpdateReminderPolicy.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/update/UpdateReminderPolicyTest.kt`

**Interfaces:**
- Produces: `internal fun shouldRemind(current: String, latest: String?, ignored: String?, alreadyShown: String?): Boolean`（顶层函数，包 `com.github.izerui.imux.update`）

- [ ] **Step 1: 写失败的测试**

```kotlin
package com.github.izerui.imux.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateReminderPolicyTest {
    @Test
    fun `远端更新时提醒`() {
        assertTrue(shouldRemind("1.3.3", "1.3.4", null, null))
    }

    @Test
    fun `版本相同或更旧时不提醒`() {
        assertFalse(shouldRemind("1.3.3", "1.3.3", null, null))
        assertFalse(shouldRemind("1.3.3", "1.3.1", null, null))
    }

    @Test
    fun `按数字比较而非字典序`() {
        assertTrue(shouldRemind("1.3.9", "1.3.10", null, null))
    }

    @Test
    fun `没有远端版本时不提醒`() {
        assertFalse(shouldRemind("1.3.3", null, null, null))
    }

    @Test
    fun `已忽略的版本不提醒，更新的版本照常提醒`() {
        assertFalse(shouldRemind("1.3.3", "1.3.4", "1.3.4", null))
        assertTrue(shouldRemind("1.3.3", "1.3.5", "1.3.4", null))
    }

    @Test
    fun `本次运行已提醒过的版本不再提醒`() {
        assertFalse(shouldRemind("1.3.3", "1.3.4", null, "1.3.4"))
        assertTrue(shouldRemind("1.3.3", "1.3.5", null, "1.3.4"))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.update.UpdateReminderPolicyTest'`
Expected: 编译失败，`Unresolved reference: shouldRemind`

- [ ] **Step 3: 实现**

```kotlin
package com.github.izerui.imux.update

import com.intellij.util.text.VersionComparatorUtil

/**
 * 远端有更新版本、用户没忽略过、本次运行也没提醒过时才提醒。
 *
 * [ignored] 是用户点"忽略此版本"记下的版本号，只挡这一个版本；[alreadyShown] 是本次 IDE 运行已提醒过的版本。
 */
internal fun shouldRemind(
    current: String,
    latest: String?,
    ignored: String?,
    alreadyShown: String?,
): Boolean =
    latest != null &&
        VersionComparatorUtil.compare(latest, current) > 0 &&
        latest != ignored &&
        latest != alreadyShown
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.update.UpdateReminderPolicyTest'`
Expected: 6 个测试全部 PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/github/izerui/imux/update/UpdateReminderPolicy.kt src/test/kotlin/com/github/izerui/imux/update/UpdateReminderPolicyTest.kt
git commit -m "新版本提醒：判断是否需要提醒（版本比较、忽略版本、本次已提醒）"
```

---

### Task 3: 服务、通知、注册与多语言

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/update/UpdateReminderService.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/update/UpdateReminderActivity.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`（`notificationGroup` 区块，约第 144-146 行；`postStartupActivity` 区块，约第 152-159 行）
- Modify: `src/main/resources/messages/ImuxBundle*.properties`（10 个文件，末尾追加）
- Test: `src/test/kotlin/com/github/izerui/imux/PluginXmlRegistrationTest.kt`（追加测试）

**Interfaces:**
- Consumes: `MarketplaceVersionParser.latestVersion(xml: String, pluginId: String): String?`；`shouldRemind(current: String, latest: String?, ignored: String?, alreadyShown: String?): Boolean`
- Produces: `class UpdateReminderService`（`@Service(Service.Level.APP)`，`fun start()`，`companion fun getInstance()`）；`class UpdateReminderActivity : ProjectActivity`

- [ ] **Step 1: 写失败的注册测试**

在 `PluginXmlRegistrationTest` 类末尾追加：

```kotlin
    @Test
    fun `注册了新版本提醒的通知组和启动活动`() {
        assertTrue(
            "缺少 imux.update 通知组，发通知时 getNotificationGroup 会拿到 null",
            pluginXml.contains("<notificationGroup id=\"imux.update\" displayType=\"BALLOON\"/>"),
        )
        assertTrue(
            "未注册 UpdateReminderActivity，新版本检查永远不会启动",
            pluginXml.contains("com.github.izerui.imux.update.UpdateReminderActivity"),
        )
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.PluginXmlRegistrationTest'`
Expected: `注册了新版本提醒的通知组和启动活动` FAIL，提示缺少 imux.update 通知组

- [ ] **Step 3: 注册到 plugin.xml**

在 `<notificationGroup id="imux.phoneBridge" displayType="BALLOON"/>` 下一行加：

```xml
        <!-- Marketplace 有兼容的新版本时提醒升级；用户可在 IDE 通知设置里关闭 -->
        <notificationGroup id="imux.update" displayType="BALLOON"/>
```

在 `BridgeNotificationsActivity` 的 `postStartupActivity` 之后加：

```xml
        <postStartupActivity
                implementation="com.github.izerui.imux.update.UpdateReminderActivity"/>
```

- [ ] **Step 4: 加文案（10 个语言包末尾追加）**

`ImuxBundle.properties`：
```properties
update.notify.title=New imux version available
update.notify.content=imux {0} is available (current: {1})
update.action.upgrade=Update
update.action.notes=Release Notes
update.action.ignore=Ignore This Version
```

`ImuxBundle_zh_CN.properties`：
```properties
update.notify.title=imux 有新版本
update.notify.content={0} 已可用（当前 {1}）
update.action.upgrade=去升级
update.action.notes=查看更新内容
update.action.ignore=忽略此版本
```

`ImuxBundle_zh_TW.properties`：
```properties
update.notify.title=imux 有新版本
update.notify.content={0} 已可用（目前 {1}）
update.action.upgrade=前往更新
update.action.notes=查看更新內容
update.action.ignore=忽略此版本
```

`ImuxBundle_ja.properties`：
```properties
update.notify.title=imux の新しいバージョンがあります
update.notify.content={0} が利用可能です（現在 {1}）
update.action.upgrade=更新する
update.action.notes=更新内容を見る
update.action.ignore=このバージョンを無視
```

`ImuxBundle_ko.properties`：
```properties
update.notify.title=imux 새 버전이 있습니다
update.notify.content={0} 버전을 사용할 수 있습니다(현재 {1})
update.action.upgrade=업데이트
update.action.notes=변경 사항 보기
update.action.ignore=이 버전 무시
```

`ImuxBundle_de.properties`：
```properties
update.notify.title=Neue imux-Version verfügbar
update.notify.content=imux {0} ist verfügbar (aktuell: {1})
update.action.upgrade=Aktualisieren
update.action.notes=Änderungen anzeigen
update.action.ignore=Diese Version ignorieren
```

`ImuxBundle_es.properties`：
```properties
update.notify.title=Nueva versión de imux disponible
update.notify.content=imux {0} está disponible (actual: {1})
update.action.upgrade=Actualizar
update.action.notes=Ver novedades
update.action.ignore=Ignorar esta versión
```

`ImuxBundle_fr.properties`：
```properties
update.notify.title=Nouvelle version disponible pour imux
update.notify.content=imux {0} est disponible (actuelle : {1})
update.action.upgrade=Mettre à jour
update.action.notes=Voir les nouveautés
update.action.ignore=Ignorer cette version
```

`ImuxBundle_pt_BR.properties`：
```properties
update.notify.title=Nova versão do imux disponível
update.notify.content=imux {0} está disponível (atual: {1})
update.action.upgrade=Atualizar
update.action.notes=Ver novidades
update.action.ignore=Ignorar esta versão
```

`ImuxBundle_ru.properties`：
```properties
update.notify.title=Доступна новая версия imux
update.notify.content=Доступна imux {0} (текущая: {1})
update.action.upgrade=Обновить
update.action.notes=Что нового
update.action.ignore=Игнорировать эту версию
```

- [ ] **Step 5: 写服务**

`UpdateReminderService.kt`：

```kotlin
package com.github.izerui.imux.update

import com.github.izerui.imux.ImuxBundle
import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.PluginManagerConfigurable
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.io.HttpRequests
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

private val LOG = logger<UpdateReminderService>()

private const val PLUGIN_ID = "com.github.izerui.imux"
private const val VERSIONS_URL = "https://plugins.jetbrains.com/plugin/33412-imux/versions"
private const val IGNORED_KEY = "imux.update.ignoredVersion"
private const val TIMEOUT_MS = 10_000
private val INITIAL_DELAY = 30.seconds
private val CHECK_INTERVAL = 24.hours

/**
 * 定时向 Marketplace 查询对当前 IDE 兼容的最新版本，有更新时弹一次提醒。
 *
 * 应用级：多个项目窗口共用一份调度，同一版本一次运行只提醒一次。查询失败一律静默，等下个周期。
 */
@Service(Service.Level.APP)
class UpdateReminderService(private val scope: CoroutineScope) {
    private val started = AtomicBoolean(false)

    @Volatile
    private var shownVersion: String? = null

    /** 每个项目打开时都会调到这里，只有第一次真正开始调度。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            delay(INITIAL_DELAY)
            while (true) {
                checkOnce()
                delay(CHECK_INTERVAL)
            }
        }
    }

    private suspend fun checkOnce() {
        // 沙箱里开发调试时也有版本号；真取不到说明插件描述符异常，直接不查。
        val current = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: return
        val latest = withContext(Dispatchers.IO) { fetchLatest() } ?: return
        val ignored = PropertiesComponent.getInstance().getValue(IGNORED_KEY)
        if (!shouldRemind(current, latest, ignored, shownVersion)) return
        shownVersion = latest
        notify(current, latest)
    }

    private fun fetchLatest(): String? {
        val build = URLEncoder.encode(ApplicationInfo.getInstance().build.asString(), Charsets.UTF_8)
        val url = "https://plugins.jetbrains.com/plugins/list?pluginId=$PLUGIN_ID&build=$build"
        return try {
            val xml =
                HttpRequests.request(url)
                    .connectTimeout(TIMEOUT_MS)
                    .readTimeout(TIMEOUT_MS)
                    .readString()
            MarketplaceVersionParser.latestVersion(xml, PLUGIN_ID)
        } catch (e: IOException) {
            LOG.debug("查询 Marketplace 最新版本失败", e)
            null
        }
    }

    private fun notify(
        current: String,
        latest: String,
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("imux.update")
            .createNotification(
                ImuxBundle.message("update.notify.title"),
                ImuxBundle.message("update.notify.content", latest, current),
                NotificationType.INFORMATION,
            ).addAction(
                NotificationAction.createSimpleExpiring(ImuxBundle.message("update.action.upgrade")) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(
                        ProjectManager.getInstance().openProjects.firstOrNull(),
                        PluginManagerConfigurable::class.java,
                    )
                },
            ).addAction(
                NotificationAction.createSimple(ImuxBundle.message("update.action.notes")) {
                    BrowserUtil.browse(VERSIONS_URL)
                },
            ).addAction(
                NotificationAction.createSimpleExpiring(ImuxBundle.message("update.action.ignore")) {
                    PropertiesComponent.getInstance().setValue(IGNORED_KEY, latest)
                },
            )
            // 不绑定项目，多个窗口时只弹一次。
            .notify(null)
    }

    companion object {
        fun getInstance(): UpdateReminderService = service()
    }
}
```

`UpdateReminderActivity.kt`：

```kotlin
package com.github.izerui.imux.update

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** 启动新版本检查；服务是应用级的，多个项目打开也只调度一次。 */
class UpdateReminderActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        UpdateReminderService.getInstance().start()
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.PluginXmlRegistrationTest' --tests 'com.github.izerui.imux.ImuxBundleTest' --tests 'com.github.izerui.imux.update.*'`
Expected: 全部 PASS（`ImuxBundleTest` 校验 10 个语言包键齐全、占位符一致）

- [ ] **Step 7: 全量测试与 IDE 检查**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

对新建和修改的 Kotlin 文件跑 IDEA MCP `lint_files`，确认没有错误（尤其是 `PluginManagerConfigurable`、`HttpRequests` 的可见性和弃用警告）；`PlatformApiAlignmentSourceTest` 若对新用法有约束，按其要求调整。

- [ ] **Step 8: 提交**

```bash
git add src/main/kotlin/com/github/izerui/imux/update/ src/main/resources/META-INF/plugin.xml src/main/resources/messages/ src/test/kotlin/com/github/izerui/imux/PluginXmlRegistrationTest.kt
git commit -m "新版本提醒：定时查询 Marketplace，有兼容新版本时气泡提醒升级、查看更新内容或忽略此版本"
```

---

### 手工验收（实现后，由用户或执行者在沙箱 IDE 里做）

1. 临时把 `build.gradle.kts` 的 `version` 改成 `1.0.0`，`./dev.sh` 或 `./gradlew runIde` 启动沙箱 IDE，约 30 秒后应弹出"imux 有新版本 1.3.1（当前 1.0.0）"一次；再开一个项目窗口不再弹。
2. 点"查看更新内容"打开版本页，通知仍在；点"忽略此版本"后重启沙箱，不再提醒。
3. 断网启动，无任何报错通知。
4. 验收完把 `version` 改回 `1.3.3`，不要提交临时改动。
