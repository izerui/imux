package com.github.izerui.imux.terminal

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * pi 桥接的**行为**测试：真的用 node 加载 `pi-imux-idea-mcp.js`，只把 fetch 换成假的。
 *
 * 不做源码文本断言——这个文件里几乎每一条规则（哪种失败可以重试、超时套在哪一层、
 * 工具报错怎么传给 pi）都能在保留字面量的前提下被改坏，只有真跑一遍才守得住。
 */
class PiIdeaMcpBehaviorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `pi 桥接分别列出工具并调用 IDEA 工具`() {
        val output =
            runHarness(
                """
                globalThis.fetch = async (_url, options) => {
                  const request = JSON.parse(options.body);
                  calls.push(request);
                  return respond(request, defaultResult(request));
                };

                const tool = await load();
                const listed = await tool.execute("call-1", { action: "list" });
                const called = await tool.execute(
                  "call-2",
                  { action: "call", tool: "search_symbol", arguments: { query: "TerminalHost" } },
                );
                const toolCall = calls.find((request) => request.method === "tools/call");
                report({
                  registered: tool.name,
                  listed: listed.content[0].text,
                  called: called.content[0].text,
                  projectPath: toolCall.params.arguments.projectPath,
                  urlDeleted: process.env.IMUX_IDEA_MCP_URL === undefined,
                  projectDeleted: process.env.IMUX_IDEA_MCP_PROJECT === undefined,
                });
                """.trimIndent(),
            )

