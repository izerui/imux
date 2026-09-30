package com.github.izerui.imux.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * 路径全部取自 [TemporaryFolder]，期望值也由这些路径构造，不写死 `/Users/...`：
 * Windows 上 `Path.of("/data/x").isAbsolute` 为 false，写死 Unix 路径的断言在那里
 * 验证的是「被当成相对路径丢掉」，而不是盘符路径能否被正确识别。
 */
class AgentHomesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var home: Path
    private lateinit var data: Path

    @Before
    fun setUp() {
        home = tmp.newFolder("home").toPath()
        data = tmp.newFolder("data").toPath()
    }

    private fun resolve(vararg env: Pair<String, String>) = AgentHomes.resolve(home) { mapOf(*env)[it] }

    @Test
    fun `没有环境变量时是默认布局`() {
        assertEquals(AgentHomes.defaults(home), resolve())
        assertEquals(home.resolve(".pi").resolve("agent"), resolve().piAgent)
    }

    @Test
    fun `环境变量覆盖各自的数据目录`() {
        val homes =
            resolve(
                "CLAUDE_CONFIG_DIR" to data.resolve("claude-work").toString(),
                "CODEX_HOME" to data.resolve("codex").toString(),
                "CODEX_SQLITE_HOME" to data.resolve("codex-db").toString(),
                "PI_CODING_AGENT_DIR" to data.resolve("pi-agent").toString(),
                "PI_CODING_AGENT_SESSION_DIR" to data.resolve("pi-sessions").toString(),
            )

        assertEquals(data.resolve("claude-work"), homes.claude)
        assertEquals(data.resolve("codex"), homes.codex)
        assertEquals(data.resolve("codex-db"), homes.codexSqlite)
        assertEquals(data.resolve("pi-agent"), homes.piAgent)
        assertEquals(data.resolve("pi-sessions"), homes.piSessions)
    }

    @Test
    fun `波浪号按用户主目录展开`() {
        assertEquals(home.resolve(".claude-work"), resolve("CLAUDE_CONFIG_DIR" to "~/.claude-work").claude)
        assertEquals(home, resolve("CLAUDE_CONFIG_DIR" to "~").claude)
    }

    @Test
    fun `波浪号后接反斜杠也按用户主目录展开`() {
        // Windows 用户写 `~\.claude-work` 很自然
        assertEquals(home.resolve(".claude-work"), resolve("CLAUDE_CONFIG_DIR" to "~\\.claude-work").claude)
    }

    @Test
    fun `空值与相对路径视为未设置`() {
        // 相对路径由 CLI 按它自己的 cwd 解析，imux 不知道那个 cwd，不能猜
        val homes = resolve("CLAUDE_CONFIG_DIR" to "  ", "CODEX_HOME" to "relative/codex", "CODEX_SQLITE_HOME" to "db")
        assertEquals(home.resolve(".claude"), homes.claude)
        assertEquals(home.resolve(".codex"), homes.codex)
        assertNull(homes.codexSqlite)
    }

    @Test
    fun `sqlite_home 优先于 CODEX_SQLITE_HOME，二者都没有时退回 CODEX_HOME`() {
        val codexHome = tmp.newFolder("codex").toPath()
        val envDir = data.resolve("codex-db")
        val configDir = data.resolve("from-config")

        assertEquals(codexHome, codexSqliteDir(codexHome, null))
        assertEquals(envDir, codexSqliteDir(codexHome, envDir))

        // 用正斜杠写进 TOML：基本字符串里的反斜杠是转义符，Windows 路径原样写进去不合法
        Files.writeString(
            codexHome.resolve("config.toml"),
            "sqlite_home = \"${configDir.toString().replace('\\', '/')}\"\n",
        )
        assertEquals(configDir, codexSqliteDir(codexHome, envDir))
    }
}
