package com.github.izerui.imux.settings

import com.github.izerui.imux.lsp.LspReport
import com.intellij.ide.ui.laf.darcula.DarculaLaf
import com.intellij.mock.MockApplication
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.LookAndFeel
import javax.swing.UIManager

class ImuxLspLifecycleTest {
    private lateinit var disposable: Disposable
    private lateinit var app: QueuedApplication
    private lateinit var page: ImuxLspConfigurable
    private var originalLaf: LookAndFeel? = null
    private var probes = 0
    private val report = LspReport(emptyList())

    @Before
    fun setUp() {
        disposable = Disposer.newDisposable("LSP lifecycle")
        originalLaf = UIManager.getLookAndFeel()
        UIManager.setLookAndFeel(DarculaLaf())
        app = QueuedApplication(disposable)
        ApplicationManager.setApplication(app, disposable)
        app.registerService(com.intellij.ui.ExperimentalUI::class.java, object : com.intellij.ui.ExperimentalUI() {
            override fun saveCurrentValueAndReapplyDefaultLaf() = Unit
        })
        page = ImuxLspConfigurable { probes++; report }
        openPage()
    }

    @After
    fun tearDown() {
        page.disposeUIResources()
        Disposer.dispose(disposable)
        originalLaf?.let(UIManager::setLookAndFeel)
    }

    @Test
    fun `设置页仍可通过无参构造创建`() {
        val configurable = ImuxLspConfigurable::class.java.getDeclaredConstructor().newInstance()
        configurable.disposeUIResources()
        assertEquals("仅创建设置页不启动检测", 0, app.background.size)
    }

    @Test
    fun `关闭后排队中的检测不再启动`() {
        refresh()
        page.disposeUIResources()
        app.runProbe()
        app.drainUi()

        assertEquals(0, probes)
        assertNull(field("lastReport"))
    }

    @Test
    fun `检测结束后关闭页面使排队的结果失效`() {
        refresh()
        app.runProbe()
        assertEquals(1, probes)
        page.disposeUIResources()
        app.drainUi()

        assertNull("关闭后不能重新填回检测结果", field("lastReport"))
    }

    @Test
    fun `重新打开后旧检测回调不更新新页面`() {
        refresh()
        app.runProbe()
        val oldResult = app.ui.removeFirst()
        page.disposeUIResources()
        val initial = openPage()
        refresh()

        oldResult.run()
        assertSame("旧页面结果不能覆盖新页面", initial, field("lastReport"))
        app.runProbe()
        app.drainUi()
        assertSame("新页面正常接收自己的结果", report, field("lastReport"))
        assertTrue((field("refreshButton") as JButton).isEnabled)
    }

    @Test
    fun `旧页面命令完成的排队回调不会触发新页面检测`() {
        val terminalScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val state = MutableStateFlow<TerminalViewSessionState>(TerminalViewSessionState.Terminated)
        val terminal = java.lang.reflect.Proxy.newProxyInstance(
            TerminalView::class.java.classLoader, arrayOf(TerminalView::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getCoroutineScope" -> terminalScope
                "getSessionState" -> state
                else -> error("Unexpected call: ${method.name}")
            }
        } as TerminalView
        try {
            ImuxLspConfigurable::class.java
                .getDeclaredMethod("refreshWhenFinished", String::class.java, TerminalView::class.java)
                .apply { isAccessible = true }
                .invoke(page, "row", terminal)
            val oldRefresh = app.ui.pollFirst(5, TimeUnit.SECONDS)
            assertNotNull("命令结束后应排队刷新", oldRefresh)
            page.disposeUIResources()
            openPage()
            refresh()

            oldRefresh!!.run()
            assertEquals("新页面仅启动自己的检测", 1, app.background.size)
        } finally {
            terminalScope.cancel()
        }
    }

    @Test
    fun `页面有效时检测结果正常显示`() {
        refresh()
        assertTrue(!(field("refreshButton") as JButton).isEnabled)
        app.runProbe()
        app.drainUi()

        assertSame(report, field("lastReport"))
        assertTrue((field("refreshButton") as JButton).isEnabled)
    }

    // 只建立刷新所需的页面状态，省去与生命周期无关的设置表单和项目服务。
    private fun openPage(): LspReport {
        setField("scope", CoroutineScope(SupervisorJob() + Dispatchers.Default))
        setField("refreshButton", JButton())
        return LspReport(emptyList()).also { setField("lastReport", it) }
    }

    private fun refresh() {
        ImuxLspConfigurable::class.java.getDeclaredMethod("refresh").apply { isAccessible = true }.invoke(page)
    }

    private fun field(name: String): Any? =
        ImuxLspConfigurable::class.java.getDeclaredField(name).apply { isAccessible = true }.get(page)

    private fun setField(name: String, value: Any) {
        ImuxLspConfigurable::class.java.getDeclaredField(name).apply { isAccessible = true }.set(page, value)
    }

    private class QueuedApplication(parent: Disposable) : MockApplication(parent) {
        val background = ArrayDeque<FutureTask<Unit>>()
        val ui = LinkedBlockingDeque<Runnable>()

        override fun executeOnPooledThread(action: Runnable): Future<*> =
            FutureTask(action, Unit).also { background.addLast(it) }

        override fun invokeLater(action: Runnable, state: ModalityState) {
            ui.addLast(action)
        }

        fun runProbe() {
            val task = background.removeFirst()
            task.run()
            task.get()
        }

        fun drainUi() {
            while (ui.isNotEmpty()) ui.removeFirst().run()
        }
    }
}