        assertEquals(
            """{"registered":"idea_mcp","listed":"search_symbol: Search symbols","called":"found",""" +
                """"projectPath":"/workspace","urlDeleted":true,"projectDeleted":true}""",
            output,
        )
    }

    /**
     * **每一个请求都要带项目定向头。**
     *
     * 补 `projectPath` 实参只能覆盖 schema 里声明了该参数的工具；这个头覆盖全部请求，
     * 包括 `initialize` 与 `tools/list`。多项目窗口下缺了它，没写 projectPath 的调用会被
     * 服务端拒绝并要求反过来问用户选项目——本机开着 3 个项目时实测如此。
     */
    @Test
    fun `每个请求都带上项目定向头`() {
        val output =
            runHarness(
                """
                const seen = {};
                globalThis.fetch = async (_url, options) => {
                  const request = JSON.parse(options.body);
                  seen[request.method ?? "?"] = options.headers["IJ_MCP_SERVER_PROJECT_PATH"];
                  return respond(request, defaultResult(request));
                };

                const tool = await load();
                await tool.execute("c", { action: "call", tool: "search_symbol", arguments: {} });
                report(seen);
                """.trimIndent(),
            )

        assertEquals(
            """{"initialize":"/workspace","notifications/initialized":"/workspace",""" +
                """"tools/list":"/workspace","tools/call":"/workspace"}""",
            output,
        )
    }

    @Test
    fun `pi 开启引导后追加自定义提示词`() {
        val output =
            runHarness(
                """
                process.env.IMUX_IDEA_MCP_GUIDANCE = "Prefer semantic IDEA tools";
                globalThis.fetch = async (_url, options) => {
                  const request = JSON.parse(options.body);
                  return respond(request, defaultResult(request));
                };

                const tool = await load();
                report({
                  custom: tool.promptGuidelines[0],
                  routing: tool.promptGuidelines[1],
                  deleted: process.env.IMUX_IDEA_MCP_GUIDANCE === undefined,
                });
                """.trimIndent(),
            )

        assertEquals("Prefer semantic IDEA tools", jsonString(output, "custom"))
        assertEquals(
            "Call idea_mcp with action=list when the required IDEA tool name or arguments are unknown.",
            jsonString(output, "routing"),
        )
        assertEquals(true, jsonBoolean(output, "deleted"))
    }

    /**
     * **工具调用不套 10 秒超时。**
     *
     * IDEA 的 `build_project`、`execute_run_configuration`、`xdebug_*` 等待动作正常就会跑
     * 几十秒到几分钟。超时会被 abort 成一次失败，而失败在调用侧又与「连接断了」长得一样。
     *
     * 断言 fetch 收到的 signal：握手那几个请求必须带（卡住就是服务端不对），
     * `tools/call` 必须不带。只断「调用能返回」不行——时钟走不到 10 秒，恒真。
     */
    @Test
    fun `握手带超时而工具调用不带超时`() {
        val output =
            runHarness(
                """
                const signals = {};
                globalThis.fetch = async (_url, options) => {
                  const request = JSON.parse(options.body);
                  signals[request.method] = options.signal !== undefined;
                  return respond(request, defaultResult(request));
                };

                const tool = await load();
                await tool.execute("call-1", { action: "call", tool: "search_symbol", arguments: {} });
                report({
                  initialize: signals["initialize"],
                  toolsList: signals["tools/list"],
                  toolsCall: signals["tools/call"],
                });
                """.trimIndent(),
            )

        assertEquals(
            "握手必须带超时信号，tools/call 必须不带——否则长耗时的 IDEA 工具全部不可用",
            """{"initialize":true,"toolsList":true,"toolsCall":false}""",
            output,
        )
    }

    @Test
    fun `会话失效时只重试一次`() {
        val output =
            runHarness(
                """
                let toolCalls = 0;
                globalThis.fetch = async (_url, options) => {
                  const request = JSON.parse(options.body);
                  if (request.method !== "tools/call") return respond(request, defaultResult(request));
                  toolCalls += 1;
                  if (toolCalls === 1) return { ok: false, status: 404, headers: noHeader };
                  return respond(request, defaultResult(request));
                };

                const tool = await load();
                await tool.execute("c", { action: "call", tool: "search_symbol", arguments: {} });
                report({ toolCalls });
                """.trimIndent(),
            )

        assertEquals("""{"toolCalls":2}""", output)
    }

    @Test
    fun `请求超时后不重发工具调用`() {
        val output =
            runHarness(
                """
                let toolCalls = 0;
                globalThis.fetch = async (_url, options) => {
                  const request = JSON.parse(options.body);
                  if (request.method !== "tools/call") return respond(request, defaultResult(request));
                  toolCalls += 1;
                  throw new DOMException("timed out", "TimeoutError");
                };

                const tool = await load();
                try {
                  await tool.execute("c", { action: "call", tool: "search_symbol", arguments: {} });
                } catch {}
                report({ toolCalls });
                """.trimIndent(),
            )

        assertEquals("""{"toolCalls":1}""", output)
    }

    @Test
    fun `HTTP 错误后不重发工具调用`() {
        val output =
            runHarness(
                """
                let toolCalls = 0;
                globalThis.fetch = async (_url, options) => {
                  const request = JSON.parse(options.body);
                  if (request.method !== "tools/call") return respond(request, defaultResult(request));
                  toolCalls += 1;
                  return { ok: false, status: 500, headers: noHeader };
                };

                const tool = await load();
                try {
                  await tool.execute("c", { action: "call", tool: "search_symbol", arguments: {} });
                } catch {}
                report({ toolCalls });
                """.trimIndent(),
            )

        assertEquals("""{"toolCalls":1}""", output)
    }

    @Test
    fun `JSON RPC 业务错误后不重发工具调用`() {
        val output =
            runHarness(
                """
                let toolCalls = 0;
                globalThis.fetch = async (_url, options) => {
                  const request = JSON.parse(options.body);
                  if (request.method !== "tools/call") return respond(request, defaultResult(request));
                  toolCalls += 1;
                  return {
                    ok: true,
                    status: 200,
                    headers: noHeader,
                    text: async () => JSON.stringify({
                      jsonrpc: "2.0",
                      id: request.id,
                      error: { code: -32602, message: "bad arguments" },
                    }),
                  };
                };

                const tool = await load();
                try {
                  await tool.execute("c", { action: "call", tool: "search_symbol", arguments: {} });
                } catch {}
                report({ toolCalls });
                """.trimIndent(),
            )

        assertEquals("""{"toolCalls":1}""", output)
    }

    /**
     * **IDEA 侧报错必须抛出来。**
     *
     * pi 只认「execute 抛异常」这一种失败信号，正常返回一律记成调用成功。把 `isError`
     * 塞进 `details` 的旧写法，会让模型看到一次「成功」的重构，而 IDE 里什么都没发生。
     */
    @Test
    fun `IDEA 工具报错时抛给 pi 而不是记成成功`() {
        val output =
            runHarness(
                """
                globalThis.fetch = async (_url, options) => {
                  const request = JSON.parse(options.body);
                  if (request.method !== "tools/call") return respond(request, defaultResult(request));
                  return respond(request, { isError: true, content: [{ type: "text", text: "symbol not found" }] });
                };

                const tool = await load();
                let thrown;
                try {
                  await tool.execute("c", { action: "call", tool: "search_symbol", arguments: {} });
                } catch (error) {
                  thrown = error.message;
                }
                report({ thrown });
                """.trimIndent(),
            )

        assertEquals(
            """{"thrown":"IDEA MCP tool search_symbol failed: symbol not found"}""",
            output,
        )
    }

    /**
     * **空工具清单不进缓存。**
     *
     * `ensureInitialized` 的守卫是 `if (state.tools) return state.tools`，`[]` 为真。
     * 于是「项目还在建索引时问到的那一次空清单」会把整个 pi 会话钉死在零工具上：
     * `action=list` 永远报「没有工具」，`action=call` 永远 `Unknown IDEA MCP tool`，
     * 除了重启 pi 没有别的出路。
     */
    @Test
    fun `首次拿到空工具清单后仍能恢复`() {
        val output =
            runHarness(
                """
                let listings = 0;
                globalThis.fetch = async (_url, options) => {
                  const request = JSON.parse(options.body);
                  calls.push(request);
                  if (request.method !== "tools/list") return respond(request, defaultResult(request));
                  listings += 1;
                  return respond(request, listings === 1 ? { tools: [] } : defaultResult(request));
                };

                const tool = await load();
                const first = await tool.execute("c1", { action: "list" });
                const second = await tool.execute("c2", { action: "list" });
                const initializes = calls.filter((request) => request.method === "initialize").length;
                report({ first: first.details.count, second: second.details.count, initializes });
                """.trimIndent(),
            )

        assertEquals(0, jsonInt(output, "first"))
        assertEquals(1, jsonInt(output, "second"))
        assertEquals("已初始化的会话不能再次发送 initialize", 1, jsonInt(output, "initializes"))
    }

    /**
     * 在 node 里跑一段用例脚本。
     *
     * 脚本可用的公共件：`calls`（所有请求）、`respond`/`defaultResult`/`noHeader`（造响应）、
     * `load()`（加载扩展并返回注册到的工具）、`report()`（打印一行 JSON 给 Kotlin 侧断言）。
     * 被测脚本本身逐字节取自 `src/main/js`，只把两个 pi 运行时依赖换成等价的桩。
     */
    private fun runHarness(body: String): String {
        val script =
            File("src/main/js/pi-imux-idea-mcp.js")
                .readText()
                .replace(
                    """import { StringEnum } from "@earendil-works/pi-ai";""",
                    """const StringEnum = (values) => ({ enum: values });""",
                ).replace(
                    """import { Type } from "typebox";""",
                    """
                    const Type = {
                      Object: (value) => value,
                      Optional: (value) => value,
                      String: () => ({ type: "string" }),
                      Record: () => ({ type: "object" }),
                      Unknown: () => ({}),
                    };
                    """.trimIndent(),
                )
        val module = tmp.newFile("pi-imux-idea-mcp.mjs").apply { writeText(script) }
        val harness =
            tmp.newFile("run.mjs").apply {
                writeText(
                    """
                    process.env.IMUX_IDEA_MCP_URL = "http://127.0.0.1:64342/stream";
                    process.env.IMUX_IDEA_MCP_PROJECT = "/workspace";

                    const calls = [];
                    const noHeader = { get: () => null };
                    const sessionHeader = {
                      get: (name) => (name.toLowerCase() === "mcp-session-id" ? "session-1" : null),
                    };
                    const defaultResult = (request) =>
                      request.method === "initialize"
                        ? { protocolVersion: "2025-06-18", capabilities: {}, serverInfo: { name: "IDEA", version: "1" } }
                        : request.method === "tools/list"
                          ? { tools: [{
                              name: "search_symbol",
                              description: "Search symbols",
                              inputSchema: { properties: { query: {}, projectPath: {} } },
                            }] }
                          : request.method === "tools/call"
                            ? { content: [{ type: "text", text: "found" }] }
                            : undefined;
                    const respond = (request, result) => ({
                      ok: true,
                      status: 200,
                      headers: sessionHeader,
                      text: async () =>
                        result === undefined ? "" : JSON.stringify({ jsonrpc: "2.0", id: request.id, result }),
                    });
                    const report = (value) => console.log(JSON.stringify(value));
                    const load = async () => {
                      const extension = (await import(${jsString(module.absolutePath)})).default;
                      let registered;
                      extension({ registerTool(definition) { registered = definition; } });
                      return registered;
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

    private fun jsonInt(
        json: String,
        key: String,
    ): Int =
        Regex(""""$key":(\d+)""")
            .find(json)
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: error("$key 不在输出中：$json")

    private fun jsonString(
        json: String,
        key: String,
    ): String =
        Regex(""""$key":"([^"]*)"""")
            .find(json)
            ?.groupValues
            ?.get(1)
            ?: error("$key 不在输出中：$json")

    private fun jsonBoolean(
        json: String,
        key: String,
    ): Boolean =
        Regex(""""$key":(true|false)""")
            .find(json)
            ?.groupValues
            ?.get(1)
            ?.toBooleanStrict()
            ?: error("$key 不在输出中：$json")

    private fun jsString(value: String): String =
        "\"" +
            value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"") +
            "\""
}
