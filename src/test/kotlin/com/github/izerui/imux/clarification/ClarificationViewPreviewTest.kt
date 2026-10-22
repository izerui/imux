package com.github.izerui.imux.clarification

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.settings.PluginLanguage
import com.intellij.ide.ui.laf.darcula.DarculaLaf
import com.intellij.ui.IconManager
import com.intellij.ui.icons.CoreIconManager
import com.intellij.util.ui.JBUI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.AfterClass
import org.junit.BeforeClass
import com.intellij.mock.MockApplication
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import org.junit.Test
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.FlowLayout
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.text.JTextComponent

/**
 * 以实际渲染验收需求澄清窗口的排版：在 Darcula 下构建 [ClarificationView]，配上按 [buttonBar] 画出的按钮栏，
 * 写出 build/ui-preview/ 下的 PNG，并检查文字没有被横向截断、内容在滚动面板里、批次缩短不丢输入。
 */
class ClarificationViewPreviewTest {
    companion object {
        private val outDir = File("build/ui-preview")
        private val zh = ViewMessages { key, params -> ImuxBundle.message(PluginLanguage.SIMPLIFIED_CHINESE, key, *params) }

        private val appDisposable = Disposer.newDisposable("ClarificationViewPreviewTest")

        /**
         * UI DSL 的 collapsibleGroup 构造时要向 ActionManager 取折叠快捷键，平台测试框架没有引入，
         * 这里只放一个空的 ActionManager（查不到任何动作），测试结束后撤掉，不影响其它用例。
         */
        @JvmStatic
        @BeforeClass
        fun installLaf() {
            UIManager.setLookAndFeel(DarculaLaf())
            // 没有激活图标管理器时 AllIcons 全是占位的空图标；激活后预览里能看到真实的官方图标
            runCatching { IconManager.activate(CoreIconManager()) }
            val app = MockApplication(appDisposable)
            ApplicationManager.setApplication(app, appDisposable)
            app.registerService(ActionManager::class.java, PreviewActionManager())
            app.registerService(com.intellij.ui.ExperimentalUI::class.java, object : com.intellij.ui.ExperimentalUI() {
                override fun saveCurrentValueAndReapplyDefaultLaf() = Unit
            })
            app.registerService(com.intellij.openapi.client.ClientSessionsManager::class.java, com.intellij.openapi.client.ClientSessionsManager<com.intellij.openapi.client.ClientSession>(kotlinx.coroutines.GlobalScope))
            outDir.mkdirs()
        }

        @JvmStatic
        @AfterClass
        fun uninstall() {
            Disposer.dispose(appDisposable)
            IconManager.deactivate()
        }
    }

    private object NoCallbacks : ClarificationViewCallbacks {
        override fun editRequirement() = Unit

        override fun skipDirection(id: String) = Unit

        override fun pinDirection(id: String) = Unit

        override fun reopenDirection(id: String) = Unit

        override fun addDirection() = Unit

        override fun showAgentOutput(raw: String) = Unit
    }

    // ---- 夹具 ----

    private val requirement =
        "给结对编程加一个“需求澄清”入口：用户输入一句话需求后，先让 Agent 结合项目代码调查，找出没想到的方向，" +
            "逐个问用户拍板，最后把调整后的需求发回当前会话；整个过程不能打断主会话正在执行的这一轮。"

    private val directions =
        listOf(
            Direction("d1", "入口放在哪里：会话标签页右键菜单、工具窗口标题栏按钮，还是两处都放并共用同一个动作", "", "", "",
                status = DirectionStatus.DECIDED, conclusion = "两处都放，共用同一个 AnAction，标题栏用官方图标"),
            Direction("d2", "主会话正在执行时，调整后的需求什么时候发送才不会打断它", "", "", "", status = DirectionStatus.ACTIVE),
            Direction("d3", "需求文档路径的解析：相对路径以项目根目录为准，还是以当前终端的工作目录为准", "", "", "", status = DirectionStatus.OPEN),
            Direction("d4", "附带的最近对话最多带多少字，超出时从哪一端截断", "", "", "",
                status = DirectionStatus.DEFAULTED, conclusion = "按默认处理：最多 8000 字，保留最近的部分"),
            Direction("d5", "把澄清记录写入项目目录下的 .imux 文件夹，方便下次接着澄清", "", "", "",
                status = DirectionStatus.DROPPED, dropReason = "窗口关闭后流程仍保留在服务里，重新打开即可接着问，不需要落盘"),
        )

