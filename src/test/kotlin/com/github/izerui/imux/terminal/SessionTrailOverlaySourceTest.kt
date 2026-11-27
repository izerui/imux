package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTrailOverlaySourceTest {
    private val editor = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt")
    private val overlay = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/SessionTrailOverlay.kt")

    @Test
    fun `轨迹面板叠在终端上层，不进入终端布局`() {
        assertTrue(editor.normalized.contains("setLayer(trailOverlay.card, JLayeredPane.PALETTE_LAYER)"))
        assertTrue("终端仍占满正文", editor.normalized.contains("terminal.setBounds(0, 0, width, height)"))
        assertTrue("面板让出提问导航横线列", editor.normalized.contains("val rightReserve = navigatorWidth + JBUI.scale(NAVIGATOR_RIGHT_INSET)"))
        assertTrue(editor.normalized.contains("trailOverlay.layout(width, height, rightReserve, stripY + strip.height + progressHeight)"))
    }

    @Test
    fun `只有标签页真正关闭才取消轨迹生成`() {
        val dispose = editor.bodyAfter("override fun dispose()", '{')
        val reopen = dispose.indexOf("CLOSING_TO_REOPEN")
        val release = dispose.indexOf(".releaseOwner(virtualFile.tabId)")
        assertTrue("拖动标签页、分屏重建时不能取消", reopen >= 0 && release > reopen)
        assertFalse(
            "按会话 key 清理会漏掉 /clear、/new 迁移前的任务，必须按稳定的标签页身份清理",
            dispose.contains(".forget(sessionKey)"),
        )
    }

    @Test
    fun `面板以标签页身份发起生成`() {
        assertTrue(overlay.normalized.contains("store.generate(key, virtualFile.tabId)"))
    }

    @Test
    fun `面板展开时先触发首次生成再读取显示状态`() {
        val refresh = overlay.bodyAfter("private fun refresh()", '{')
        val generate = refresh.indexOf("if (expanded) store.generateIfNeeded(key, virtualFile.tabId)")
        assertTrue("折叠时不自动生成", generate >= 0)
        assertTrue("首次展开直接显示生成进度", refresh.indexOf("val state = store.state(key)") > generate)
    }

    @Test
    fun `面板跟随会话 key 迁移，且状态与任务不存在面板里`() {
        assertTrue(overlay.normalized.contains("addSessionKeyMigratedListener(this)"))
        assertTrue("每次读取当前 key", overlay.normalized.contains("private val key get() = virtualFile.sessionKey"))
        assertFalse("面板不能自己起协程任务", overlay.normalized.contains(".launch("))
    }

    @Test
    fun `收起按钮没有点击死区且点击区域足够大`() {
        assertFalse(
            "InplaceButton 在鼠标刚移入时会吞掉点击（TimedDeadzone），收起按钮显得不灵敏",
            overlay.normalized.contains("InplaceButton("),
        )
        assertTrue(overlay.normalized.contains("ActionButton( collapseAction,"))
    }

    @Test
    fun `编辑器把正文、滚动条和叠加控件接到收起监听，随编辑器切换重绑并在早退前解绑`() {
        val create = editor.bodyAfter("private fun createEditorComponent()", '{')
        for (target in listOf("navigatorComponent", "navigatorPreview", "scrollButton", "notices", "resultChip")) {
            assertTrue("点 $target 也算点在轨迹之外", create.contains("trailOutsideClicks.attach($target)"))
        }
        assertTrue(
            "终端编辑器切换时重绑正文与滚动条",
            editor.normalized.contains("trailOutsideClicks.bindEditor(editor?.let(::terminalClickTargets).orEmpty())"),
        )
        val dispose = editor.bodyAfter("override fun dispose()", '{')
        val unbind = dispose.indexOf("trailOutsideClicks.dispose()")
        assertTrue("拖动重建时终端被沿用，必须在早退前解绑", unbind >= 0 && unbind < dispose.indexOf("CLOSING_TO_REOPEN"))
        assertFalse("卡片本身不能挂收起监听", editor.normalized.contains("trailOutsideClicks.attach(trailOverlay"))
        assertTrue(overlay.normalized.contains("fun collapse() = setExpanded(false)"))
        assertFalse("项目约定不用全局 AWT 监听", editor.normalized.contains("addAWTEventListener"))
    }

    @Test
    fun `面板上的按钮不抢终端焦点`() {
        assertTrue(overlay.normalized.contains("isFocusable = false"))
        assertFalse(overlay.normalized.contains("requestFocus"))
    }

    // ── 会话报告 ──

    @Test
    fun `标题栏带更新报告和在系统浏览器中打开按钮`() {
        assertTrue(overlay.normalized.contains("action.overview.report.update"))
        assertTrue(overlay.normalized.contains("action.overview.report.openInBrowser"))
    }

    @Test
    fun `报告视图通过 ReportViews 创建并随面板释放`() {
        assertTrue(overlay.normalized.contains("ReportViews.create("))
        assertTrue(overlay.normalized.contains("Disposer.register(this, view)"))
    }

    @Test
    fun `主题切换时重设报告深浅色`() {
        assertTrue(overlay.normalized.contains("LafManagerListener.TOPIC"))
        assertTrue(overlay.normalized.contains("setDark(!JBColor.isBright())"))
    }

    @Test
    fun `报告页由服务渲染成文件再交给视图显示`() {
        assertTrue(overlay.normalized.contains(".writeReport(sessionKey, result)"))
        assertTrue(overlay.normalized.contains("view.show("))
    }

    @Test
    fun `每种语言都有新增的报告文案`() {
        val keys = listOf(
            "action.overview.report.update",
            "action.overview.report.openInBrowser",
            "action.overview.report.noBrowser",
            "action.overview.report.stale",
            "action.overview.report.legacy",
            "action.overview.report.writeFailed",
        )
        val bundles = java.io.File("src/main/resources/messages").listFiles { file -> file.name.startsWith("ImuxBundle") }!!
        org.junit.Assert.assertEquals(10, bundles.size)
        for (bundle in bundles) {
            val lines = bundle.readLines()
            for (key in keys) {
                assertTrue("${bundle.name} 缺少 $key", lines.any { it.startsWith("$key=") && it.length > key.length + 1 })
            }
        }
    }

    @Test
    fun `在系统浏览器中打开不用 263 计划移除的 BrowserUtil browse File`() {
        assertFalse(overlay.normalized.contains(".toFile())"))
        assertTrue(overlay.normalized.contains("BrowserUtil.browse(it.toUri())"))
    }

    @Test
    fun `报告写入失败时显示错误横幅并可重试`() {
        assertTrue(overlay.normalized.contains("action.overview.report.writeFailed"))
    }

    @Test
    fun `拖拽松手按 heightToSave 决定存不存高度`() {
        val released = overlay.bodyAfter("override fun mouseReleased(", '{')
        assertTrue(released, released.contains("heightToSave(resizeHeight, showsReport(renderedState),"))
    }

    @Test
    fun `报告文件在后台线程按会话领号写入，写好的通知回到界面线程再显示`() {
        val show = overlay.bodyAfter("private fun showReport(", '{')
        val ticket = show.indexOf("reportWrites.ticket(sessionKey)")
        val background = show.indexOf("executeOnPooledThread")
        val gated = show.indexOf("reportWrites.runIfLatest(sessionKey, ticket)")
        val write = show.indexOf(".writeReport(")
        assertTrue("先领号再到后台排队写，号最新才写：$show", ticket >= 0 && background > ticket && gated > background && write > gated)
        assertTrue("显示不在这里：$show", !show.contains("view.show("))
        assertTrue(overlay.normalized.contains("reportWrites.addListener {"))
        assertTrue("通知回到界面线程", overlay.normalized.contains("invokeLater({ onReportWritten(sessionKey, ticket, write) }"))
        assertTrue("监听随面板释放注销", overlay.normalized.contains("Disposer.register(this) { removeReportListener() }"))
    }

    @Test
    fun `写好的通知只更新正显示这个会话报告页的面板`() {
        val written = overlay.bodyAfter("private fun onReportWritten(", '{')
        assertTrue(written, written.contains("if (disposed || sessionKey != key || !showsReport(renderedState)) return"))
        val content = overlay.bodyAfter("private fun contentFor(", '{')
        assertTrue("切到非报告状态作废待写：$content", content.contains("reportWrites.ticket(key)"))
    }

    @Test
    fun `面板按显示的内容决定重新加载，不看这一次写入有没有改文件`() {
        val apply = overlay.bodyAfter("private fun applyReport(", '{')
        assertTrue(apply, apply.contains("if (needsReload(shownReport, write))"))
        assertTrue("不能看 changed：别的请求可能刚把文件改成同样的内容", !apply.contains("write.changed"))
    }

    @Test
    fun `展开弹窗与在浏览器中打开都先补写报告文件，被删了也能补回`() {
        val refresh = overlay.bodyAfter("private fun refresh()", '{')
        assertTrue(refresh, refresh.contains("if (shouldRewriteOnExpand(wasExpanded, expanded, state)) currentReport(state)?.let { showReport(it) }"))
        val open = overlay.bodyAfter("private fun openReportInBrowser()", '{')
        assertTrue("打开前先请求写好：$open", open.contains("pendingOpen = showReport(it)"))
        assertTrue("不直接打开记下的路径：$open", !open.contains("BrowserUtil.browse("))
        val apply = overlay.bodyAfter("private fun applyReport(", '{')
        assertTrue("按点击那次请求结算打开意图：$apply", apply.contains("settleOpen(pendingOpen, ticket, written = write != null)"))
        assertTrue("写好后再打开：$apply", apply.contains("if (open.openNow) BrowserUtil.browse(write.path.toUri())"))
    }

    @Test
    fun `标题栏按钮与无 JCEF 时的备用链接都走先补写再打开的同一入口`() {
        val open = overlay.normalized.substringAfter("private val openAction").substringBefore("private val reportButtons")
        assertTrue("标题栏按钮：$open", open.contains("openReportInBrowser()"))
        val entry = overlay.bodyAfter("private fun openReportInBrowser()", '{')
        assertTrue("先请求写好再打开，记下这次请求的号：$entry", entry.contains("pendingOpen = showReport(it)"))
        assertTrue("备用视图拿到的是这个入口", overlay.normalized.contains("onOpenReport = ::openReportInBrowser"))

        val views = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/report/ReportView.kt").normalized
        val fallback = views.substringAfter("private class FallbackReportView")
        assertTrue("备用链接只通知弹窗打开：$fallback", fallback.contains("ActionLink(ImuxBundle.message(\"action.overview.report.openInBrowser\")) { onOpenReport() }"))
        assertTrue("备用视图不记旧路径、不自己打开：$fallback", !fallback.contains("openExternal") && !fallback.contains("private var file"))
    }
}
