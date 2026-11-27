package com.github.izerui.imux.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectGitBranchTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun repository(): File = tmp.newFolder("repo").apply {
        resolve(".git").mkdir()
        resolve(".git/HEAD").writeText("ref: refs/heads/feature/session-info\n")
    }

    @Test
    fun `读取当前分支并保留分支名中的斜线`() {
        assertEquals("feature/session-info", projectGitBranch(repository().toPath()))
    }

    @Test
    fun `工程位于仓库子目录时读取所属仓库`() {
        val module = repository().resolve("module").apply { mkdir() }
        assertEquals("feature/session-info", projectGitBranch(module.toPath()))
    }

    @Test
    fun `相对路径的工作树读取自己的分支`() {
        val repo = repository()
        repo.resolve(".git/worktrees/task").mkdirs()
        repo.resolve(".git/worktrees/task/HEAD").writeText("ref: refs/heads/task\r\n")
        val tree = tmp.newFolder("工作目录 with spaces")
        tree.resolve(".git").writeText("gitdir: ../repo/.git/worktrees/task\r\n")
        assertEquals("task", projectGitBranch(tree.toPath()))
    }

    @Test
    fun `绝对路径的子模块读取自己的分支`() {
        val repo = repository()
        val metadata = repo.resolve(".git/modules/child").apply { mkdirs() }
        metadata.resolve("HEAD").writeText("ref: refs/heads/child-main\n")
        val module = repo.resolve("child").apply { mkdir() }
        module.resolve(".git").writeText("gitdir: ${metadata.absolutePath}\n")
        assertEquals("child-main", projectGitBranch(module.toPath()))
    }

    @Test
    fun `未启用Git的目录不显示分支`() {
        assertNull(projectGitBranch(tmp.newFolder("plain").toPath()))
    }

    @Test
    fun `游离HEAD不冒充当前分支`() {
        val repo = repository()
        repo.resolve(".git/HEAD").writeText("a".repeat(40) + "\n")
        assertNull(projectGitBranch(repo.toPath()))
    }

    @Test
    fun `嵌套仓库元数据损坏不显示外层分支`() {
        val nested = repository().resolve("nested").apply { mkdir() }
        nested.resolve(".git").writeText("gitdir: missing\n")
        assertNull(projectGitBranch(nested.toPath()))
    }

    @Test
    fun `重新读取能反映切换分支和移除版本管理`() {
        val repo = repository()
        assertEquals("feature/session-info", projectGitBranch(repo.toPath()))
        repo.resolve(".git/HEAD").writeText("ref: refs/heads/main\n")
        assertEquals("main", projectGitBranch(repo.toPath()))
        repo.resolve(".git").deleteRecursively()
        assertNull(projectGitBranch(repo.toPath()))
    }
}
