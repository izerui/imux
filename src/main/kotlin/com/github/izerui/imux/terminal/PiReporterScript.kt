package com.github.izerui.imux.terminal

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** 打包时放进插件目录的上报脚本，见 build.gradle.kts 的 prepareSandbox 配置。 */
private const val SCRIPT_RELATIVE_PATH = "scripts/pi-imux-reporter.js"
internal const val PLUGIN_DIRECTORY_NAME = "imux"

private object PiReporterScriptLocation

/**
 * 插件目录下某个随包脚本的绝对路径；目录未知或文件不存在时返回 null。
 *
 * 脚本名做成参数而不是各写一份：向上找三层父目录那一段是这里唯一微妙的地方
 * （见 [pluginScriptNear]），复制一份就多一处会各自漂移的实现。
 */
internal fun pluginScriptIn(
    pluginPath: Path?,
    relativePath: String,
): Path? {
    val script = pluginPath?.resolve(relativePath) ?: return null
    return script.takeIf { Files.isRegularFile(it) }
}

/** 从插件 classpath 项（通常是 `<plugin>/lib/imux-*.jar`）向上定位脚本。 */
internal fun pluginScriptNear(
    classPathEntry: Path?,
    relativePath: String,
): Path? =
    generateSequence(classPathEntry) { it.parent }
        .take(3)
        .mapNotNull { pluginScriptIn(it, relativePath) }
        .firstOrNull()

/**
 * 上报脚本的绝对路径；插件目录未知或文件不存在时返回 null。
 *
 * 返回 null 时调用方必须**不加** `-e` 参数：把一个不存在的路径拼进命令行，
 * pi 会因加载不到扩展而启动异常，代价是整个会话起不来——
 * 而少了上报只是标签页不自动跟随，退回本功能上线前的行为。
 */
internal fun piReporterScriptIn(pluginPath: Path?): Path? = pluginScriptIn(pluginPath, SCRIPT_RELATIVE_PATH)

/** 从插件 classpath 项（通常是 `<plugin>/lib/imux-*.jar`）向上定位脚本。 */
internal fun piReporterScriptNear(classPathEntry: Path?): Path? = pluginScriptNear(classPathEntry, SCRIPT_RELATIVE_PATH)

internal fun locatePiReporterScript(
    pluginPath: Path?,
    classPathEntry: Path?,
): Path? = piReporterScriptIn(pluginPath) ?: piReporterScriptNear(classPathEntry)

/**
 * 生产入口：优先使用平台公开的插件目录；code source 仅作为非 IDE 环境的降级路径。
 *
 * runIde 会从构建输出加载插件类，class code source 不在 sandbox 插件目录下；只靠它定位
 * 会让脚本明明已被 PrepareSandboxTask 复制，却仍静默漏掉 `-e`。Gradle 构建的插件根目录
 * 名固定为项目名 `imux`，正式安装与 runIde 沙箱均为 `<plugins>/imux`。
 */
internal fun piReporterScript(): Path? {
    val pluginPath =
        runCatching {
            PathManager.getPluginsDir().resolve(PLUGIN_DIRECTORY_NAME)
        }.getOrElse {
            LOG.debug("读取插件目录失败，尝试从 classpath 定位 Pi reporter", it)
            null
        }

    val classPathEntry =
        runCatching {
            val location =
                PiReporterScriptLocation::class.java.protectionDomain.codeSource
                    ?.location
            location?.toURI()?.let(Path::of)
        }.getOrElse {
            LOG.debug("读取插件 classpath 失败，无法使用 reporter 降级定位", it)
            null
        }
    return locatePiReporterScript(pluginPath, classPathEntry).also { script ->
        if (script == null && missingScriptWarned.compareAndSet(false, true)) {
            LOG.warn("找不到随插件打包的 Pi reporter 脚本，Pi 会话将无法自动跟随会话切换")
        }
    }
}

private val LOG = logger<PiReporterScriptLocation>()
private val missingScriptWarned = AtomicBoolean(false)
