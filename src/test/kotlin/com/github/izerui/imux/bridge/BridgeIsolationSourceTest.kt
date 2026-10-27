package com.github.izerui.imux.bridge

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 桥接是旁路扩展：只能是新代码依赖现有代码，现有代码不能反过来依赖桥接。
 * 这条测试失败，说明有人把桥接逻辑写进了原有功能里。
 */
class BridgeIsolationSourceTest {
    private val root = File("src/main/kotlin/com/github/izerui/imux")

    @Test
    fun `bridge 以外的源码不引用 bridge 包`() {
        val nonBridgeFiles =
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { it.path.contains("${File.separator}bridge${File.separator}") }
                .toList()
        assertTrue("bridge 以外没有找到任何 .kt 源文件", nonBridgeFiles.isNotEmpty())
        val offenders = nonBridgeFiles
            .filter { SourceCode(it.path).normalized.contains("com.github.izerui.imux.bridge") }
            .map { it.path }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `渠道与路由内核不依赖 Agent 类型和平台终端`() {
        val core = File(root, "bridge/core")
        val listed = core.listFiles { f -> f.extension == "kt" && f.name != "BridgeModels.kt" }
        assertNotNull("bridge/core 目录不存在", listed)
        val files = listed!!
        assertTrue("bridge/core 里没有需要检查的源文件", files.isNotEmpty())
        files.forEach { file ->
            val code = SourceCode(file.path).normalized
            assertTrue("${file.name} 引用了 AgentType", !code.contains("AgentType"))
            assertTrue("${file.name} 引用了终端平台 API", !code.contains("org.jetbrains.plugins.terminal"))
            assertTrue("${file.name} 引用了 intellij API", !code.contains("com.intellij"))
        }
    }
}
