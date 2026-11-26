package com.github.izerui.imux.code

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CallSiteEvidenceTest {
    @Test fun `自身直接调用在返回节点和源码位置标为调用并注明递归`() {
        val evidence = CallSiteEvidence()
        evidence.record("call") { JsonObject().apply { addProperty("text", "visit(child)") } }
        val node = JsonObject().also { evidence.writeTo(it, self = true) }
        assertEquals("call", node["usage"].asString)
        assertEquals(true, node["recursive"].asBoolean)
        assertEquals("call", node.getAsJsonArray("sites").single().asJsonObject["usage"].asString)
    }

    @Test fun `作为实参的 save 与随后直接调用的 save 分别保留行号与用法`() {
        val uses = CallSiteEvidence()
        uses.record(classifyUsageRole("ARGUMENT_LIST", false)!!) {
            JsonObject().apply { addProperty("line", 10); addProperty("text", "save") }
        }
        assertFalse(uses.called)
        uses.record(classifyUsageRole("CALL_EXPRESSION", true)!!) {
            JsonObject().apply { addProperty("line", 12); addProperty("text", "save()") }
        }
        val result = JsonObject().also(uses::writeTo)
        val sites = result.getAsJsonArray("sites").map { it.asJsonObject }
        assertEquals("call", result["usage"].asString)
        assertEquals(listOf(10 to "reference", 12 to "call"), sites.map { it["line"].asInt to it["usage"].asString })
    }

    @Test fun `展示上限之后才出现调用仍然能展开并报告总数`() {
        val uses = CallSiteEvidence()
        repeat(20) { uses.record("reference") { JsonObject() } }
        uses.record("call") { error("超出上限不再读取源码") }
        val result = JsonObject().also(uses::writeTo)
        assertEquals("call", result["usage"].asString)
        assertEquals(20, result.getAsJsonArray("sites").size())
        assertEquals(21, result["site_count"].asInt)
    }

    @Test fun `未识别的语法保留为待核对的位置`() {
        assertNull(classifyUsageRole("CUSTOM_NODE", true))
        val uses = CallSiteEvidence()
        uses.record("unknown") { JsonObject() }
        val result = JsonObject().also(uses::writeTo)
        assertFalse(uses.called)
        assertEquals("unknown", result.getAsJsonArray("sites")[0].asJsonObject["usage"].asString)
    }
}
