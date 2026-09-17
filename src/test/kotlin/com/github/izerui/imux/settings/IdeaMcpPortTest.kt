package com.github.izerui.imux.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class IdeaMcpPortTest {
    @Test
    fun `读取端口时使用 MCP 插件的类加载器`() {
        val pluginLoader = object : ClassLoader() {}
        var receivedLoader: ClassLoader? = null

        readIdeaMcpPlatformPort(
            pluginClassLoader = { pluginLoader },
            loadClass = { loader, _ ->
                receivedLoader = loader
                FakeMcpSettings::class.java
            },
        )

        assertSame(pluginLoader, receivedLoader)
    }

    @Test
    fun `读取端口时查找 JetBrains MCP 设置类`() {
        var receivedName: String? = null

        val port =
            readIdeaMcpPlatformPort(
                pluginClassLoader = { javaClass.classLoader },
                loadClass = { _, name ->
                    receivedName = name
                    FakeMcpSettings::class.java
                },
            )

        assertEquals("com.intellij.mcpserver.settings.McpServerSettings", receivedName)
        assertEquals(64355, port)
    }

    class FakeMcpSettings {
        fun getState(): FakeState = FakeState()

        companion object {
            @JvmStatic
            fun getInstance(): FakeMcpSettings = FakeMcpSettings()
        }
    }

    class FakeState {
        fun getMcpServerPort(): Int = 64355
    }
}
