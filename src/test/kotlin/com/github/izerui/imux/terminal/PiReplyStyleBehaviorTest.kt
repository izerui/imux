package com.github.izerui.imux.terminal

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 在 Node 中加载实际的 `pi-imux-reply-style.js`，验证它把回复引导加进 pi 的系统提示。 */
class PiReplyStyleBehaviorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `把回复引导加成系统提示里单独一段并清除启动环境`() {
        val output =
            runHarness(
                """
                process.env.IMUX_REPLY_STYLE = "Explain plainly";
                await load();
                report({ section: await fire(), env: process.env.IMUX_REPLY_STYLE ?? null });
                """,
            )

        assertEquals("""{"section":"Explain plainly","env":null}""", output)
    }

    /** /new、/resume 会重载扩展，而环境变量首次加载就删了：重载后引导不能丢。 */
    @Test
    fun `扩展重载后仍保留回复引导`() {
        val output =
            runHarness(
                """
                process.env.IMUX_REPLY_STYLE = "Explain plainly";
                await load();
                handlers.clear();
                await load();
                report({ section: await fire() });
                """,
            )

        assertEquals("""{"section":"Explain plainly"}""", output)
    }

    @Test
    fun `没有回复引导时不注册钩子`() {
        val output = runHarness("await load(); report({ hooks: handlers.size });")

        assertEquals("""{"hooks":0}""", output)
    }

    @Test
    fun `扩展 API 不兼容时不抛异常`() {
        val output =
            runHarness(
                """
                process.env.IMUX_REPLY_STYLE = "Explain plainly";
                await load({ on() { throw new TypeError("unsupported"); } });
                report("ok");
                """,
            )

        assertEquals("\"ok\"", output)
    }

    private fun runHarness(body: String): String {
        val module =
            tmp.newFile("pi-imux-reply-style.mjs").apply {
                writeText(File("src/main/js/pi-imux-reply-style.js").readText())
            }
        val harness =
            tmp.newFile("run.mjs").apply {
                writeText(
                    """
                    delete process.env.IMUX_REPLY_STYLE;
                    const handlers = new Map();
                    const api = { on(name, handler) { handlers.set(name, handler); } };
                    const report = (value) => console.log(JSON.stringify(value));
                    const fire = async () => {
                      const event = { systemPromptOptions: { sections: {} } };
                      await handlers.get("before_agent_start")?.(event);
                      return event.systemPromptOptions.sections.imux_reply_style ?? null;
                    };
                    let loads = 0;
                    const load = async (target = api) => {
                      const extension = (await import(${jsString(module.toURI().toString())} + "?load=" + loads++)).default;
                      extension(target);
                    };
                    
                    """.trimIndent() + "\n" + body.trimIndent(),
                )
            }

        val process = ProcessBuilder("node", harness.absolutePath).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader =
            thread(name = "pi-reply-style-test-output", isDaemon = true) {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach { output.appendLine(it) } }
            }
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
        reader.join(2_000)
        val text = output.toString().trim()

        assertEquals("node 没有在 10 秒内结束：$text", true, finished)
        assertEquals("node 退出码非 0：$text", 0, process.exitValue())
        return text
    }

    private fun jsString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
