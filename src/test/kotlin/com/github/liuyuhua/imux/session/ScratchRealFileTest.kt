package com.github.liuyuhua.imux.session

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Paths

class ScratchRealFileTest {
    @Test
    fun probe() {
        val home = Paths.get(System.getProperty("user.home"))
        val dir = File(home.toFile(), ".claude/projects/-Users-liuyuhua-github-open-agents")
        assumeTrue(dir.isDirectory)
        val sessions = ClaudeSessionReader(home.resolve(".claude")).read("/Users/liuyuhua/github/open-agents")
        println("=== 读到 ${sessions.size} 个会话 ===")
        sessions.forEach { println("  id=${it.id.take(8)}  标题=${it.title}  时间=${it.lastActiveAt}") }
    }
}