    private val longQuestion =
        Question(
            "d2",
            "主会话正在执行一轮任务时，你点了“交给 Agent”：是等这一轮结束后再把调整后的需求发进终端，还是立即打断当前这一轮并发送？" +
                "如果等待，等待期间用户关掉窗口或关掉会话标签页时，这段已经写好的需求应该怎么处理？",
            "立即发送会把文字插进 Agent 正在输出的内容中间，Claude Code 可能把它当成对确认框的回答",
            "PeerCoordinator.injectFeedback 在主会话忙时会排队重试；SessionMonitor.runningIds 能判断是否正在执行",
            listOf(
                ChoiceOption("a", "等这一轮结束后再发送，等待期间窗口显示“等待当前回合结束”，可以随时取消发送", true,
                    "主会话执行中排队，回合结束后立即发送；标签页关闭时通知并提供复制，需求不丢"),
                ChoiceOption("b", "立即发送，不管主会话是否正在执行", false,
                    "总是立即发送；可能打断正在执行的一轮，也可能被当成确认框的回答"),
                ChoiceOption("c", "弹出确认框，让用户每次自己决定是等待还是立即发送，并记住这次的选择作为下次的默认值", false,
                    "每次发送前询问一次，记住上次的选择作为默认"),
            ),
        )

    private val laterQuestion1 =
        Question("d3", "需求文档的相对路径以哪里为准？", "", "", listOf(ChoiceOption("a", "项目根目录", true, "以项目根目录解析相对路径")))
    private val laterQuestion2 =
        Question("d4", "附带对话最多多少字？", "", "", listOf(ChoiceOption("a", "8000 字", true, "最多 8000 字")))

    private val askingLedger =
        Ledger(requirement, null, understanding = "你想在动手前多一道澄清：Agent 先读代码找出遗漏，再一题一题问你，最后把补全后的需求原样交回当前会话，主会话不受打扰。", directions = directions)

    private val resultText =
        """
        |给结对编程加一个“需求澄清”入口。
        |
        |1. 入口：会话标签页右键菜单与工具窗口标题栏各放一个，共用同一个 AnAction，标题栏使用官方图标。
        |2. 发送时机：主会话执行中排队，等这一轮结束后再发送；等待期间可以取消；标签页已关闭时通知并提供复制。
        |3. 需求文档路径：相对路径以项目根目录解析。
        |4. 附带对话：最多 8000 字，超出时保留最近的部分。
        |5. 不落盘：窗口关闭后流程保留在服务里，重新打开即可接着澄清。
        |
        |验收：主会话执行中点击“交给 Agent”不会打断当前一轮；关闭标签页时不会丢失已写好的需求；窗口缩小时底部按钮始终可见，长文字自动换行。
        """.trimMargin()

    // ---- 渲染 ----

    private fun view() = ClarificationView(NoCallbacks, zh)

