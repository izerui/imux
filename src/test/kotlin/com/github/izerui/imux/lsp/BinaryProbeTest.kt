package com.github.izerui.imux.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BinaryProbeTest {

    private val source: String by lazy {
        File("src/main/kotlin/com/github/izerui/imux/lsp/BinaryProbe.kt").readText()
    }

    @Test
    fun `脚本对每个二进制输出一行 名称 制表符 路径`() {
        val script = buildProbeScript(listOf("gopls", "jdtls"))

        assertTrue("必须逐个查询", script.contains("'gopls'") && script.contains("'jdtls'"))
        assertTrue("必须用 command -v 而不是 which（后者在部分 shell 里不是内建）", script.contains("command -v"))
        assertTrue("失败时不能让整条脚本中断", script.contains("2>/dev/null"))
    }

    /** 二进制名来自本仓库的静态表，但拼进 shell 的东西一律当作不可信。 */
    @Test
    fun `二进制名被单引号包裹`() {
        assertTrue(buildProbeScript(listOf("a'b")).contains("""'a'\''b'"""))
    }

    @Test
    fun `解析输出得到路径映射`() {
        val output = "gopls\t/Users/demo/go/bin/gopls\njdtls\t/opt/homebrew/bin/jdtls\n"

        assertEquals(
            mapOf("gopls" to "/Users/demo/go/bin/gopls", "jdtls" to "/opt/homebrew/bin/jdtls"),
            parseProbeOutput(output),
        )
    }

    /** 未安装时 command -v 无输出，制表符后为空——这一条必须解析成 null 而不是空串。 */
    @Test
    fun `路径为空表示未安装`() {
        val parsed = parseProbeOutput("gopls\t\njdtls\t/opt/homebrew/bin/jdtls\n")

        assertTrue("键必须在", "gopls" in parsed)
        assertNull(parsed["gopls"])
        assertEquals("/opt/homebrew/bin/jdtls", parsed["jdtls"])
    }

    /** 登录 shell 会打印 profile 里的欢迎语、版本提示等噪音，不能让它污染结果。 */
    @Test
    fun `忽略不含制表符的噪音行`() {
        val output = "Welcome to zsh!\n\ngopls\t/usr/local/bin/gopls\nnvm: v22\n"

        assertEquals(mapOf("gopls" to "/usr/local/bin/gopls"), parseProbeOutput(output))
    }

    @Test
    fun `空输入得到空映射`() {
        assertEquals(emptyMap<String, String?>(), parseProbeOutput(""))
    }

    /**
     * stderr 留独立管道会死锁，而且是**超时兜底也救不了**的那种死锁。
     *
     * 这里全程没人读 stderr。nvm / rbenv / conda init 之类的 rc 往 stderr 写超过管道缓冲
     * （约 64KB）时，子进程阻塞在写 stderr → 永远不关 stdout → `readText()` 永久阻塞 →
     * 下面那行 `waitFor(timeout)` 根本执行不到。复现它需要一份会刷屏的真实 rc，
     * 单测里造不出来，所以只能靠源码断言守。
     *
     * `redirectErrorStream(true)` 同样能解死锁，但会把 profile 噪音混进 stdout：
     * parseProbeOutput 靠制表符筛行，任何含制表符的告警都会被当成一条「名称→路径」。
     * 因此两种写法都要挡。
     */
    @Test
    fun `stderr 必须丢弃而不是留管道或并进 stdout`() {
        // 只看 locate 的函数体：类 KDoc 里为了解释「为什么不能这么写」正引用着
        // redirectErrorStream 的两种写法，扫全文会把说明当成缺陷。
        val body = source.substringAfter("override fun locate")

        assertTrue(
            "stderr 无人读取，必须 DISCARD，否则子进程写满管道缓冲就死锁，超时形同虚设",
            body.contains("redirectError(ProcessBuilder.Redirect.DISCARD)"),
        )
        assertFalse(
            "留独立管道正是死锁本身",
            body.contains("redirectErrorStream(false)"),
        )
        assertFalse(
            "并进 stdout 虽不死锁，但 profile 噪音会污染探测结果",
            body.contains("redirectErrorStream(true)"),
        )
    }

    /**
     * 超时是这一页最可能发生的失败，UI 上只表现为一屏「无法确定」，
     * 而 settings.lsp.failed 的译文正让用户「详情见 IDE 日志」——不记就是让人翻空日志。
     *
     * 断言钉的是 `LOG.warn` 与 `return emptyMap()` 的**先后顺序**：只断言文件里有
     * LOG.warn 的话，catch 分支那句现成的就能让它变绿，超时分支静默照旧。
     */
    @Test
    fun `探测超时必须留下日志`() {
        val timeoutBranch = source.substringAfter("destroyForcibly()").substringBefore("parseProbeOutput")

        assertTrue(
            "超时分支必须记 warn，否则用户按提示去翻 idea.log 只会翻到空",
            timeoutBranch.contains("LOG.warn"),
        )
        assertTrue(
            "日志要落在返回空映射之前",
            timeoutBranch.indexOf("LOG.warn") < timeoutBranch.indexOf("return emptyMap()"),
        )
    }
}
