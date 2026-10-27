package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.swing.JComponent
import kotlin.coroutines.cancellation.CancellationException

class SessionDecorationsTest {
    private class Fake(val value: Boolean, val fail: Boolean = false) : SessionDecorator {
        override fun isPhoneConnected(
            project: Project,
            tabId: String,
        ): Boolean = value

        override fun createBannerSegment(
            project: Project,
            file: AgentTerminalVirtualFile,
            parent: Disposable,
        ): JComponent? = null

        fun ask(): Boolean {
            if (fail) throw IllegalStateException("boom")
            return value
        }
    }

    @Test
    fun `装饰实现抛异常时取回退值，其余照常`() {
        val results = collectSafely(listOf(Fake(true, fail = true), Fake(true), Fake(false)), false) { (it as Fake).ask() }
        assertEquals(listOf(false, true, false), results)
    }

    @Test
    fun `链接错误取回退值`() {
        val results = collectSafely(listOf(Fake(true)), false) { throw NoClassDefFoundError("missing") }
        assertEquals(listOf(false), results)
    }

    @Test
    fun `取消信号照常向上传递`() {
        val thrown =
            runCatching { collectSafely(listOf<SessionDecorator>(Fake(true)), false) { throw CancellationException("c") } }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
    }

    @Test
    fun `plugin xml 声明了会话装饰扩展点`() {
        val xml = File("src/main/resources/META-INF/plugin.xml").readText()
        assertTrue(xml.contains("qualifiedName=\"com.github.izerui.imux.sessionDecorator\""))
        assertTrue(xml.contains("interface=\"com.github.izerui.imux.terminal.SessionDecorator\""))
    }

    @Test
    fun `门面只经扩展点访问装饰，不认识具体功能`() {
        val code = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/SessionDecorations.kt").normalized
        assertTrue(code.contains("ExtensionPointName"))
        assertTrue(!code.contains("bridge"))
    }
}
