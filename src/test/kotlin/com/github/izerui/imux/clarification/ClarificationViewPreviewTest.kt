package com.github.izerui.imux.clarification

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.settings.PluginLanguage
import com.intellij.ide.ui.laf.darcula.DarculaLaf
import com.intellij.mock.MockApplication
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.IconManager
import com.intellij.ui.icons.CoreIconManager
import com.intellij.util.ui.JBUI
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.FlowLayout
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
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
import javax.swing.KeyStroke
import javax.swing.LookAndFeel
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.text.JTextComponent

/**
 * 以实际渲染验收需求澄清窗口：在 Darcula 下构建 [ClarificationView]，配上按 [buttonBar] 画出的按钮栏，
 * 写出 build/ui-preview/ 下的 PNG，并检查文字没有被横向截断、内容在滚动面板里、
 * 640×520 下题目与全部选项不用滚动就能看到；同时用重建计数验收"不反复渲染"。
 */
class ClarificationViewPreviewTest {
    companion object {
        private val outDir = File("build/ui-preview")
        private val zh = ViewMessages { key, params -> ImuxBundle.message(PluginLanguage.SIMPLIFIED_CHINESE, key, *params) }
        private val appDisposable = Disposer.newDisposable("ClarificationViewPreviewTest")
        private var originalLaf: LookAndFeel? = null
        private var activatedIcons = false

        @JvmStatic
        @BeforeClass
        fun installLaf() {
            originalLaf = UIManager.getLookAndFeel()
            UIManager.setLookAndFeel(DarculaLaf())
            // 没有激活图标管理器时 AllIcons 全是占位的空图标；激活后预览里能看到真实的官方图标
            activatedIcons = runCatching { IconManager.activate(CoreIconManager()) }.isSuccess
            // UI DSL 与 Darcula 构造组件时要用到的最少服务，测试结束随 appDisposable 一并撤掉
            val app = MockApplication(appDisposable)
            ApplicationManager.setApplication(app, appDisposable)
            app.registerService(com.intellij.ui.ExperimentalUI::class.java, object : com.intellij.ui.ExperimentalUI() {
                override fun saveCurrentValueAndReapplyDefaultLaf() = Unit
            })
            app.registerService(
                com.intellij.openapi.client.ClientSessionsManager::class.java,
                com.intellij.openapi.client.ClientSessionsManager<com.intellij.openapi.client.ClientSession>(kotlinx.coroutines.GlobalScope),
            )
            outDir.mkdirs()
        }

        @JvmStatic
        @AfterClass
        fun uninstall() {
            Disposer.dispose(appDisposable)
            if (activatedIcons) IconManager.deactivate()
            originalLaf?.let { UIManager.setLookAndFeel(it) }
        }
    }

    /** 草稿存在这里（对话框里是存在流程上）；记下按钮栏需要刷新的次数。 */
    private class FakeCallbacks(private val preset: (BatchDrafts) -> Unit = {}) : ClarificationViewCallbacks {
        val drafts = mutableMapOf<List<Question>, BatchDrafts>()
        var pageChanges = 0

        override fun draftsFor(batch: List<Question>): BatchDrafts = drafts.getOrPut(batch) { BatchDrafts(batch).also(preset) }

        override fun askingPageChanged() {
            pageChanges++
        }

        override fun showAgentOutput(raw: String) = Unit
    }

    // ---- 夹具 ----

    private val requirement =
        "给终端增加断线重连：网络抖动或远程主机重启后，会话标签页能自动恢复连接，用户不用手动关掉再开一个新标签页，" +
            "也不会丢掉正在看的输出。"

    private fun confirmedAnswer(text: String) = AnswerRecord(Question("c", "H", "Q", false, emptyList()), Answer.OwnWords(text), text)

    private val record =
        ClarificationRecord(
            requirement,
            null,
            listOf(confirmedAnswer("断线后自动重连 3 次，间隔逐次加倍"), confirmedAnswer("多个标签页同时断线时逐个重连，不并发")),
        )