    private fun barFor(state: ClarificationState, canFinish: Boolean, ledger: Ledger): JPanel {
        val bar = buttonBar(state, canFinish, ledger)
        fun buttons(list: List<BarButton>) =
            JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(8), 0)).apply {
                isOpaque = false
                list.forEach { add(JButton(ImuxBundle.message(PluginLanguage.SIMPLIFIED_CHINESE, it.labelKey)).apply { name = it.name }) }
            }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(12)
            add(buttons(bar.left), BorderLayout.WEST)
            add(buttons(bar.right), BorderLayout.EAST)
        }
    }

    /** 视图 + 按钮栏组成整个窗口内容，按给定尺寸布局；返回根面板。 */
    private fun window(view: ClarificationView, bar: JPanel, width: Int, height: Int, default: BarButton): JRootPane {
        val content =
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(12, 16, 12, 16)
                add(view.component, BorderLayout.CENTER)
                add(bar, BorderLayout.SOUTH)
            }
        val root = JRootPane().apply { contentPane = content }
        root.defaultButton = allOf<JButton>(bar).first { it.name == default.name }
        root.setSize(width, height)
        // 自动换行的文字的首选高度取决于上一次布局给的宽度，布局几遍才稳定
        // 没有窗口 peer 时 validate() 不会布局，逐层 doLayout
        repeat(4) {
            invalidateTree(root)
            layoutTree(root)
        }
        return root
    }

    private fun layoutTree(c: Component) {
        if (c is Container) {
            c.doLayout()
            c.components.forEach(::layoutTree)
        }
    }

    private fun invalidateTree(c: Component) {
        c.invalidate()
        if (c is Container) c.components.forEach(::invalidateTree)
    }

    private fun png(root: JRootPane, name: String): File {
        val image = BufferedImage(root.width, root.height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = root.contentPane.background
        g.fillRect(0, 0, root.width, root.height)
        root.printAll(g)
        g.dispose()
        val file = File(outDir, "$name.png")
        ImageIO.write(image, "png", file)
        return file
    }

    private fun tree(c: Component): List<Component> = listOf(c) + ((c as? Container)?.components?.flatMap(::tree) ?: emptyList())

    private inline fun <reified T : Component> allOf(c: Component): List<T> = tree(c).filterIsInstance<T>()

    private fun showing(c: Component, root: Component): Boolean {
        var x: Component? = c
        while (x != null && x !== root) {
            if (!x.isVisible) return false
            x = x.parent
        }
        return true
    }

    /**
     * 文字组件不得被横向截断：每个可见的标签、按钮、文本组件都落在滚动视口的可见宽度内，
     * 不换行的标签与按钮拿到的宽度不小于首选宽度（需求行按设计省略显示，除外）。
     */
    private fun assertNoHorizontalClipping(view: ClarificationView, root: JRootPane, name: String) {
        val viewport = view.component.viewport
        val visibleWidth = viewport.width
        assertTrue("$name：视口有宽度", visibleWidth > 0)
        val texts = allOf<JComponent>(viewport.view).filter { (it is JLabel || it is AbstractButton || it is JTextComponent) && showing(it, root) }
        assertTrue("$name：有文字组件", texts.isNotEmpty())
        for (c in texts) {
            val r: Rectangle = SwingUtilities.convertRectangle(c.parent, c.bounds, viewport.view)
            val label = (c as? JLabel)?.text ?: (c as? AbstractButton)?.text ?: c.javaClass.simpleName
            assertTrue("$name：「$label」左边越界 x=${r.x}", r.x >= 0)
            assertTrue("$name：「$label」右边超出视口 ${r.x + r.width} > $visibleWidth", r.x + r.width <= visibleWidth)
            val ellipsized = c.javaClass.simpleName == "EllipsisLabel"
            if ((c is JLabel || c is AbstractButton) && !ellipsized && c !is WrappingRadioButton) {
                assertTrue("$name：「$label」被截断 ${c.width} < ${c.preferredSize.width}", c.width >= c.preferredSize.width)
            }
        }
        // 按钮栏同样不能被挤出窗口
        for (b in allOf<JButton>(root.contentPane).filter { it.isShowing || showing(it, root) }) {
            val r = SwingUtilities.convertRectangle(b.parent, b.bounds, root)
            if (SwingUtilities.isDescendingFrom(b, viewport)) continue
            assertTrue("$name：按钮「${b.text}」超出窗口", r.x >= 0 && r.x + r.width <= root.width)
            assertTrue("$name：按钮「${b.text}」被截断", b.width >= b.preferredSize.width)
            assertTrue("$name：按钮栏在窗口内可见", r.y + r.height <= root.height)
        }
    }

    private fun check(
        name: String,
        state: ClarificationState,
        ledger: Ledger,
        width: Int,
        height: Int,
        canFinish: Boolean = true,
        expandList: Boolean = false,
        prefill: String = "",
        prepare: (ClarificationView) -> Unit = {},
    ): ClarificationView {
        val view = view()
        view.setListExpanded(expandList)
        view.render(state, ledger, canFinish, prefill)
        prepare(view)
        val root = window(view, barFor(state, canFinish, ledger), width, height, buttonBar(state, canFinish, ledger).default)
        val file = png(root, name)
        assertTrue("$name 已写出", file.length() > 0)
        assertTrue("$name：内容区在 JScrollPane 里", view.component is JScrollPane)
        assertNoHorizontalClipping(view, root, name)
        return view
    }

    // ---- 用例 ----

    @Test
    fun `输入页在两种尺寸下渲染且输入框填满剩余高度`() {
        for ((w, h) in listOf(640 to 480, 480 to 360)) {
            val view = check("input-${w}x$h", ClarificationState.Input, Ledger("", null), w, h) { it.setBackgroundAvailable(true) }
            val area = allOf<javax.swing.JTextArea>(view.component).single()
            val viewport = view.component.viewport
            val areaPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, area)!!
            // 输入框占了视口的大半高度，而不是缩在顶部留下一片空白
            assertTrue("${w}x$h：输入框高度 ${areaPane.height} 应超过视口 ${viewport.height} 的一半", areaPane.height * 2 > viewport.height)
        }
    }

    @Test
    fun `提问页长问题在两种尺寸下换行且不截断`() {
        val state = ClarificationState.Asking(listOf(longQuestion, laterQuestion1, laterQuestion2), 0)
        // 最后一张足够高，整页内容都在画面里，用来检查下半部分（选项、其他想法）的换行
        for ((w, h) in listOf(640 to 480, 460 to 380, 460 to 980)) {
            val view = check("asking-${w}x$h", state, askingLedger, w, h)
            assertNotNull(view.asking)
        }
    }

    @Test
    fun `方向清单展开时五个方向都显示且不截断`() {
        val state = ClarificationState.Asking(listOf(longQuestion), 0)
        val view = check("asking-list-expanded-640x560", state, askingLedger.copy(understanding = null), 640, 560, expandList = true)
        val texts = allOf<javax.swing.JEditorPane>(view.component).map { it.document.getText(0, it.document.length) }
        for (d in directions) assertTrue("清单里有「${d.id}」", texts.any { it.contains(d.title.take(8)) })
        assertTrue("去掉的方向原样显示理由", texts.any { it.contains("不需要落盘") })
    }

    @Test
    fun `结果页在两种尺寸下渲染且编辑框填满剩余高度`() {
        for ((w, h) in listOf(640 to 480, 460 to 360)) {
            val view = check("result-${w}x$h", ClarificationState.Result(resultText), askingLedger, w, h, canFinish = false)
            assertEquals(resultText, view.resultText())
        }
    }

    @Test
    fun `调查中与失败页渲染`() {
        val first = Ledger(requirement, null)
        check("investigating-640x480", ClarificationState.Investigating("正在读取 src/main/kotlin/com/github/izerui/imux/clarification/ClarificationFlow.kt", System.currentTimeMillis() - 42_000), first, 640, 480)
        check(
            "investigating-round-640x480",
            ClarificationState.Investigating("正在搜索 PeerCoordinator", System.currentTimeMillis() - 7_000),
            askingLedger,
            640,
            480,
        )
        check(
            "failed-640x480",
            ClarificationState.Failed(FailureReason.AGENT_ERROR, "claude 进程以退出码 1 结束：Error: connect ECONNREFUSED 127.0.0.1:443，请检查网络或代理设置后重试", "{\"raw\":true}"),
            askingLedger,
            640,
            480,
            canFinish = false,
        )
        check("sending-640x480", ClarificationState.Sending(resultText, waitingForTurn = true), askingLedger, 640, 480, canFinish = false)
    }

    @Test
    fun `调查中的进度与已用秒数就地更新`() {
        val view = view()
        val started = 1_000_000L
        view.render(ClarificationState.Investigating(null, started), Ledger(requirement, null), true, "")
        val panes = allOf<javax.swing.JEditorPane>(view.component)
        fun plain(p: javax.swing.JEditorPane) = p.document.getText(0, p.document.length)

        view.render(ClarificationState.Investigating("正在读取 build.gradle.kts", started), Ledger(requirement, null), true, "")
        assertEquals("进度变化不重建", panes, allOf<javax.swing.JEditorPane>(view.component))
        assertTrue("进度文字就地更新", panes.any { plain(it).contains("build.gradle.kts") })

        view.tick(started + 65_000)
        assertTrue("已用秒数就地更新", panes.any { plain(it).contains("65") && plain(it).contains("30 秒到 2 分钟") })
    }

    @Test
    fun `输入页附带背景只在会话记录存在时生效`() {
        val view = view()
        view.render(ClarificationState.Input, Ledger("", null), false, "旧需求")
        assertEquals("输入框预填", "旧需求", view.inputText())
        assertEquals("会话记录未确认存在时不附带", false, view.includeBackground())
        view.setBackgroundAvailable(true)
        assertEquals("存在时默认附带", true, view.includeBackground())
    }

    @Test
    fun `跳过这一批后面的题时当前题的选择、其他想法与纠正文字都保留`() {
        val view = view()
        val full = ClarificationState.Asking(listOf(longQuestion, laterQuestion1, laterQuestion2), 0)
        view.render(full, askingLedger, true, "")
        val before = view.asking!!
        before.otherField.text = "先等，但超过五分钟就提示用户"
        before.radios[1].isSelected = true
        // 点「纠正理解」展开纠正输入框，和用户的操作一样
        allOf<JButton>(view.component).single { it.text == ImuxBundle.message(PluginLanguage.SIMPLIFIED_CHINESE, "clarify.understanding.correct") }.doClick()
        assertTrue("纠正输入框已展开", showing(before.correctionArea!!, view.component))
        before.correctionArea!!.text = "不是结对编程专用，普通会话也要能用"
        val root = window(view, barFor(full, true, askingLedger), 640, 560, buttonBar(full, true, askingLedger).default)
        png(root, "asking-correction-640x560")
        assertNoHorizontalClipping(view, root, "asking-correction-640x560")
        val headerBefore = before.header

        // 跳过了 d4：这一批变短，当前题不变
        view.render(full.copy(batch = listOf(longQuestion, laterQuestion1)), askingLedger, true, "")
        val after = view.asking!!
        assertSame("提问页没有重建", before, after)
        assertSame("同一个选项按钮实例", before.radios[1], after.radios[1])
        assertTrue("原来选中的选项仍然选中", after.radios[1].isSelected)
        assertSame("同一个其他想法输入框", before.otherField, after.otherField)
        assertEquals("其他想法的文字不变", "先等，但超过五分钟就提示用户", after.otherField.text)
        assertEquals("纠正文字不变", "不是结对编程专用，普通会话也要能用", after.correctionArea!!.text)
        assertEquals("纠正仍会随回答发出", "不是结对编程专用，普通会话也要能用", view.correctionText())
        assertSame("同一个题号说明", headerBefore, after.header)
        assertTrue("题号就地更新为 1/2：${after.header.text}", after.header.text.contains("1/2"))
        assertEquals("当前回答仍是选中的选项", Answer.Picked("b"), view.currentAnswer())
    }
}

