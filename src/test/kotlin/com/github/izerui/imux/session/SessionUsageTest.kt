package com.github.izerui.imux.session

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionUsageTest {
    @Test fun `Codex 技能说明与来源路径分开显示`() {
        val row = """{"type":"world_state","payload":{"state":{"host_skills":{"body":"- docs: Find documentation (file: /skills/docs/SKILL.md)"}}}}"""
        val usage = sessionUsage(AgentType.CODEX, sequenceOf(row))
        assertEquals("Find documentation", usage.skillDescriptions["docs"])
        assertEquals("/skills/docs/SKILL.md", usage.skillLocations["docs"])
    }

    @Test fun `Claude 技能说明按清单名称对应`() {
        val row = """{"type":"attachment","attachment":{"type":"skill_listing","names":["review"],"content":"- review: Check code changes"}}"""
        assertEquals("Check code changes", sessionUsage(AgentType.CLAUDE, sequenceOf(row)).skillDescriptions["review"])
    }

    @Test fun `Pi 从上报读取技能用途`() {
        val row = """{"type":"session_start","tabId":"tab","sessionId":"s","cwd":"/project","skillNames":"review","skillDescriptions":{"review":"Check changes"}}"""
        assertEquals("Check changes", parsePiReport(row)?.skillDescriptions?.get("review"))
    }
    @Test fun `Claude 弹窗用本会话状态栏占比且不会借用相邻会话的快照`() {
        val root = java.nio.file.Files.createTempDirectory("imux-context")
        try {
            val project = java.nio.file.Files.createDirectories(root.resolve("projects/sample"))
            val first = java.nio.file.Files.createFile(project.resolve("first.jsonl"))
            val other = java.nio.file.Files.createFile(project.resolve("other.jsonl"))
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(first.toString().toByteArray())
            val cache = java.nio.file.Files.createDirectories(root.resolve("plugins/claude-hud/context-cache"))
            java.nio.file.Files.writeString(cache.resolve("${java.util.HexFormat.of().formatHex(hash)}.json"),
                """{"used_percentage":86,"context_window_size":1000000}""")
            assertEquals(86, readSessionUsage(AgentType.CLAUDE, first) {}.reportedContextPercent)
            assertEquals(null, readSessionUsage(AgentType.CLAUDE, other) {}.reportedContextPercent)
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun `Claude 技能初始清单合并后续发现的技能`() {
        fun listing(initial: Boolean, name: String) =
            """{"type":"attachment","attachment":{"type":"skill_listing","isInitial":$initial,"names":["$name"]}}"""
        val usage = sessionUsage(AgentType.CLAUDE, sequenceOf(listing(true, "review"), listing(false, "build")))
        assertEquals(listOf("build", "review"), usage.skills)
        assertEquals(true, usage.skillsReported)
    }

    @Test fun `Pi 上报技能名能保留独立条目`() {
        val json = """{"type":"session_start","tabId":"tab","sessionId":"s","cwd":"/project","skillNames":"build\nreview"}"""
        assertEquals(listOf("build", "review"), parsePiReport(json)?.skillNames)
    }
    @Test fun `Codex 累计值取最近快照且不重复计算`() {
        val line = """{"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"total_tokens":120},"last_token_usage":{"total_tokens":35},"model_context_window":200}}}"""
        assertEquals(SessionUsage(120, 35, 200), sessionUsage(AgentType.CODEX, sequenceOf(line, line, "{")))
    }

    @Test fun `Claude 同一回复的用量更新只算一次并包含缓存`() {
        val line = """{"message":{"role":"assistant","id":"x","usage":{"input_tokens":1,"cache_read_input_tokens":2,"cache_creation_input_tokens":3,"output_tokens":4}}}"""
        assertEquals(10L, sessionUsage(AgentType.CLAUDE, sequenceOf(line, line)).total)
    }

    @Test fun `Pi 累加记录中的助手回复并忽略用户正文`() {
        val line = """{"id":"1","message":{"role":"assistant","model":"m","usage":{"totalTokens":33}}}"""
        val next = line.replace("\"1\"", "\"2\"")
        assertEquals(SessionUsage(66, 33, model = "m"), sessionUsage(AgentType.PI, sequenceOf(line, next, next.replace("assistant", "user"))))
    }
}