    private val longQuestion =
        Question(
            "d3", "失败提示",
            "重连 3 次都失败后，怎么告诉用户？如果用户此时正在别的标签页里工作，是否需要打断他（例如弹出通知），" +
                "还是等他切回这个标签页时再看到失败状态？",
            false,
            listOf(
                ChoiceOption("a", "显示错误并提供重试", "失败时在标签页里显示错误原因，并给出「重试」按钮", true),
                ChoiceOption("b", "只显示断开状态", "标签页标题显示断开，不提供重试入口，用户自己重新打开", false),
                ChoiceOption("c", "弹出 IDE 通知", "失败时弹出 IDE 通知，点击通知跳到这个标签页并显示重试", false),
            ),
            "失败时只在标签页标题上显示断开状态",
        )

    private val methodQuestion =
        Question(
            "d4", "重连方式", "断线后用什么方式重新连接？", false,
            listOf(
                ChoiceOption("a", "沿用 SSH 配置", "直接用标签页原来的 SSH 配置重连，不再询问", true),
                ChoiceOption("b", "每次询问", "每次重连前弹框确认目标主机与凭据", false),
            ),
            "沿用现有的 SSH 配置直接重连",
        )

    private val scopeQuestion =
        Question(
            "d5", "恢复范围", "重连成功后要恢复哪些内容？可以多选。", true,
            listOf(
                ChoiceOption("a", "工作目录", "重连后回到断线前的工作目录", true),
                ChoiceOption("b", "环境变量", "重新导出断线前会话里设置的环境变量", false),
                ChoiceOption("c", "最近输出", "保留断线前的输出，接在后面继续显示", false),
                ChoiceOption("d", "正在运行的命令", "断线前正在运行的命令在重连后重新执行", false),
            ),
            "只恢复连接，不恢复输出与工作目录",
        )

    private val batch = listOf(longQuestion, methodQuestion, scopeQuestion)

    private val resultText =
        """
        |给终端增加断线重连。
        |
        |1. 断线后自动重连 3 次，间隔逐次加倍；多个标签页同时断线时逐个重连，不并发。
        |2. 重连沿用标签页原来的 SSH 配置，不再询问。
        |3. 3 次都失败时在标签页里显示错误原因，并给出「重试」按钮。
        |4. 重连成功后回到断线前的工作目录，并保留断线前的输出。
        """.trimMargin()

    // ---- 渲染 ----

