package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.SourceCode
import com.github.izerui.imux.icons.AgentIcons
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.peer.PeerCoordinator
import com.github.izerui.imux.peer.PeerMcpConfig
import com.github.izerui.imux.peer.PeerOutcomeKind
import com.github.izerui.imux.session.SessionListModel
import com.github.izerui.imux.settings.PluginLanguage
import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.darcula.DarculaLaf
import com.intellij.mock.MockApplication
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.AnimatedIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.awt.Container
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.LookAndFeel
import javax.swing.UIManager

/**
 * 单次审查的结果在窄屏也要留下痕迹：
 * 审查一次完成自动解绑 → 结果提示条收起 → 窄屏把结果 chip 收起 → 悬停结对按钮仍能看到这次结果。
 */
class OneShotResultTraceTest {
    companion object {
        private val appDisposable = Disposer.newDisposable("OneShotResultTraceTest")
        private var originalLaf: LookAndFeel? = null

        @JvmStatic
        @BeforeClass
        fun installApp() {
            originalLaf = UIManager.getLookAndFeel()
            UIManager.setLookAndFeel(DarculaLaf())
            val app = MockApplication(appDisposable)
            ApplicationManager.setApplication(app, appDisposable)
            app.registerService(com.intellij.ui.ExperimentalUI::class.java, object : com.intellij.ui.ExperimentalUI() {
                override fun saveCurrentValueAndReapplyDefaultLaf() = Unit
            })
        }

        @JvmStatic
        @AfterClass
        fun uninstall() {
            Disposer.dispose(appDisposable)
            originalLaf?.let { UIManager.setLookAndFeel(it) }
        }
    }

    private val zh: PairMessages = { key, args -> ImuxBundle.message(PluginLanguage.SIMPLIFIED_CHINESE, key, *args.toTypedArray()) }
    private val zone = ZoneId.systemDefault()

    private class FakeWindow : TipWindow {
        var content: JComponent? = null
        override var isVisible = false

        override fun show(content: JComponent, owner: JComponent) {
            this.content = content
            isVisible = true
        }

        override fun repack() = Unit

        override fun close() {
            isVisible = false
        }
    }

    private fun texts(root: Container): List<String> =
        root.components.flatMap { child ->
            when (child) {
                is JLabel -> listOf(child.text)
                is Container -> texts(child)
                else -> emptyList()
            }
        }

    private fun fixedTool(state: ToolState) =
        object : SessionTool {
            override fun state() = state

            override fun perform(anchor: JComponent) = Unit
        }

