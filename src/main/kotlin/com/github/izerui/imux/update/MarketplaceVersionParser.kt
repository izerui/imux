package com.github.izerui.imux.update

import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 解析 Marketplace `plugins/list` 接口返回的 XML。
 *
 * 接口已按请求里的 build 号过滤，返回的 `idea-plugin` 就是当前 IDE 能装的最新版本。
 */
internal object MarketplaceVersionParser {
    /** 取 [pluginId] 对应插件的版本号；没有兼容版本、格式不对时返回 null。 */
    fun latestVersion(
        xml: String,
        pluginId: String,
    ): String? =
        try {
            val builder =
                DocumentBuilderFactory.newInstance()
                    .apply {
                        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                        isExpandEntityReferences = false
                    }.newDocumentBuilder()
            // 默认错误处理器会往 stderr 打印 [Fatal Error]，这里改成直接抛出。
            builder.setErrorHandler(ThrowingErrorHandler)
            val plugins = builder.parse(InputSource(StringReader(xml))).getElementsByTagName("idea-plugin")
            (0 until plugins.length)
                .map { plugins.item(it) as Element }
                .firstOrNull { it.childText("id") == pluginId }
                ?.childText("version")
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }

    private fun Element.childText(tag: String): String? {
        val children = childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && node.tagName == tag) return node.textContent.trim()
        }
        return null
    }

    private object ThrowingErrorHandler : ErrorHandler {
        override fun warning(exception: SAXParseException) = Unit

        override fun error(exception: SAXParseException): Unit = throw exception

        override fun fatalError(exception: SAXParseException): Unit = throw exception
    }
}