    private fun barFor(view: ClarificationView, state: ClarificationState, canFinish: Boolean, record: ClarificationRecord): JPanel {
        val bar = buttonBar(state, canFinish, record, view.askingPage())
        fun buttons(list: List<BarButton>) =
            JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(8), 0)).apply {
                isOpaque = false
                list.forEach { add(JButton(ImuxBundle.message(PluginLanguage.SIMPLIFIED_CHINESE, it.labelKey)).apply { name = it.name }) }
            }
        // 与 DialogWrapper 的按钮栏一样，留白属于按钮栏自己
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(12, 16)
            add(buttons(bar.left), BorderLayout.WEST)
            add(buttons(bar.right), BorderLayout.EAST)
        }
    }

    /** 视图 + 按钮栏组成整个窗口内容，按给定尺寸布局；返回根面板。 */
    private fun window(view: ClarificationView, state: ClarificationState, canFinish: Boolean, record: ClarificationRecord, width: Int, height: Int): JRootPane {
        val bar = barFor(view, state, canFinish, record)
        // 与真实窗口一致：内容区直接贴着窗口边缘放，不额外加边框——内边距必须来自视图自己
        val content =
            JPanel(BorderLayout()).apply {
                add(view.component, BorderLayout.CENTER)
                add(bar, BorderLayout.SOUTH)
            }
        val root = JRootPane().apply { contentPane = content }
        val default = buttonBar(state, canFinish, record, view.askingPage()).default
        root.defaultButton = default?.let { d -> allOf<JButton>(bar).first { it.name == d.name } }
        root.setSize(width, height)
        // 自动换行的文字的首选高度取决于上一次布局给的宽度，布局几遍才稳定；没有窗口 peer 时逐层 doLayout
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
     * 不换行的标签与按钮拿到的宽度不小于首选宽度（需求行、进度行按设计省略显示，除外）。
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
            if ((c is JLabel || c is AbstractButton) && !ellipsized) {
                assertTrue("$name：「$label」被截断 ${c.width} < ${c.preferredSize.width}", c.width >= c.preferredSize.width)
            }
        }
        for (b in allOf<JButton>(root.contentPane).filter { showing(it, root) && !SwingUtilities.isDescendingFrom(it, viewport) }) {
            val r = SwingUtilities.convertRectangle(b.parent, b.bounds, root)
            assertTrue("$name：按钮「${b.text}」超出窗口", r.x >= 0 && r.x + r.width <= root.width)
            assertTrue("$name：按钮「${b.text}」被截断", b.width >= b.preferredSize.width)
            assertTrue("$name：按钮栏在窗口内可见", r.y + r.height <= root.height)
        }
    }

    /** 题目、全部选项与"其他"都在视口里，不用滚动。 */
    private fun assertQuestionFits(view: ClarificationView, name: String) {
        val viewport = view.component.viewport
        val w = view.options!!
        for (c in w.buttons + w.otherButton + w.otherField) {
            val r = SwingUtilities.convertRectangle(c.parent, c.bounds, viewport)
            assertTrue("$name：「${(c as? AbstractButton)?.text ?: "其他输入框"}」在视口内 y=${r.y + r.height} > ${viewport.height}", r.y >= 0 && r.y + r.height <= viewport.height)
        }
        assertTrue("$name：内容不需要竖向滚动", view.component.viewport.view.preferredSize.height <= viewport.height)
    }

    /**
     * 内容与窗口左右边缘之间留出内边距：每个可见的文字、按钮、输入框、标签条、分隔线（窗口坐标）
     * 离左右边缘都不少于 [CONTENT_PADDING_H]。多行输入框按它所在的滚动面板算，标签条内部的标签不单独算。
     */
    private fun assertEdgePadding(view: ClarificationView, root: JRootPane, name: String) {
        val pad = JBUI.scale(CONTENT_PADDING_H)
        val inner = tree(view.component.viewport.view).filter { c ->
            c !== view.component.viewport.view &&
                (c is JScrollPane || c is javax.swing.JTabbedPane)
        }
        val checked =
            tree(view.component.viewport.view).filter { c ->
                showing(c, root) && c.width > 0 &&
                    (c is JLabel || c is AbstractButton || c is JTextComponent || c is javax.swing.JTabbedPane || c is javax.swing.JSeparator || c is JScrollPane) &&
                    inner.none { it !== c && SwingUtilities.isDescendingFrom(c, it) }
            }
        assertTrue("$name：有要检查的组件", checked.isNotEmpty())
        for (c in checked) {
            val r = SwingUtilities.convertRectangle(c.parent, c.bounds, root)
            val label = (c as? JLabel)?.text ?: (c as? AbstractButton)?.text ?: c.javaClass.simpleName
            assertTrue("$name：「$label」离左边缘 ${r.x} < $pad", r.x >= pad)
            assertTrue("$name：「$label」离右边缘 ${root.width - (r.x + r.width)} < $pad", r.x + r.width <= root.width - pad)
        }
    }

    private fun check(
        name: String,
        state: ClarificationState,
        record: ClarificationRecord,
        width: Int,
        height: Int,
        canFinish: Boolean = true,
        drafts: (BatchDrafts) -> Unit = {},
        prepare: (ClarificationView) -> Unit = {},
    ): ClarificationView {
        val view = ClarificationView(FakeCallbacks(drafts), zh)
        view.render(state, record, canFinish, "")
        prepare(view)
        val root = window(view, state, canFinish, record, width, height)
        val file = png(root, name)
        assertTrue("$name 已写出", file.length() > 0)
        assertTrue("$name：内容区在 JScrollPane 里", view.component is JScrollPane)
        assertNoHorizontalClipping(view, root, name)
        assertEdgePadding(view, root, name)
        return view
    }

    // ---- 渲染验收 ----

    @Test
    fun `输入页在两种尺寸下渲染且输入框填满剩余高度`() {
        for ((w, h) in listOf(640 to 520, 460 to 380)) {
            val view = check("input-${w}x$h", ClarificationState.Input, ClarificationRecord("", null), w, h, canFinish = false) { v -> v.setBackgroundAvailable(true) }
            val area = allOf<javax.swing.JTextArea>(view.component).single()
            val viewport = view.component.viewport
            val areaPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, area)!!
            assertTrue("${w}x$h：输入框高度 ${areaPane.height} 应超过视口 ${viewport.height} 的一半", areaPane.height * 2 > viewport.height)
        }
    }

    @Test
    fun `调查中显示进度、秒数与两条已确认`() {
        val state = ClarificationState.Investigating("读取 src/main/kotlin/com/github/izerui/imux/terminal/TerminalSessionReconnector.kt", System.currentTimeMillis() - 42_000, run = 3)
        val view = check("investigating-640x520", state, record, 640, 520)
        val texts = allOf<javax.swing.JEditorPane>(view.component).map { it.document.getText(0, it.document.length) }
        assertTrue("显示第一条已确认", texts.any { it.contains("断线后自动重连 3 次") })
        assertTrue("显示第二条已确认", texts.any { it.contains("多个标签页同时断线时逐个重连") })
        assertTrue("显示已用秒数", texts.any { it.contains("42") })
    }

    @Test
    fun `只有一题时没有标签条且题目与选项不用滚动`() {
        val view = check("asking-single-640x520", ClarificationState.Asking(listOf(methodQuestion)), record, 640, 520)
        assertEquals("一题没有标签条", null, view.tabs)
        assertEquals("主按钮是提交", AskingPage.SINGLE, view.askingPage())
        assertQuestionFits(view, "asking-single-640x520")
    }

    @Test
    fun `三题一批的长问题在两种尺寸下换行且不截断`() {
        // 第二题已经答过，标签上应有 ✓
        val drafts: (BatchDrafts) -> Unit = { it.draftOf(methodQuestion).picked += "a" }
        val big = check("asking-batch-640x520", ClarificationState.Asking(batch), record, 640, 520, drafts = drafts)
        val strip = big.tabs!!
        assertEquals("标签条：三题加提交页", 4, strip.tabCount)
        for (i in 0 until strip.tabCount) {
            val icon = (strip.getTabComponentAt(i) as JLabel).icon
            assertNotNull("第 ${i + 1} 个标签有图标位", icon)
            assertEquals("第 ${i + 1} 个标签的图标位宽度一致", JBUI.scale(16), icon.iconWidth)
        }
        assertEquals("tooltip 是完整标签与问句", "失败提示 — " + longQuestion.text, strip.getToolTipTextAt(0))
        assertQuestionFits(big, "asking-batch-640x520")
        check("asking-batch-460x400", ClarificationState.Asking(batch), record, 460, 400, drafts = drafts)
    }

    @Test
    fun `多选题渲染为复选框且其他是额外一项`() {
        val view =
            check("asking-multi-640x520", ClarificationState.Asking(batch), record, 640, 520) { v ->
                v.selectTab(2)
                v.options!!.buttons[0].isSelected = true
                v.options!!.buttons[2].isSelected = true
                v.options!!.otherField.text = "终端标题也恢复"
            }
        val w = view.options!!
        assertTrue("多选题用复选框", w.buttons.all { it is javax.swing.JCheckBox })
        assertTrue("其他也是复选框", w.otherButton is javax.swing.JCheckBox)
        assertEquals(Answer.Picked(listOf("a", "c"), "终端标题也恢复"), view.answers()["d5"])
        assertQuestionFits(view, "asking-multi-640x520")
    }

    @Test
    fun `提交页逐题列出回答，没答的写明默认处理`() {
        val view =
            check(
                "asking-review-640x520", ClarificationState.Asking(batch), record, 640, 520,
                drafts = { d ->
                    d.draftOf(longQuestion).picked += "a"
                    d.draftOf(scopeQuestion).picked += listOf("a", "c")
                },
            ) { v -> v.selectTab(3) }
        assertEquals(AskingPage.REVIEW, view.askingPage())
        val texts = allOf<javax.swing.JEditorPane>(view.component).map { it.document.getText(0, it.document.length) }
        assertTrue("列出第一题的回答", texts.any { it.contains("显示错误并提供重试") })
        assertTrue("没答的第二题写明默认处理", texts.any { it.contains("沿用现有的 SSH 配置直接重连") })
        assertTrue("多选的回答都列出", texts.any { it.contains("工作目录") && it.contains("最近输出") })
    }

    @Test
    fun `结果页在两种尺寸下渲染且编辑框填满剩余高度`() {
        for ((w, h) in listOf(640 to 520, 460 to 380)) {
            val view = check("result-${w}x$h", ClarificationState.Result(resultText), record, w, h, canFinish = false)
            assertEquals(resultText, view.resultText())
            val areaPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, allOf<javax.swing.JTextArea>(view.component).single())!!
            assertTrue("${w}x$h：编辑框占视口大半", areaPane.height * 2 > view.component.viewport.height)
        }
    }

    @Test
    fun `失败页渲染`() {
        check(
            "failed-640x520",
            ClarificationState.Failed(FailureReason.AGENT_ERROR, "claude 进程以退出码 1 结束：Error: connect ECONNREFUSED 127.0.0.1:443，请检查网络或代理设置后重试", "{\"raw\":true}"),
            record,
            640,
            520,
            canFinish = false,
        )
    }

    // ---- 不反复渲染 ----

    @Test
    fun `一百次进度更新不重建中间区域也不重建头部`() {
        val view = ClarificationView(FakeCallbacks(), zh)
        view.render(ClarificationState.Investigating(null, 1_000L, run = 7), record, true, "")
        val centers = view.centerRebuilds
        val headers = view.headerRebuilds
        val label = allOf<JLabel>(view.component).single { it.javaClass.simpleName == "EllipsisLabel" && it.text.contains("调查") }
        repeat(100) { i -> view.render(ClarificationState.Investigating("读取 File$i.kt", 1_000L, run = 7), record, true, "") }
        assertEquals("中间区域没有重建", centers, view.centerRebuilds)
        assertEquals("头部没有重建", headers, view.headerRebuilds)
        assertTrue("进度就地更新：${label.text}", label.text.contains("File99.kt"))
        view.tick(1_000L + 65_000)
        val elapsed = allOf<javax.swing.JEditorPane>(view.component).map { it.document.getText(0, it.document.length) }
        assertTrue("已用秒数就地更新", elapsed.any { it.contains("65") })
    }

    @Test
    fun `换标签只重建题目区域一次，切回来恢复原来的选择与其他文字`() {
        val callbacks = FakeCallbacks()
        val view = ClarificationView(callbacks, zh)
        view.render(ClarificationState.Asking(batch), record, true, "")
        val centers = view.centerRebuilds
        view.options!!.buttons[1].isSelected = true
        val before = view.questionRebuilds

        view.selectTab(1)
        assertEquals("换到第二题只重建题目区域一次", before + 1, view.questionRebuilds)
        assertEquals("中间区域没有重建", centers, view.centerRebuilds)
        assertEquals("按钮栏收到一次换页通知", 1, callbacks.pageChanges)
        view.options!!.otherField.text = "先问用户再重连"

        view.selectTab(0)
        assertTrue("第一题原来的选择恢复", view.options!!.buttons[1].isSelected)
        view.selectTab(1)
        assertEquals("第二题的其他文字恢复", "先问用户再重连", view.options!!.otherField.text)
        assertTrue("第二题的其他仍是选中的", view.options!!.otherButton.isSelected)
        assertEquals(Answer.OwnWords("先问用户再重连"), view.answers()["d4"])
        assertEquals(Answer.Picked(listOf("b")), view.answers()["d3"])
    }

    @Test
    fun `已确认内容不变时头部不重建，变了才重建`() {
        val view = ClarificationView(FakeCallbacks(), zh)
        view.render(ClarificationState.Asking(batch), record, true, "")
        val headers = view.headerRebuilds
        view.render(ClarificationState.Investigating(null, 5L, run = 9), record.copy(background = "新的背景"), true, "")
        assertEquals("已确认不变：头部不重建", headers, view.headerRebuilds)
        val more = record.copy(history = record.history + confirmedAnswer("失败时显示错误并提供重试"))
        view.render(ClarificationState.Investigating(null, 5L, run = 9), more, true, "")
        assertEquals("已确认多了一条：头部重建一次", headers + 1, view.headerRebuilds)
    }

    @Test
    fun `同一批问题再次通知不重建提问页`() {
        val view = ClarificationView(FakeCallbacks(), zh)
        view.render(ClarificationState.Asking(batch), record, true, "")
        val centers = view.centerRebuilds
        val questions = view.questionRebuilds
        repeat(10) { view.render(ClarificationState.Asking(batch), record, true, "") }
        assertEquals(centers, view.centerRebuilds)
        assertEquals(questions, view.questionRebuilds)
    }

    // ---- 键盘 ----

    private fun fire(target: JComponent, condition: Int, key: Int) {
        val name = target.getInputMap(condition).get(KeyStroke.getKeyStroke(key, 0))
        assertNotNull("${KeyEvent.getKeyText(key)} 有绑定", name)
        target.actionMap.get(name)!!.actionPerformed(ActionEvent(target, ActionEvent.ACTION_PERFORMED, ""))
    }

    @Test
    fun `数字键选择选项，最后一个数字是其他`() {
        val view = ClarificationView(FakeCallbacks(), zh)
        view.render(ClarificationState.Asking(batch), record, true, "")
        fire(view.component, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, KeyEvent.VK_2)
        assertTrue("2 选中第二个选项", view.options!!.buttons[1].isSelected)
        fire(view.component, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, KeyEvent.VK_4)
        assertTrue("4（选项数 + 1）选中其他", view.options!!.otherButton.isSelected)
        assertFalse("单选：选了其他后第二个选项取消", view.options!!.buttons[1].isSelected)

        view.selectTab(2)
        fire(view.component, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, KeyEvent.VK_1)
        fire(view.component, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, KeyEvent.VK_3)
        fire(view.component, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, KeyEvent.VK_1)
        assertFalse("多选：再按 1 取消勾选", view.options!!.buttons[0].isSelected)
        assertTrue("多选：3 保持勾选", view.options!!.buttons[2].isSelected)
    }

    @Test
    fun `选项上的左右键换标签，单选题回车选中焦点所在的选项`() {
        val view = ClarificationView(FakeCallbacks(), zh)
        view.render(ClarificationState.Asking(batch), record, true, "")
        val second = view.options!!.buttons[1]
        fire(second, JComponent.WHEN_FOCUSED, KeyEvent.VK_ENTER)
        assertTrue("回车选中焦点所在的选项", second.isSelected)
        fire(second, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, KeyEvent.VK_RIGHT)
        assertEquals("→ 到第二题", 1, view.tabs!!.selectedIndex)
        fire(view.options!!.buttons[0], JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, KeyEvent.VK_LEFT)
        assertEquals("← 回到第一题", 0, view.tabs!!.selectedIndex)
        assertTrue("回来后选择还在", view.options!!.buttons[1].isSelected)
    }

    // ---- 默认尺寸 ----

    @Test
    fun `各页按打开时的默认尺寸渲染：宽 560、高按内容限制在 360～520`() {
        val asking = listOf(methodQuestion)
        val pages =
            listOf(
                Triple("input", ClarificationState.Input, false),
                Triple("investigating", ClarificationState.Investigating("读取 src/main/kotlin/com/github/izerui/imux/terminal/TerminalSessionReconnector.kt", System.currentTimeMillis() - 42_000, run = 3), true),
                Triple("asking-single", ClarificationState.Asking(asking), true),
                Triple("asking-batch", ClarificationState.Asking(batch), true),
                Triple("result", ClarificationState.Result(resultText), false),
                Triple("failed", ClarificationState.Failed(FailureReason.TIMEOUT, "600", null), false),
            )
        val width = JBUI.scale(ClarificationDialog.INITIAL_WIDTH)
        val heights = mutableMapOf<String, Int>()
        for ((page, state, canFinish) in pages) {
            val view = ClarificationView(FakeCallbacks(), zh)
            view.render(state, record, canFinish, "")
            val bar = barFor(view, state, canFinish, record)
            // 预览里没有标题栏，窗口其余部分只有按钮栏
            val height = initialHeight(view.preferredContentHeight(width), bar.preferredSize.height)
            heights[page] = height
            assertTrue("$page：高度在 360～520 之间（$height）", height in JBUI.scale(MIN_INITIAL_HEIGHT)..JBUI.scale(MAX_INITIAL_HEIGHT))
            val root = window(view, state, canFinish, record, width, height)
            png(root, "default-$page-${width}x$height")
            assertNoHorizontalClipping(view, root, "default-$page")
            assertEdgePadding(view, root, "default-$page")
        }
        assertTrue("一道短问题的窗口比一批长问题矮：${heights}", heights.getValue("asking-single") < heights.getValue("asking-batch"))
    }
}
