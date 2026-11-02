package com.github.izerui.imux.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarketplaceVersionParserTest {
    private val id = "com.github.izerui.imux"

    private fun repo(body: String) =
        "<?xml version='1.0' encoding='UTF-8'?><plugin-repository><ff>\"AI\"</ff>" +
            "<category name=\"AI\">$body</category></plugin-repository>"

    @Test
    fun `取到插件节点下的版本号`() {
        val xml =
            repo(
                "<idea-plugin downloads=\"747\"><name>imux</name><id>$id</id>" +
                    "<description><![CDATA[<p>desc</p>]]></description>" +
                    "<version>1.3.1</version><idea-version since-build=\"262.0\"/></idea-plugin>",
            )
        assertEquals("1.3.1", MarketplaceVersionParser.latestVersion(xml, id))
    }

    @Test
    fun `没有兼容版本时返回 null`() {
        assertNull(MarketplaceVersionParser.latestVersion("<plugin-repository/>", id))
    }

    @Test
    fun `缺少 version 时返回 null`() {
        val xml = repo("<idea-plugin><id>$id</id></idea-plugin>")
        assertNull(MarketplaceVersionParser.latestVersion(xml, id))
    }

    @Test
    fun `插件 ID 不匹配时返回 null`() {
        val xml = repo("<idea-plugin><id>other.plugin</id><version>9.9.9</version></idea-plugin>")
        assertNull(MarketplaceVersionParser.latestVersion(xml, id))
    }

    @Test
    fun `非法 XML 返回 null`() {
        assertNull(MarketplaceVersionParser.latestVersion("<html>502 Bad Gateway", id))
    }

    @Test
    fun `拒绝 DOCTYPE`() {
        val xml =
            "<?xml version='1.0'?><!DOCTYPE r [<!ENTITY x \"1.0\">]>" +
                repo("<idea-plugin><id>$id</id><version>&x;</version></idea-plugin>")
        assertNull(MarketplaceVersionParser.latestVersion(xml, id))
    }
}
