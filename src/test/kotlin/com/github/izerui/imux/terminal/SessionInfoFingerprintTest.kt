package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.claudeContextCachePath
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class SessionInfoFingerprintTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `会话文件追加内容后信息指纹变化`() {
        val project = temporaryFolder.newFolder("project").toPath()
        val transcript = temporaryFolder.newFile("session.jsonl").toPath()
        Files.writeString(transcript, "first\n")
        val before = sessionInfoFingerprint(transcript, project, AgentType.CODEX)

        Files.writeString(transcript, "second\n", java.nio.file.StandardOpenOption.APPEND)

        assertNotEquals(before, sessionInfoFingerprint(transcript, project, AgentType.CODEX))
    }

    @Test
    fun `Git 分支切换后信息指纹变化`() {
        val project = temporaryFolder.newFolder("project").toPath()
        val git = Files.createDirectory(project.resolve(".git"))
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n")
        val before = sessionInfoFingerprint(null, project, AgentType.CODEX)

        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/feature/live-info\n")

        assertNotEquals(before, sessionInfoFingerprint(null, project, AgentType.CODEX))
    }

    @Test
    fun `文件与分支未变化时信息指纹保持不变`() {
        val project = temporaryFolder.newFolder("project").toPath()
        val git = Files.createDirectory(project.resolve(".git"))
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n")
        val transcript = temporaryFolder.newFile("session.jsonl").toPath()
        Files.writeString(transcript, "same\n")

        assertEquals(
            sessionInfoFingerprint(transcript, project, AgentType.CODEX),
            sessionInfoFingerprint(transcript, project, AgentType.CODEX),
        )
    }

    @Test
    fun `Claude 上下文缓存变化后信息指纹变化`() {
        val root = temporaryFolder.newFolder("claude").toPath()
        val project = Files.createDirectories(root.resolve("projects/sample"))
        val transcript = Files.createFile(project.resolve("session.jsonl"))
        val cache = checkNotNull(claudeContextCachePath(transcript))
        Files.createDirectories(cache.parent)
        val before = sessionInfoFingerprint(transcript, project, AgentType.CLAUDE)

        Files.writeString(cache, """{"used_percentage":42}""")

        assertNotEquals(before, sessionInfoFingerprint(transcript, project, AgentType.CLAUDE))
    }
}