    @Test
    fun `审查一次完成自动解绑、结果条收起、窄屏收起 chip 后，悬停结对按钮仍能看到结果`() {
        // 1. 审查一次：PASS 后自动解绑，结果保留
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val unbound = CountDownLatch(1)
        val coordinator =
            PeerCoordinator(
                project = testProject(),
                projectPath = "/tmp/test-project",
                model = SessionListModel(scan = { emptyList() }, clock = Instant::now),
                viewOf = { null },
                coroutineScope = scope,
                shell = "/bin/zsh",
                edtDispatcher = Dispatchers.Unconfined,
                peerMaxRounds = { 5 },
                peerAutoInject = { true },
                runCli = { _, _, _, _, _, _, _, _ -> "PASS" },
                resolveMcpConfig = { PeerMcpConfig(null, null, null) },
                buildPrompt = { _, _ -> "test prompt" },
                sendAutoFeedback = { _, _ -> true },
                onOneShotPassed = {},
            )
        try {
            coordinator.addStateListener(coordinator) { key ->
                if (key == "s1" && coordinator.status("s1") == null && coordinator.lastOutcome("s1") != null) unbound.countDown()
            }
            coordinator.reviewOnce("s1", AgentType.CODEX, null)
            assertTrue(unbound.await(5, TimeUnit.SECONDS))
            assertNull("已自动解绑", coordinator.status("s1"))
            val outcome = coordinator.lastOutcome("s1")
            assertNotNull(outcome)
            assertEquals(PeerOutcomeKind.PASSED, outcome!!.kind)

            // 2. 结果提示条显示满 8 秒后收起
            val stack = NoticeStack()
            stack.flash(NoticeSource.PAIR, Notice("pair:passed", NoticeTone.SUCCESS, "Codex 已审查，没有新的反馈"))
            stack.tick(NoticeStack.DEFAULT_TTL_MILLIS)
            assertTrue("结果条已收起", stack.visible().none { it.source == NoticeSource.PAIR })

            // 3. 结对回到未开启（IDLE），按钮只剩图标；窄屏下结果 chip 被收起
            val model = peerSegmentModel(coordinator.status("s1"), System.currentTimeMillis())
            assertEquals(PeerSegmentKind.IDLE, model.kind)
            val lastResult = lastResultLine(outcome, "Claude Code", zh, zone)
            val pairState = pairToolState(model, null, lastResult, "", null, AnimatedIcon.Default.INSTANCE, zh)
            val window = FakeWindow()
            val pair = ToolPill(fixedTool(pairState), pinned = true, tipWindow = window)
            val pills = listOf(pair) + listOf(AgentIcons.talk, AgentIcons.trail, AgentIcons.phone).map { ToolPill(fixedTool(ToolState(it, "", ""))) }
            val chip = ResultChip {}
            chip.icon = AllIcons.Status.Success
            chip.setResult(
                true,
                full = ImuxBundle.message(PluginLanguage.SIMPLIFIED_CHINESE, "action.peer.chip.passed", resultTime(outcome, zone)),
                short = ImuxBundle.message(PluginLanguage.SIMPLIFIED_CHINESE, "action.peer.chip.passed.short"),
            )
            val strip = TerminalToolStrip(chip, pills)
            val narrow = strip.component.insets.let { it.left + it.right } + pills.sumOf { it.iconWidth() } + com.intellij.util.ui.JBUI.scale(4) * 3
            strip.fit(narrow)
            assertFalse("窄屏下结果 chip 被收起", chip.isVisible)
            assertNull("未开启时按钮不带字", pairState.label)

            // 4. 悬停结对按钮，说明里仍有这次结果
            pair.hoverTip.hoverStarted()
            pair.hoverTip.showNow()
            val shown = texts(window.content!!).joinToString(" ")
            val expected = "上一轮 ${resultTime(outcome, zone)} · Codex：没有新的反馈"
            assertTrue("悬停能看到结果：$shown", shown.contains(expected))
        } finally {
            coordinator.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `结对中悬停也附上上一轮结果，没有结果或上一轮失败时不附`() {
        val ready = PeerSegmentModel(PeerSegmentKind.READY, "Codex")
        val withResult = pairToolState(ready, null, "上一轮 14:32 · Codex：没有新的反馈", "", null, AnimatedIcon.Default.INSTANCE, zh)
        assertTrue(withResult.description.endsWith("上一轮 14:32 · Codex：没有新的反馈"))
        assertEquals("Codex", withResult.label)
        assertNull(lastResultLine(null, "Claude Code", zh, zone))
    }

    @Test
    fun `结对菜单在非审查中时显示上一轮结果`() {
        val editor = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt")
        val menu = editor.bodyAfter("private fun showPeerMenu(anchor: JComponent)", '{')
        assertTrue(menu.contains("if (peerSegment.kind != PeerSegmentKind.RUNNING) {"))
        assertTrue(menu.contains("lastResultLine(coordinator.lastOutcome(key), virtualFile.agentType.displayName)?.let { group.add(Separator.create(it)) }"))
    }

    private fun testProject(): Project =
        Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { proxy, method, args ->
            when (method.name) {
                "getBasePath" -> "/tmp/test-project"
                "isDisposed" -> false
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "TestProject"
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        } as Project
}
