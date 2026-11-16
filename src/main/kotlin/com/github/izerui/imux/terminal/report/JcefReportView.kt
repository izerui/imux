package com.github.izerui.imux.terminal.report

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.nio.file.Path
import javax.swing.JComponent

/**
 * 用 JCEF 内嵌显示报告页。唯一引用 JCEF 类的地方，只在 [ReportViews.create] 确认 JCEF 插件启用后加载。
 *
 * 用离屏渲染：默认的原生窗口总画在所有 Swing 组件之上，会盖住终端浮动层上方的图标组与提示条。
 * 加载完成时设好深浅色（根元素的 `data-theme`）并注入 Esc 监听，按 Esc 经 JS 查询通知插件收起卡片。
 * 页面里点到别的地址不在弹窗里跳转，交给系统浏览器。
 */
internal class JcefReportView private constructor(
    private val onEscape: () -> Unit,
    private val openExternal: (Path) -> Unit,
) : ReportView {
    private val browser: JBCefBrowser = JBCefBrowser.createBuilder().setOffScreenRendering(true).build()
    private val escapeQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private var currentUrl: String? = null
    private var dark = false

    override val component: JComponent get() = browser.component

    init {
        Disposer.register(this, browser)
        Disposer.register(browser, escapeQuery)
        escapeQuery.addHandler {
            ApplicationManager.getApplication().invokeLater(onEscape)
            null
        }
        browser.jbCefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                    if (!frame.isMain) return
                    applyTheme()
                    run("document.addEventListener('keydown', function (e) { if (e.key === 'Escape') { ${escapeQuery.inject("'esc'")} } });")
                }
            },
            browser.cefBrowser,
        )
        browser.jbCefClient.addRequestHandler(
            object : CefRequestHandlerAdapter() {
                override fun onBeforeBrowse(
                    cefBrowser: CefBrowser,
                    frame: CefFrame,
                    request: CefRequest,
                    userGesture: Boolean,
                    isRedirect: Boolean,
                ): Boolean {
                    val url = request.url ?: return false
                    if (isReportUrl(currentUrl, url)) return false
                    val external = runCatching { Path.of(java.net.URI(url)) }.getOrNull()
                    ApplicationManager.getApplication().invokeLater {
                        if (external != null) openExternal(external) else com.intellij.ide.BrowserUtil.browse(url)
                    }
                    return true
                }
            },
            browser.cefBrowser,
        )
    }

    override fun show(file: Path) {
        val url = file.toUri().toString()
        currentUrl = url
        browser.loadURL(url)
    }

    override fun setDark(dark: Boolean) {
        this.dark = dark
        applyTheme()
    }

    private fun applyTheme() {
        run("document.documentElement.setAttribute('data-theme', '${if (dark) "dark" else "light"}');")
    }

    private fun run(js: String) {
        if (currentUrl == null) return
        browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
    }

    override fun dispose() = Unit

    companion object {
        /** 当前环境支持 JCEF 时创建；不支持返回 null，由调用方用退路视图。 */
        fun createIfSupported(onEscape: () -> Unit, openExternal: (Path) -> Unit): ReportView? =
            if (JBCefApp.isSupported()) JcefReportView(onEscape, openExternal) else null
    }
}

/** 导航目标是不是正在显示的报告本身（含页内锚点）。 */
internal fun isReportUrl(current: String?, target: String): Boolean =
    current != null && (target == current || target.startsWith("$current#"))
