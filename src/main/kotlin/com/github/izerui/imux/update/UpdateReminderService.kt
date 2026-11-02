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
