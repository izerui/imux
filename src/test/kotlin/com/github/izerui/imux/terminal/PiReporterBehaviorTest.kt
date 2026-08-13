package com.github.izerui.imux.terminal

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 上报扩展的降级行为：**任何**故障都必须退回「不上报」，绝不能把异常抛给 pi 的
 * 扩展加载器——那会让用户的会话起不来，而这个扩展只负责标签页自动跟随。
 *
 * 三种降级路径中，「脚本缺失」与「内置服务未就绪」在 Kotlin 侧（[piReporterScriptIn]、
 * `PiReportEndpoint.current()` 返回 null）已有覆盖。这里守住第三种：
 * pi 版本变更导致扩展 API 不兼容。它只能真的把脚本跑起来才能验证——
 * try/catch 放在回调内部还是包住整个函数体，源码上只差两行缩进，行为却天差地别。
 */
class PiReporterBehaviorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * pi 换了扩展 API：`on` 不再存在。此时 `pi.on(...)` 抛 TypeError，
     * 若没被包住就会一路冒到 pi 的加载器。
     */
    @Test
    fun `扩展 API 不兼容时不抛异常`() {
        assertEquals("ok", runReporter(piExpression = "({})"))
    }

    /** `on` 存在但签名变了、当场抛错，同样要吞掉。 */
    @Test
    fun `注册回调抛错时不抛异常`() {
        assertEquals(
            "ok",
            runReporter(piExpression = """({ on() { throw new TypeError("unsupported event"); } })"""),
        )
    }

    /** 正常形态下必须真的注册上，否则上面两条「不抛异常」用空实现也能过。 */
    @Test
    fun `API 正常时注册 session_start 回调`() {
        assertEquals(
            "ok:session_start",
            runReporter(
                piExpression = "({ on(name) { globalThis.__registered = name; } })",
                report = """"ok:" + (globalThis.__registered ?? "none")""",
            ),
        )
    }

    /** 环境变量缺失时彻底不干活——用户自己在终端里跑 pi 的情形。 */
    @Test
    fun `缺少环境变量时不注册回调`() {
        assertEquals(
            "ok:none",
            runReporter(
                piExpression = "({ on(name) { globalThis.__registered = name; } })",
                report = """"ok:" + (globalThis.__registered ?? "none")""",
                env = emptyMap(),
            ),
        )
    }

    /**
     * 真的把脚本加载起来跑一遍。
     *
     * 装不到 node 就跳过而不是失败：node 不是本插件的构建依赖，
     * 而这条测试的价值在于开发机与 CI 上能跑到就一定跑到。
     */
    private fun runReporter(
        piExpression: String,
        report: String = """"ok"""",
        env: Map<String, String> = mapOf(
            "IMUX_REPORT_URL" to "http://127.0.0.1:1/imux/pi-session",
            "IMUX_TOKEN" to "token",
            "IMUX_TAB" to "imux-tab",
        ),
    ): String {
        val node = File("/opt/homebrew/bin/node").takeIf { it.canExecute() }?.absolutePath ?: "node"
        assumeTrue("需要 node 才能执行扩展脚本", canRun(node))

        // 源码是 ESM（export default），必须以 .mjs 载入才能不依赖 package.json
        val script = File(tmp.root, "reporter.mjs")
        script.writeText(File("src/main/js/pi-imux-reporter.js").readText())

        val driver = File(tmp.root, "driver.mjs")
        driver.writeText(
            """
            import reporter from "./reporter.mjs";
            reporter($piExpression);
            console.log($report);
            """.trimIndent(),
        )

        val process = ProcessBuilder(node, driver.absolutePath)
            .directory(tmp.root)
            .redirectErrorStream(true)
            .apply {
                environment().keys.retainAll(setOf("PATH", "HOME"))
                environment().putAll(env)
            }
            .start()
        process.waitFor(30, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().readText().trim()
        assertEquals("脚本必须正常退出，异常冒到加载器就是会话起不来：\n$output", 0, process.exitValue())
        return output
    }

    private fun canRun(node: String): Boolean = runCatching {
        ProcessBuilder(node, "--version").redirectErrorStream(true).start()
            .also { it.waitFor(10, TimeUnit.SECONDS) }
            .exitValue() == 0
    }.getOrDefault(false)
}
