package com.github.izerui.imux.agent

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 模拟智能体 CLI 的 Java 子进程（跨平台，不依赖 /bin/sh）。整个测试进程只编译一次。
 *
 * 模式：
 * - `claude-ok`：先打印杂讯，再打印标记行和一组 Claude stream-json 事件；最终回答报告收到的 stdin 字符数
 * - `claude-error`：Claude 报告 is_error，stderr 输出详情，退出码 1
 * - `exit-nonzero`：只在 stderr 输出一行，退出码 3
 * - `huge-answer`：最终回答超过 [MAX_AGENT_OUTPUT_CHARS]
 * - `plain`：标记行之后输出非 JSON 文本
 * - `block`：打印标记行后阻塞
 * - `close-early`：只读 16 字节就关闭 stdin，然后正常给出回答
 * - `pi-length`：先给出合法节点，最后一条助手消息因长度截断（stopReason=length），随后 agent_end，退出码 0
 * - `pi-error`：最后一条助手消息带部分合法节点但 stopReason=error，附 errorMessage，退出码 0
 * - `pi-length-no-end`：同 pi-length，但没有 agent_end 事件
 * - `pi-recovered`：按 Pi 真实顺序 message_end(error) → agent_end → 自动重试 message_end(stop) → agent_end，退出码 0
 * - `pi-recovered-empty`：先给出合法节点但 length 截断，重试后最后一条 stopReason=stop 但正文为空，agent_end，退出码 0
 * - `codex-incomplete`：输出合法节点后开始调用工具，没有 turn.completed 就以 0 退出
 * - `codex-ok`：同样的过程，工具完成后给出最终回答并 turn.completed
 * - `codex-second-turn-empty`：第一轮输出合法节点并完成，第二轮没有回答也没有工具事件就完成，退出码 0
 * - `codex-second-turn-answer`：同上，但第二轮输出新节点后完成
 * - `codex-reconnect`：流断开报 error 后重连成功，给出回答并 turn.completed，退出码 0
 * - `codex-error-final`：给出回答后报 error，没有 turn.completed 就以 0 退出
 * - `pi-tool-use-last`：最后一条助手消息停在 toolUse，没有最终回答，退出码 0
 * - `spawn-orphan`：启动一个长时间休眠的后台子进程，稍等后给出带其 pid 的回答并立即以 0 退出，留下被系统托管的子进程
 * - `tool-orphan`：模拟工具启动后台子进程：输出 tool_use / tool_result 事件后短暂停留，随即给出回答以 0 退出，留下子进程
 * - `echo`：原样回显 stdin（不打印标记）
 */
internal object AgentTestProcesses {
    private val helperDir: Path by lazy { compile() }

    fun command(mode: String, sessionId: String = "00000000-0000-0000-0000-000000000000"): List<String> =
        listOf(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            helperDir.toString(),
            "AgentHelper",
            mode,
            sessionId,
        )

