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
    fun `API 正常时注册会话切换与最终结束回调`() {
        assertEquals(
            "ok:session_start,message_end,agent_settled",
            runReporter(
                piExpression = "({ on(name) { (globalThis.__registered ??= []).push(name); } })",
                report = """"ok:" + (globalThis.__registered?.join(",") ?? "none")""",
            ),
        )
    }

    /** 缺少上报凭据时仍注册 session_start 做光标适配，但不注册终态上报。 */
    @Test
    fun `缺少环境变量时只注册光标适配`() {
        assertEquals(
            "ok:session_start",
            runReporter(
                piExpression = "({ on(name) { (globalThis.__registered ??= []).push(name); } })",
                report = """"ok:" + (globalThis.__registered?.join(",") ?? "none")""",
                env = emptyMap(),
            ),
        )
    }

    @Test
    fun `默认 editor 强制硬件光标并去掉反色假光标`() {
        assertEquals(
            "true|<ESC>[5 q|prefix:<ESC>_pi:c<BEL>中:suffix",
            runReporter(
                piExpression = "({ on(name, handler) { (globalThis.__handlers ??= {})[name] = handler; } })",
                beforeReport =
                    """
                    globalThis.__editorLines = ["prefix:\x1b_pi:c\x07\x1b[7m中\x1b[0m:suffix"];
                    const ui = {
                      factory: undefined,
                      getEditorComponent() { return this.factory; },
                      setEditorComponent(factory) { this.factory = factory; },
                    };
                    await globalThis.__handlers.session_start({}, {
                      mode: "tui",
                      ui,
                      sessionManager: { getSessionId: () => "s1", getCwd: () => "/tmp" },
                    });
                    const editor = ui.factory({
                      terminal: {
                        write(value) { globalThis.__cursorStyle = value; },
                      },
                      setShowHardwareCursor(value) { globalThis.__hardwareCursor = value; },
                    }, {}, {});
                    const rendered = editor.render(80)[0]
                      .replaceAll("\x1b", "<ESC>")
                      .replaceAll("\x07", "<BEL>");
                    const cursorStyle = globalThis.__cursorStyle.replaceAll("\x1b", "<ESC>");
                    globalThis.__rendered = `${'$'}{globalThis.__hardwareCursor}|${'$'}{cursorStyle}|${'$'}{rendered}`;
                    """.trimIndent(),
                report = "globalThis.__rendered",
            ),
        )
    }

    @Test
    fun `配置光标样式不改写后续终端输出`() {
        assertEquals(
            "<ESC>[5 q|frame|move|show",
            runReporter(
                piExpression = "({ on(name, handler) { (globalThis.__handlers ??= {})[name] = handler; } })",
                beforeReport =
                    """
                    const customFactory = () => ({ render: () => ["editor"] });
                    const ui = {
                      factory: customFactory,
                      getEditorComponent() { return this.factory; },
                      setEditorComponent(factory) { this.factory = factory; },
                    };
                    await globalThis.__handlers.session_start({}, {
                      mode: "tui",
                      ui,
                      sessionManager: { getSessionId: () => "s1", getCwd: () => "/tmp" },
                    });
                    const writes = [];
                    const terminal = {
                      write(value) { writes.push(value.replaceAll("\x1b", "<ESC>")); },
                      showCursor() { writes.push("show"); },
                    };
                    ui.factory({
                      terminal,
                      setShowHardwareCursor() {},
                    }, {}, {});
                    terminal.write("frame");
                    terminal.write("move");
                    terminal.showCursor();
                    globalThis.__rendered = writes.join("|");
                    """.trimIndent(),
                report = "globalThis.__rendered",
            ),
        )
    }

    @Test
    fun `行尾删除合成光标空格只保留硬件光标位置`() {
        assertEquals(
            "true|abc<ESC>_pi:c<BEL>",
            runReporter(
                piExpression = "({ on(name, handler) { (globalThis.__handlers ??= {})[name] = handler; } })",
                beforeReport =
                    """
                    const customFactory = () => ({
                      getCursor: () => ({ line: 0, col: 3 }),
                      getLines: () => ["abc"],
                      render: () => ["abc\x1b_pi:c\x07\x1b[7m \x1b[0m"],
                    });
                    const ui = {
                      factory: customFactory,
                      getEditorComponent() { return this.factory; },
                      setEditorComponent(factory) { this.factory = factory; },
                    };
                    await globalThis.__handlers.session_start({}, {
                      mode: "tui",
                      ui,
                      sessionManager: { getSessionId: () => "s1", getCwd: () => "/tmp" },
                    });
                    const editor = ui.factory({
                      setShowHardwareCursor(value) { globalThis.__hardwareCursor = value; },
                    }, {}, {});
                    const rendered = editor.render(80)[0]
                      .replaceAll("\x1b", "<ESC>")
                      .replaceAll("\x07", "<BEL>");
                    globalThis.__rendered = `${'$'}{globalThis.__hardwareCursor}|${'$'}{rendered}`;
                    """.trimIndent(),
                report = "globalThis.__rendered",
            ),
        )
    }

    @Test
    fun `清屏重绘把输入光标合并进同步输出`() {
        assertEquals(
            "<ESC>[?2026h<ESC>[2J<ESC>[H<ESC>[3Jframe<ESC>[4;5H<ESC>[?2026l|show|row=13",
            runReporter(
                piExpression = "({ on(name, handler) { (globalThis.__handlers ??= {})[name] = handler; } })",
                beforeReport =
                    """
                    const customFactory = () => ({
                      getCursor: () => ({ line: 0, col: 4 }),
                      getLines: () => ["text"],
                      render: () => ["text\x1b_pi:c\x07\x1b[7m \x1b[0m"],
                    });
                    const ui = {
                      factory: customFactory,
                      getEditorComponent() { return this.factory; },
                      setEditorComponent(factory) { this.factory = factory; },
                    };
                    await globalThis.__handlers.session_start({}, {
                      mode: "tui",
                      ui,
                      sessionManager: { getSessionId: () => "s1", getCwd: () => "/tmp" },
                    });
                    const writes = [];
                    const terminal = {
                      columns: 60,
                      rows: 10,
                      write(value) {
                        if (value !== "\x1b[5 q") writes.push(value.replaceAll("\x1b", "<ESC>"));
                      },
                      showCursor() { writes.push("show"); },
                      hideCursor() { writes.push("hide"); },
                    };
                    const tui = {
                      mode: "regular",
                      terminal,
                      hardwareCursorRow: 19,
                      setShowHardwareCursor() {},
                      getShowHardwareCursor() { return true; },
                      positionHardwareCursor(cursor, totalLines) {
                        writes.push(`relative:${'$'}{cursor.row}:${'$'}{cursor.col}:${'$'}{totalLines}`);
                      },
                    };
                    const editor = ui.factory(tui, {}, {});
                    editor.render(60);
                    terminal.write("\x1b[?2026h\x1b[2J\x1b[H\x1b[3Jframe\x1b[?2026l");
                    tui.positionHardwareCursor({ row: 13, col: 4 }, 20);
                    writes.push(`row=${'$'}{tui.hardwareCursorRow}`);
                    globalThis.__rendered = writes.join("|");
                    """.trimIndent(),
                report = "globalThis.__rendered",
            ),
        )
    }

    @Test
    fun `硬件光标回调缺席时微任务冲刷重绘帧`() {
        assertEquals(
            "frame<ESC>[2J<ESC>[H<ESC>[3J<ESC>[?2026l",
            runReporter(
                piExpression = "({ on(name, handler) { (globalThis.__handlers ??= {})[name] = handler; } })",
                beforeReport =
                    """
                    const customFactory = () => ({ render: () => ["editor"] });
                    const ui = {
                      factory: customFactory,
                      getEditorComponent() { return this.factory; },
                      setEditorComponent(factory) { this.factory = factory; },
                    };
                    await globalThis.__handlers.session_start({}, {
                      mode: "tui",
                      ui,
                      sessionManager: { getSessionId: () => "s1", getCwd: () => "/tmp" },
                    });
                    const writes = [];
                    const terminal = {
                      write(value) {
                        if (value !== "\x1b[5 q") writes.push(value.replaceAll("\x1b", "<ESC>"));
                      },
                    };
                    const tui = {
                      mode: "regular",
                      terminal,
                      setShowHardwareCursor() {},
                      positionHardwareCursor() {},
                    };
                    ui.factory(tui, {}, {}).render(60);
                    terminal.write("frame\x1b[2J\x1b[H\x1b[3J\x1b[?2026l");
                    await Promise.resolve();
                    globalThis.__rendered = writes.join("|");
                    """.trimIndent(),
                report = "globalThis.__rendered",
            ),
        )
    }

    @Test
    fun `连续清屏重绘逐帧恢复输入光标`() {
        assertEquals(
            "A<ESC>[2J<ESC>[H<ESC>[3J<ESC>[4;5H<ESC>[?2026l|show|B<ESC>[2J<ESC>[H<ESC>[3J<ESC>[6;3H<ESC>[?2026l|show",
            runReporter(
                piExpression = "({ on(name, handler) { (globalThis.__handlers ??= {})[name] = handler; } })",
                beforeReport =
                    """
                    const customFactory = () => ({
                      getCursor: () => ({ line: 0, col: 0 }),
                      getLines: () => [""],
                      render: () => ["\x1b_pi:c\x07\x1b[7m \x1b[0m"],
                    });
                    const ui = {
                      factory: customFactory,
                      getEditorComponent() { return this.factory; },
                      setEditorComponent(factory) { this.factory = factory; },
                    };
                    await globalThis.__handlers.session_start({}, {
                      mode: "tui",
                      ui,
                      sessionManager: { getSessionId: () => "s1", getCwd: () => "/tmp" },
                    });
                    const writes = [];
                    const terminal = {
                      columns: 60,
                      rows: 10,
                      write(value) {
                        if (value !== "\x1b[5 q") writes.push(value.replaceAll("\x1b", "<ESC>"));
                      },
                      showCursor() { writes.push("show"); },
                    };
                    const tui = {
                      mode: "regular",
                      terminal,
                      setShowHardwareCursor() {},
                      getShowHardwareCursor() { return true; },
                      positionHardwareCursor() { writes.push("relative"); },
                    };
                    const editor = ui.factory(tui, {}, {});
                    editor.render(60);
                    terminal.write("A\x1b[2J\x1b[H\x1b[3J\x1b[?2026l");
                    tui.positionHardwareCursor({ row: 13, col: 4 }, 20);
                    terminal.write("B\x1b[2J\x1b[H\x1b[3J\x1b[?2026l");
                    tui.positionHardwareCursor({ row: 10, col: 2 }, 15);
                    globalThis.__rendered = writes.join("|");
                    """.trimIndent(),
                report = "globalThis.__rendered",
            ),
        )
    }

    @Test
    fun `增量渲染保留 pi 的相对光标定位`() {
        assertEquals(
            "relative:true:2:3:8",
            runReporter(
                piExpression = "({ on(name, handler) { (globalThis.__handlers ??= {})[name] = handler; } })",
                beforeReport =
                    """
                    const customFactory = () => ({
                      getCursor: () => ({ line: 0, col: 0 }),
                      getLines: () => [""],
                      render: () => ["\x1b_pi:c\x07\x1b[7m \x1b[0m"],
                    });
                    const ui = {
                      factory: customFactory,
                      getEditorComponent() { return this.factory; },
                      setEditorComponent(factory) { this.factory = factory; },
                    };
                    await globalThis.__handlers.session_start({}, {
                      mode: "tui",
                      ui,
                      sessionManager: { getSessionId: () => "s1", getCwd: () => "/tmp" },
                    });
                    const writes = [];
                    const terminal = {
                      columns: 60,
                      rows: 10,
                      write(value) {
                        if (value !== "\x1b[5 q") writes.push(value.replaceAll("\x1b", "<ESC>"));
                      },
                    };
                    const tui = {
                      mode: "regular",
                      terminal,
                      setShowHardwareCursor() {},
                      positionHardwareCursor(cursor, totalLines) {
                        writes.push(`relative:${'$'}{this === tui}:${'$'}{cursor.row}:${'$'}{cursor.col}:${'$'}{totalLines}`);
                      },
                    };
                    const editor = ui.factory(tui, {}, {});
                    editor.render(60);
                    tui.positionHardwareCursor({ row: 2, col: 3 }, 8);
                    globalThis.__rendered = writes.join("|");
                    """.trimIndent(),
                report = "globalThis.__rendered",
            ),
        )
    }

    @Test
    fun `包装已有自定义 editor 且重复 session start 不叠加`() {
        assertEquals(
            "true|custom:<ESC>_pi:c<BEL>X",
            runReporter(
                piExpression = "({ on(name, handler) { (globalThis.__handlers ??= {})[name] = handler; } })",
                beforeReport =
                    """
                    const customFactory = () => ({ render: () => ["custom:\x1b_pi:c\x07\x1b[7mX\x1b[27m"] });
                    const ui = {
                      factory: customFactory,
                      getEditorComponent() { return this.factory; },
                      setEditorComponent(factory) { this.factory = factory; },
                    };
                    const ctx = {
                      mode: "tui",
                      ui,
                      sessionManager: { getSessionId: () => "s1", getCwd: () => "/tmp" },
                    };
                    await globalThis.__handlers.session_start({}, ctx);
                    const installedOnce = ui.factory;
                    await globalThis.__handlers.session_start({}, ctx);
                    const rendered = ui.factory({}, {}, {}).render(80)[0]
                      .replaceAll("\x1b", "<ESC>")
                      .replaceAll("\x07", "<BEL>");
                    globalThis.__result = `${'$'}{installedOnce === ui.factory}|${'$'}{rendered}`;
                    """.trimIndent(),
                report = "globalThis.__result",
            ),
        )
    }

    @Test
    fun `用户消息提示不携带正文且不等待请求完成`() {
        assertEquals(
            "returned|{\"type\":\"user_message\",\"tabId\":\"imux-tab\",\"sessionId\":\"s1\",\"cwd\":\"/tmp\"}",
            runReporter(
                piExpression = "({ on(name, handler) { (globalThis.__handlers ??= {})[name] = handler; } })",
                beforeReport =
                    """
                    globalThis.fetch = (_url, options) => {
                      globalThis.__body = JSON.parse(options.body);
                      return new Promise(() => {});
                    };
                    const result = globalThis.__handlers.message_end(
                      { message: { role: "user", content: "敏感正文" } },
                      { sessionManager: { getSessionId: () => "s1", getCwd: () => "/tmp" } },
                    );
                    globalThis.__result = `${'$'}{result === undefined ? "returned" : "blocked"}|${'$'}{JSON.stringify(globalThis.__body)}`;
                    """.trimIndent(),
                report = "globalThis.__result",
            ),
        )
    }

    @Test
    fun `上报官方会话 id 与 cwd 并清除子进程环境凭据`() {
        assertEquals(
            """{"envCleared":true,"body":{"type":"session_start","tabId":"imux-tab","sessionId":"custom.id-1","cwd":"/Users/demo/project"}}""",
            runReporter(
                piExpression =
                    """
                    ({
                        on(name, handler) { (globalThis.__handlers ??= {})[name] = handler; }
                    })
                    """.trimIndent(),
                beforeReport =
                    """
                    globalThis.fetch = (_url, options) => {
                      globalThis.__body = JSON.parse(options.body);
                      return Promise.resolve({ ok: true });
                    };
                    const ctx = {
                      sessionManager: {
                        getSessionId: () => "custom.id-1",
                        getCwd: () => "/Users/demo/project",
                      },
                    };
                    await globalThis.__handlers.session_start({}, ctx);
                    """.trimIndent(),
                report =
                    """
                    JSON.stringify({
                      envCleared: !process.env.IMUX_REPORT_URL && !process.env.IMUX_TOKEN && !process.env.IMUX_TAB,
                      body: globalThis.__body,
                    })
                    """.trimIndent(),
            ),
        )
    }

    @Test
    fun `扩展重载后从 globalThis 恢复凭据并上报 settled`() {
        assertEquals(
            """{"type":"agent_settled","tabId":"imux-tab","sessionId":"abc-1","cwd":"/tmp/project","stopReason":"error","messageId":"a1"}""",
            runReporter(
                piExpression =
                    """
                    ({
                        on(name, handler) { (globalThis.__handlers ??= {})[name] = handler; }
                    })
                    """.trimIndent(),
                beforeReport =
                    """
                    globalThis.fetch = (_url, options) => {
                      globalThis.__body = JSON.parse(options.body);
                      return Promise.resolve({ ok: true });
                    };
                    const second = {
                      on(name, handler) { (globalThis.__secondHandlers ??= {})[name] = handler; }
                    };
                    reporter(second);
                    const ctx = {
                      sessionManager: {
                        getSessionId: () => "abc-1",
                        getCwd: () => "/tmp/project",
                        getBranch: () => [
                          { type: "message", id: "a1", message: { role: "assistant", stopReason: "error" } },
                        ],
                      },
                    };
                    await globalThis.__secondHandlers.agent_settled({}, ctx);
                    """.trimIndent(),
                report = "JSON.stringify(globalThis.__body)",
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
        beforeReport: String = "",
        env: Map<String, String> =
            mapOf(
                "IMUX_REPORT_URL" to "http://127.0.0.1:1/imux/pi-session",
                "IMUX_TOKEN" to "token",
                "IMUX_TAB" to "imux-tab",
            ),
    ): String {
        val node = File("/opt/homebrew/bin/node").takeIf { it.canExecute() }?.absolutePath ?: "node"
        assumeTrue("需要 node 才能执行扩展脚本", canRun(node))

        val packageDir = File(tmp.root, "node_modules/@earendil-works/pi-coding-agent").apply { mkdirs() }
        File(packageDir, "package.json").writeText(
            """{"type":"module","exports":"./index.js"}""",
        )
        File(packageDir, "index.js").writeText(
            """
            export class CustomEditor {
              render() { return globalThis.__editorLines ?? []; }
            }
            """.trimIndent(),
        )

        // 源码是 ESM（export default），必须以 .mjs 载入才能不依赖 package.json
        val script = File(tmp.root, "reporter.mjs")
        script.writeText(File("src/main/js/pi-imux-reporter.js").readText())

        val driver = File(tmp.root, "driver.mjs")
        driver.writeText(
            """
            import reporter from "./reporter.mjs";
            reporter($piExpression);
            $beforeReport
            console.log($report);
            """.trimIndent(),
        )

        val process =
            ProcessBuilder(node, driver.absolutePath)
                .directory(tmp.root)
                .redirectErrorStream(true)
                .apply {
                    environment().keys.retainAll(setOf("PATH", "HOME"))
                    environment().putAll(env)
                }.start()
        process.waitFor(30, TimeUnit.SECONDS)
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        assertEquals("脚本必须正常退出，异常冒到加载器就是会话起不来：\n$output", 0, process.exitValue())
        return output
    }

    private fun canRun(node: String): Boolean =
        runCatching {
            ProcessBuilder(node, "--version")
                .redirectErrorStream(true)
                .start()
                .also { it.waitFor(10, TimeUnit.SECONDS) }
                .exitValue() == 0
        }.getOrDefault(false)
}
