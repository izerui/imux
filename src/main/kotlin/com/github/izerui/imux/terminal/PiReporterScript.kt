package com.github.izerui.imux.terminal

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import java.nio.file.Files
import java.nio.file.Path

/** 打包时放进插件目录的上报脚本，见 build.gradle.kts 的 prepareSandbox 配置。 */
private const val SCRIPT_RELATIVE_PATH = "scripts/pi-imux-reporter.js"

private const val PLUGIN_ID = "com.github.izerui.imux"

/**
 * 上报脚本的绝对路径；插件目录未知或文件不存在时返回 null。
 *
 * 返回 null 时调用方必须**不加** `-e` 参数：把一个不存在的路径拼进命令行，
 * pi 会因加载不到扩展而启动异常，代价是整个会话起不来——
 * 而少了上报只是标签页不自动跟随，退回本功能上线前的行为。
 */
internal fun piReporterScriptIn(pluginPath: Path?): Path? {
    val script = pluginPath?.resolve(SCRIPT_RELATIVE_PATH) ?: return null
    return script.takeIf { Files.isRegularFile(it) }
}

/** 生产入口：从插件自身的安装目录取。 */
internal fun piReporterScript(): Path? =
    piReporterScriptIn(PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.pluginPath)
