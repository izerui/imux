package com.github.izerui.imux.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files

class ImuxDirectoryTest {
    @Test
    fun `没有 git 目录时也生成内容为星号的 gitignore`() {
        val root = Files.createTempDirectory("project")
        assertFalse(Files.exists(root.resolve(".git")))

        ensureImuxIgnored(root)

        assertEquals("*\n", Files.readString(root.resolve(".imux").resolve(".gitignore")))
    }

    @Test
    fun `已有 gitignore 时不覆盖`() {
        val root = Files.createTempDirectory("project")
        val ignore = root.resolve(".imux").resolve(".gitignore")
        Files.createDirectories(ignore.parent)
        Files.writeString(ignore, "attachments/\n")

        ensureImuxIgnored(root)

        assertEquals("attachments/\n", Files.readString(ignore))
    }
}