/** 只够 UI DSL 构造折叠分组用的 ActionManager：查不到任何动作，其余操作不支持。 */
private class PreviewActionManager : ActionManager() {
    override fun createActionPopupMenu(place: String, group: com.intellij.openapi.actionSystem.ActionGroup) = unsupported()

    override fun createActionToolbar(place: String, group: com.intellij.openapi.actionSystem.ActionGroup, horizontal: Boolean) = unsupported()

    override fun getAction(actionId: String): com.intellij.openapi.actionSystem.AnAction? = null

    override fun getId(action: com.intellij.openapi.actionSystem.AnAction): String? = null

    override fun registerAction(actionId: String, action: com.intellij.openapi.actionSystem.AnAction) = unsupported()

    override fun registerAction(actionId: String, action: com.intellij.openapi.actionSystem.AnAction, pluginId: com.intellij.openapi.extensions.PluginId?) = unsupported()

    override fun unregisterAction(actionId: String) = unsupported()

    override fun replaceAction(actionId: String, newAction: com.intellij.openapi.actionSystem.AnAction) = unsupported()

    @Deprecated("unused")
    override fun getActionIds(idPrefix: String): Array<String> = emptyArray()

    override fun getActionIdList(idPrefix: String): List<String> = emptyList()

    override fun isGroup(actionId: String): Boolean = false

    override fun getActionOrStub(id: String): com.intellij.openapi.actionSystem.AnAction? = null

    override fun addTimerListener(listener: com.intellij.openapi.actionSystem.TimerListener) = Unit

    override fun removeTimerListener(listener: com.intellij.openapi.actionSystem.TimerListener) = Unit

    override fun tryToExecute(
        action: com.intellij.openapi.actionSystem.AnAction,
        inputEvent: java.awt.event.InputEvent?,
        contextComponent: java.awt.Component?,
        place: String?,
        now: Boolean,
    ) = unsupported()

    override fun getKeyboardShortcut(actionId: String): com.intellij.openapi.actionSystem.KeyboardShortcut? = null

    private fun unsupported(): Nothing = throw UnsupportedOperationException()
}
