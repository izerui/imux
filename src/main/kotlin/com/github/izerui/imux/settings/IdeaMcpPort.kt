package com.github.izerui.imux.settings

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId

private const val MCP_PLUGIN_ID = "com.intellij.mcpServer"
private const val MCP_SETTINGS_CLASS = "com.intellij.mcpserver.settings.McpServerSettings"
private val LOG = logger<ImuxSettings>()

/**
 * 从 JetBrains MCP Server 插件读取当前端口，只用于初始化/校验 Imux 自己的设置值。
 *
 * 目标类属于另一个插件且不是公开 API，不能产生直接字节码引用。这里必须使用该插件自己的
 * classloader；普通 `Class.forName` 走 imux classloader，在两个插件独立安装时看不到目标类。
 * 平台改名或插件被禁用时返回 null，Imux 继续使用自己的持久化端口。
 */
internal fun readIdeaMcpPlatformPort(
    pluginClassLoader: () -> ClassLoader? = {
        PluginManagerCore
            .getPlugin(PluginId.getId(MCP_PLUGIN_ID))
            ?.pluginClassLoader
    },
    loadClass: (ClassLoader, String) -> Class<*> = { loader, name ->
        Class.forName(name, true, loader)
    },
): Int? =
    runCatching {
        val loader = pluginClassLoader() ?: return null
        val settingsClass = loadClass(loader, MCP_SETTINGS_CLASS)
        val settings = settingsClass.getMethod("getInstance").invoke(null) ?: return null
        val state = settingsClass.getMethod("getState").invoke(settings) ?: return null
        (state.javaClass.getMethod("getMcpServerPort").invoke(state) as Int)
            .takeIf { it in 1..65535 }
    }.getOrElse {
        LOG.debug("读不到 JetBrains MCP Server 端口，保留 Imux 端口设置", it)
        null
    }