    private fun compile(): Path {
        val dir = Files.createTempDirectory("imux-agent-helper")
        dir.toFile().deleteOnExit()
        val source = dir.resolve("AgentHelper.java")
        Files.writeString(source, SOURCE.replace("__MARKER__", AGENT_OUTPUT_MARKER).replace("__HUGE__", (MAX_AGENT_OUTPUT_CHARS + 10).toString()))
        val javac = Path.of(System.getProperty("java.home"), "bin", "javac").toString()
        val process = ProcessBuilder(javac, "-encoding", "UTF-8", source.toString())
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "javac failed: $output" }
        return dir
    }

    private val SOURCE = """
        import java.nio.charset.StandardCharsets;

        public class AgentHelper {
            static void out(String s) {
                System.out.println(s);
                System.out.flush();
            }

            static String piMessage(String text, String stopReason, String errorMessage) {
                return "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],"
                    + "\"stopReason\":\"" + stopReason + "\""
                    + (errorMessage == null ? "" : ",\"errorMessage\":\"" + errorMessage + "\"")
                    + "}}";
            }

            static String codexMessage(String text) {
                return "{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"" + text + "\"}}";
            }

            static Process spawnOrphan(String sessionId) throws Exception {
                String javaBin = System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java";
                return new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"), "AgentHelper", "block", sessionId)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            }

            public static void main(String[] args) throws Exception {
                String mode = args[0];
                String sessionId = args[1];
                switch (mode) {
                    case "claude-ok": {
                        String input = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
                        out("shell banner noise");
                        out("{\"type\":\"result\",\"result\":\"noise before marker\"}");
                        out("__MARKER__");
                        out("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"" + sessionId + "\",\"model\":\"test-model\"}");
                        out("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Read\",\"input\":{\"file_path\":\"/tmp/session.jsonl\"}}]}}");
                        out("{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"ok\"}]}}");
                        out("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"draft\"}]}}");
                        out("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"session_id\":\"" + sessionId + "\",\"result\":\"[MAIN] received " + input.length() + " chars\"}");
                        break;
                    }
                    case "claude-error": {
                        System.in.readAllBytes();
                        out("__MARKER__");
                        out("{\"type\":\"result\",\"is_error\":true,\"errors\":[\"context window exceeded\"]}");
                        System.err.println("stderr detail");
                        System.exit(1);
                    }
                    case "exit-nonzero": {
                        System.in.readAllBytes();
                        out("__MARKER__");
                        System.err.println("command not found: claude");
                        System.exit(3);
                    }
                    case "huge-answer": {
                        System.in.readAllBytes();
                        out("__MARKER__");
                        out("{\"type\":\"result\",\"is_error\":false,\"result\":\"" + "x".repeat(__HUGE__) + "\"}");
                        break;
                    }
                    case "plain": {
                        System.in.readAllBytes();
                        out("__MARKER__");
                        out("[MAIN] plain text answer");
                        break;
                    }
                    case "block": {
                        out("__MARKER__");
                        Thread.sleep(300000);
                        break;
                    }
                    case "close-early": {
                        System.in.read(new byte[16]);
                        System.in.close();
                        out("__MARKER__");
                        out("{\"type\":\"result\",\"is_error\":false,\"result\":\"[MAIN] only saw the beginning\"}");
                        break;
                    }
                    case "pi-length":
                    case "pi-length-no-end": {
                        System.in.readAllBytes();
                        out("__MARKER__");
                        out("{\"type\":\"agent_start\"}");
                        out(piMessage("[MAIN] 第一阶段完成\\n[MAIN] 第二阶段完成\\n[MAIN] 第三阶段做到一半", "length", null));
                        if (mode.equals("pi-length")) out("{\"type\":\"agent_end\"}");
                        break;
                    }
                    case "pi-error": {
                        System.in.readAllBytes();
                        out("__MARKER__");
                        out("{\"type\":\"agent_start\"}");
                        out(piMessage("[MAIN] 已梳理前半段", "error", "429 rate limited"));
                        out("{\"type\":\"agent_end\"}");
                        break;
                    }
                    case "pi-recovered": {
                        System.in.readAllBytes();
                        out("__MARKER__");
                        out("{\"type\":\"agent_start\"}");
                        out(piMessage("[MAIN] 半截", "error", "overloaded"));
                        out("{\"type\":\"agent_end\"}");
                        out(piMessage("[MAIN] 完整时间线", "stop", null));
                        out("{\"type\":\"agent_end\"}");
                        break;
                    }
                    case "pi-recovered-empty": {
                        System.in.readAllBytes();
                        out("__MARKER__");
                        out("{\"type\":\"agent_start\"}");
                        out(piMessage("[MAIN] 旧节点一\\n[MAIN] 旧节点二", "length", null));
                        out("{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":[],\"stopReason\":\"stop\"}}");
                        out("{\"type\":\"agent_end\"}");
                        break;
                    }
                    case "codex-incomplete":
                    case "codex-ok": {
                        System.in.readAllBytes();
                        out("__MARKER__");
                        out("{\"type\":\"thread.started\"}");
                        out("{\"type\":\"turn.started\"}");
                        out(codexMessage("[MAIN] 中途节点一\\n[MAIN] 中途节点二"));
                        out("{\"type\":\"item.started\",\"item\":{\"type\":\"command_execution\",\"command\":\"rg -n user session.jsonl\"}}");
                        if (mode.equals("codex-ok")) {
                            out("{\"type\":\"item.completed\",\"item\":{\"type\":\"command_execution\",\"command\":\"rg -n user session.jsonl\"}}");
                            out(codexMessage("[MAIN] 最终节点"));
                            out("{\"type\":\"turn.completed\"}");
                        }
                        break;
                    }
                    case "codex-second-turn-empty":
                    case "codex-second-turn-answer": {
                        System.in.readAllBytes();
                        out("__MARKER__");
                        out("{\"type\":\"thread.started\"}");
                        out("{\"type\":\"turn.started\"}");
                        out(codexMessage("[MAIN] 第一轮旧节点"));
                        out("{\"type\":\"turn.completed\"}");
                        out("{\"type\":\"turn.started\"}");
                        if (mode.equals("codex-second-turn-answer")) out(codexMessage("[MAIN] 第二轮新节点"));
                        out("{\"type\":\"turn.completed\"}");
                        break;
                    }
                    case "codex-reconnect":
                    case "codex-error-final": {
                        System.in.readAllBytes();
                        out("__MARKER__");
                        out("{\"type\":\"thread.started\"}");
                        out("{\"type\":\"turn.started\"}");
                        if (mode.equals("codex-reconnect")) {
                            out("{\"type\":\"error\",\"message\":\"Reconnecting... 1/5 (stream disconnected)\"}");
                            out(codexMessage("[MAIN] 重连后的完整时间线"));
                            out("{\"type\":\"turn.completed\"}");
                        } else {
                            out(codexMessage("[MAIN] 半截时间线"));
                            out("{\"type\":\"error\",\"message\":\"stream disconnected before completion\"}");
                        }
                        break;
                    }
                    case "pi-tool-use-last": {
                        System.in.readAllBytes();
                        out("__MARKER__");
                        out(piMessage("[MAIN] 我先读一下文件", "toolUse", null));
                        out("{\"type\":\"agent_end\"}");
                        break;
                    }
                    case "tool-orphan": {
                        System.in.readAllBytes();
                        out("__MARKER__");
                        Process orphan = spawnOrphan(sessionId);
                        out("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Bash\",\"input\":{\"command\":\"server &\"}}]}}");
                        out("{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"started\"}]}}");
                        // 停留时间远短于测试里关掉的定时扫描，只够运行器读到上面的工具事件
                        Thread.sleep(300);
                        out("{\"type\":\"result\",\"is_error\":false,\"result\":\"[MAIN] orphan " + orphan.pid() + "\"}");
                        System.exit(0);
                    }
                    case "spawn-orphan": {
                        System.in.readAllBytes();
                        Process orphan = spawnOrphan(sessionId);
                        // 让 CLI 还活着的这段时间足够被扫描到，然后直接退出、把子进程留下
                        Thread.sleep(800);
                        out("__MARKER__");
                        out("{\"type\":\"result\",\"is_error\":false,\"result\":\"[MAIN] orphan " + orphan.pid() + "\"}");
                        System.exit(0);
                    }
                    case "echo": {
                        System.out.write(System.in.readAllBytes());
                        System.out.flush();
                        break;
                    }
                    default:
                        System.exit(64);
                }
            }
        }
    """.trimIndent()
}
