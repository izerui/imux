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
 * 页面里用户点开的外部地址不在弹窗里跳转，交给系统浏览器（见 [shouldOpenExternally]）。
 */
internal class JcefReportView private constructor(
    private val onEscape: () -> Unit,
    private val openExternal: (Path) -> Unit,
) : ReportView {
    private val browser: JBCefBrowser = JBCefBrowser.createBuilder().setOffScreenRendering(true).build()
    private val escapeQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    // EDT 写、CEF 线程（onLoadEnd、onBeforeBrowse）读
    @Volatile private var currentUrl: String? = null
    @Volatile private var dark = false

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
                    if (!shouldOpenExternally(currentUrl, url, userGesture)) return false
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

/**
 * 这次导航要不要拦下、交给系统浏览器：只拦用户点开的、既不是报告本身（含页内锚点）也不是浏览器内部页面的地址。
 * 初始化、重定向这类不是用户点的导航，以及 `about:`、`data:`、JCEF 自己的 `file:///jbcefbrowser/` 页面一律放行，
 * 否则弹窗一打开就可能拉起系统浏览器，内置错误页也显示不出来。
 */
internal fun shouldOpenExternally(current: String?, target: String, userGesture: Boolean): Boolean {
    if (!userGesture) return false
    if (INTERNAL_PREFIXES.any(target::startsWith)) return false
    return current == null || (target != current && !target.startsWith("$current#"))
}

private val INTERNAL_PREFIXES = listOf("about:", "data:", "file:///jbcefbrowser/")
