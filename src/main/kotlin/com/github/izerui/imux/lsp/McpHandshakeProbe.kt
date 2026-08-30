package com.github.izerui.imux.lsp

import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一条最小的 MCP `initialize` 请求。
 *
 * `protocolVersion` 取一个已发布的版本即可：server 要么按自己的版本回，要么镜像
 * 客户端请求的版本，两种做法都会带上 `result` 与 `protocolVersion`，
 * [handshakeSucceeded] 认的就是这个。这里不做版本协商，只判活。
 */
private const val INITIALIZE_REQUEST =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05",""" +
        """"capabilities":{},"clientInfo":{"name":"imux","version":"0"}}}"""

/**
 * 真起一次进程，看它能不能完成 MCP 握手。
 *
 * 这是 [mountsPiLensMcp] 判「已挂载」的最后一关。只查文件位会漏掉一整类故障：
 * 可执行文件在、依赖不在，进程 spawn 后立刻 `ERR_MODULE_NOT_FOUND` 退出，
 * 而 Codex 每次启动都在拉起这个必崩的进程。
 *
 * 收的是**完整启动命令**（`[可执行文件, ...args]`）而不是单个可执行文件：`npx -y
 * pi-lens-mcp` 这类形态里真正的标识在 args 上，只 spawn 那个 npx 是拿半条命令判活。
 *
 * 四条纪律，前三条与 [ShellBinaryProbe] 同源：
 *
 * - **stderr 必须 DISCARD。** 这里全程没人读 stderr，而 MCP server 普遍把启动日志
 *   往 stderr 打（pi-lens 更是刻意把 `console.log` 整条改道过去，以免污染 stdout 的
 *   JSON 流）。留独立管道而无人读取，写满约 64KB 缓冲后子进程会阻塞在写 stderr 上，
 *   永远不关 stdout，读取线程随之永久阻塞。也不能合并进 stdout——那会把启动横幅混进
 *   待判定的 JSON 流里。
 * - **写完请求立刻关 stdin。** EOF 是 MCP 的 shutdown 信号，规矩的 server 收到就自己
 *   退出，探测不留残留进程。
 * - **无论走哪条路径都 `destroyForcibly`。** MCP server 是长驻的，握手成功后并不退出；
 *   不理会 EOF 的 server 更是会一直挂着。设置页每刷新一次就漏一个 node 进程（pi-lens
 *   还会连带开一个 unix socket 与若干语言服务器）是不可接受的。
 * - **cwd 必须是一个空的临时目录，不能继承 IDE 的。** pi-lens 的 warm side-channel
 *   按 cwd 哈希取 socket 路径（`clients/mcp/ipc.js` 的 `ipcPathForCwd`），而它启动时
 *   **无条件 `unlinkSync` 掉那个路径上已有的 socket** 再自己 listen
 *   （`mcp/server.js` 的 `startIpcServer`）。继承 IDE 的 cwd 时，只要它与某个活跃
 *   工作区重合——从终端 `idea .` 或 `runIde` 起的 IDE 正是如此——探测就会挤掉在用的
 *   socket。更糟的是清理挂在 `process.on("exit")` 上，而 `destroyForcibly` 发的是
 *   SIGKILL，不触发 exit：探测留下一个死 socket 文件，原 server 还在监听那个已被
 *   unlink 的 inode，PostToolUse hook 按路径连过去只会拿到 ECONNREFUSED，热通道就此
 *   废掉。空临时目录的哈希撞不上任何真实工作区，这条路彻底断掉。
 *
 * 读取线程一旦认出成功响应就短路收工，不等进程退出，所以正常情况下这次探测只花
 * server 的启动时间（pi-lens 本机实测约 1.5s），[timeoutSeconds] 只在异常时兜底。
 */
internal fun spawnMcpHandshake(
    command: List<String>,
    timeoutSeconds: Long = HANDSHAKE_TIMEOUT_SECONDS,
): Boolean {
    if (command.isEmpty()) return false
    var process: Process? = null
    var probeDir: Path? = null
    return runCatching {
        val isolated = Files.createTempDirectory("imux-mcp-probe")
        probeDir = isolated
        val started =
            ProcessBuilder(command)
                .directory(isolated.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        process = started
        started.outputStream.bufferedWriter().use { writer ->
            writer.write(INITIALIZE_REQUEST)
            writer.newLine()
        }

        val succeeded = AtomicBoolean(false)
        val finished = CountDownLatch(1)
        Thread {
            runCatching {
                started.inputStream.bufferedReader().useLines { lines ->
                    // any 短路：认出成功响应就不再读，也不等 server 退出。
                    succeeded.set(lines.any(::handshakeSucceeded))
                }
            }
            finished.countDown()
        }.apply { isDaemon = true }.start()

        finished.await(timeoutSeconds, TimeUnit.SECONDS)
        succeeded.get()
    }.onFailure { LOG.warn("MCP 握手探测失败，按未挂载处理：${command.joinToString(" ")}", it) }
        .also {
            // 先杀进程再删目录：反过来的话 server 可能正往里写东西，删完又冒出来。
            process?.destroyForcibly()
            probeDir?.let { dir ->
                runCatching { dir.toFile().deleteRecursively() }
                    .onFailure { LOG.warn("清理握手探测临时目录失败：$dir", it) }
            }
        }.getOrDefault(false)
}

private const val HANDSHAKE_TIMEOUT_SECONDS = 15L
private val LOG = logger<LspDiagnostics>()
