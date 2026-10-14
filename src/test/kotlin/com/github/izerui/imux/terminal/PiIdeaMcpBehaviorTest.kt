package com.github.izerui.imux.terminal

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 在 Node 中加载实际扩展，验证 imux 与 Pi 原生 MCP 注册接口之间的契约。 */
class PiIdeaMcpBehaviorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `通过原生接口注册当前项目的 IDEA 服务`() {
        val output =
            runHarness(
                """
                await load();
                report({ registrations, customTools, requests });
                """.trimIndent(),
            )

        val result = JsonParser.parseString(output).asJsonObject
        val registrations = result.getAsJsonArray("registrations")
        assertEquals(1, registrations.size())
        val registration = registrations[0].asJsonObject
        assertEquals("idea", registration["name"].asString)
        val config = registration.getAsJsonObject("config")
        assertEquals("http://127.0.0.1:64342/stream", config["url"].asString)
        assertEquals("/workspace", config.getAsJsonObject("headers")["IJ_MCP_SERVER_PROJECT_PATH"].asString)
        assertEquals("deferred", config["exposure"].asString)
        assertEquals(600, config["timeout"].asInt)
        assertEquals(0, result["customTools"].asInt)
        assertEquals(0, result["requests"].asInt)
    }

    @Test
    fun `旧版 Pi 跳过服务注册和引导注入并清除启动环境`() {
        val output =
            runHarness(
                """
                process.env.IMUX_IDEA_MCP_GUIDANCE = "Use IDEA tools";
                await load({ ...api, registerMcpServer: undefined });
                report({
                  registrations: registrations.length,
                  customTools,
                  requests,
                  hooks: handlers.size,
                  environment: remainingEnvironment(),
                });
                """.trimIndent(),
            )

        assertEquals("""{"registrations":0,"customTools":0,"requests":0,"hooks":0,"environment":{}}""", output)
    }

    @Test
    fun `原生注册后清除启动环境`() {
        val output =
            runHarness(
                """
                process.env.IMUX_IDEA_MCP_GUIDANCE = "Use IDEA tools";
                await load();
                report(remainingEnvironment());
                """.trimIndent(),
            )

        assertEquals("{}", output)
    }

    @Test
    fun `扩展重载后恢复原生注册与引导`() {
        val output =
            runHarness(
                """
                process.env.IMUX_IDEA_MCP_GUIDANCE = "Use IDEA tools";
                await load();
                handlers.clear();
                await load();
                const event = { systemPromptOptions: { sections: {} } };
                handlers.get("before_agent_start")(event);
                report({
                  registrations,
                  guidance: event.systemPromptOptions.sections.imux_idea_mcp,
                });
                """.trimIndent(),
            )

        val result = JsonParser.parseString(output).asJsonObject
        val registrations = result.getAsJsonArray("registrations")
        assertEquals(2, registrations.size())
        assertEquals(registrations[0], registrations[1])
        assertEquals("Use IDEA tools", result["guidance"].asString)
    }

    @Test
    fun `引导追加到独立提示词段落且不重复累加`() {
        val output =
            runHarness(
                """
                process.env.IMUX_IDEA_MCP_GUIDANCE = "Prefer semantic IDEA tools";
                await load();
                const event = { systemPromptOptions: { sections: { existing: "Keep this" } } };
                const handler = handlers.get("before_agent_start");
                handler(event);
                handler(event);
                report(event.systemPromptOptions.sections);
                """.trimIndent(),
            )

        assertEquals(
            """{"existing":"Keep this","imux_idea_mcp":"Prefer semantic IDEA tools"}""",
            output,
        )
    }

    @Test
    fun `未开启引导时只注册服务`() {
        val output =
            runHarness(
                """
                await load();
                report({ registrations: registrations.length, hooks: handlers.size });
                """.trimIndent(),
            )

        assertEquals("""{"registrations":1,"hooks":0}""", output)
    }

    @Test
    fun `没有端点时不注入服务或引导`() {
        val output =
            runHarness(
                """
                delete process.env.IMUX_IDEA_MCP_URL;
                process.env.IMUX_IDEA_MCP_GUIDANCE = "Use IDEA tools";
                await load();
                report({ registrations: registrations.length, hooks: handlers.size });
                """.trimIndent(),
            )

        assertEquals("""{"registrations":0,"hooks":0}""", output)
    }

    @Test
    fun `没有项目路径时不发送空项目头`() {
        val output =
            runHarness(
                """
                delete process.env.IMUX_IDEA_MCP_PROJECT;
                await load();
                report(registrations[0].config.headers);
                """.trimIndent(),
            )

        assertEquals("{}", output)
    }

    @Test
    fun `原生注册失败不阻止启动也不注入引导`() {
        val output =
            runHarness(
                """
                process.env.IMUX_IDEA_MCP_GUIDANCE = "Use IDEA tools";
                let attempts = 0;
                await load({
                  ...api,
                  registerMcpServer() {
                    attempts += 1;
                    throw new Error("MCP unavailable");
                  },
                });
                report({ attempts, continued: true, hooks: handlers.size, customTools });
                """.trimIndent(),
            )

        assertEquals("""{"attempts":1,"continued":true,"hooks":0,"customTools":0}""", output)
    }

    private fun runHarness(body: String): String {
        val module =
            tmp.newFile("pi-imux-idea-mcp.mjs").apply {
                writeText(File("src/main/js/pi-imux-idea-mcp.js").readText())
            }
        val harness =
            tmp.newFile("run.mjs").apply {
                writeText(
                    """
                    process.env.IMUX_IDEA_MCP_URL = "http://127.0.0.1:64342/stream";
                    process.env.IMUX_IDEA_MCP_PROJECT = "/workspace";
                    delete process.env.IMUX_IDEA_MCP_GUIDANCE;

                    const registrations = [];
                    const handlers = new Map();
                    let customTools = 0;
                    let requests = 0;
                    globalThis.fetch = async () => { requests += 1; throw new Error("Unexpected HTTP request"); };
                    const api = {
                      registerMcpServer(name, config) { registrations.push({ name, config }); },
                      registerTool() { customTools += 1; },
                      on(name, handler) { handlers.set(name, handler); },
                    };
                    const remainingEnvironment = () => ({
                      url: process.env.IMUX_IDEA_MCP_URL,
                      project: process.env.IMUX_IDEA_MCP_PROJECT,
                      guidance: process.env.IMUX_IDEA_MCP_GUIDANCE,
                    });
                    const report = (value) => console.log(JSON.stringify(value));
                    let loads = 0;
                    const load = async (target = api) => {
                      const extension = (await import(${jsString(module.toURI().toString())} + "?load=" + loads++)).default;
                      extension(target);
                    };

                    ${body.replace("\n", "\n                    ")}
                    """.trimIndent(),
                )
            }

        val process =
            ProcessBuilder("node", harness.absolutePath)
                .redirectErrorStream(true)
                .start()
        val output = StringBuilder()
        val reader =
            thread(name = "pi-idea-mcp-test-output", isDaemon = true) {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> output.appendLine(line) }
                }
            }
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
        reader.join(2_000)
        val text = output.toString().trim()

        assertEquals("node 没有在 10 秒内结束：$text", true, finished)
        assertEquals("node 输出读取线程没有结束：$text", false, reader.isAlive)
        assertEquals("node 退出码非 0：$text", 0, process.exitValue())
        return text
    }

    private fun jsString(value: String): String =
        "\"" +
            value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"") +
            "\""
}
