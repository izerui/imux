package com.github.izerui.imux.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PiReporterScriptTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `在插件目录下定位上报脚本`() {
        val scripts = File(tmp.root, "scripts").apply { mkdirs() }
        val script = File(scripts, "pi-imux-reporter.js").apply { writeText("// x") }

        assertEquals(script.toPath(), piReporterScriptIn(tmp.root.toPath()))
    }

    /**
     * 安装不完整时必须退回「不加 -e」，而不是把一个不存在的路径拼进命令行——
     * pi 加载不到扩展会启动失败，代价是整个会话起不来。
     */
    @Test
    fun `脚本缺失时返回 null`() {
        assertNull(piReporterScriptIn(tmp.root.toPath()))
    }

    @Test
    fun `拿不到插件路径时返回 null`() {
        assertNull(piReporterScriptIn(null))
    }

    /** 源码里的脚本必须真实存在，否则打包出来的插件缺文件，功能静默失效。 */
    @Test
    fun `仓库里带着待打包的脚本`() {
        assertEquals(true, File("src/main/js/pi-imux-reporter.js").exists())
    }
}
