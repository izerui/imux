package com.github.izerui.imux.lsp

import com.github.izerui.imux.terminal.resolveShell
import com.github.izerui.imux.terminal.singleQuote
import com.intellij.openapi.diagnostic.logger
import java.util.concurrent.TimeUnit

/** 查一批二进制在不在 PATH 里；值为绝对路径，不在则为 null。 */
internal interface BinaryProbe {
    fun locate(binaries: Set<String>): Map<String, String?>
}

/**
 * 拼出一次问完所有二进制的脚本。
 *
 * 每个二进制起一个登录 shell 是不可接受的：`zsh -l -i` 要读 profile 与 rc，
 * 单次开销可观，本表有近二十个二进制。一次调用、按行返回。
 *
 * 输出格式 `名称<TAB>路径`：`command -v` 找不到时输出空串，于是制表符后为空，
 * 与「找到了」在结构上仍然可区分——不能靠「有没有这一行」判断，因为登录 shell
 * 会往 stdout 混入 profile 的欢迎语。
 */
internal fun buildProbeScript(binaries: List<String>): String =
    binaries.joinToString("; ") { binary ->
        val quoted = singleQuote(binary)
        "printf '%s\\t%s\\n' $quoted \"\$(command -v $quoted 2>/dev/null)\""
    }

/** 解析 [buildProbeScript] 的输出。不含制表符的行是 shell 噪音，丢弃。 */
internal fun parseProbeOutput(output: String): Map<String, String?> =
    output.lineSequence()
        .mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab < 0) return@mapNotNull null
            val name = line.substring(0, tab).trim()
            if (name.isEmpty()) return@mapNotNull null
            name to line.substring(tab + 1).trim().takeIf(String::isNotEmpty)
        }
        .toMap()

/**
 * 经用户登录 shell 探测。
 *
 * **不能用 IDE 进程自己的 PATH。** 从 Dock/Finder 启动的 IDE 只有系统默认
 * PATH（`/usr/bin:/bin:/usr/sbin:/sbin`），而语言服务器普遍装在
 * `/opt/homebrew/bin`、`~/go/bin`、`~/.nvm/versions/node/&#42;/bin`。
 * 本机实测五个已装 server 分散在三个前缀下，没有一个落在系统默认 PATH 里。
 *
 * 从终端 `runIde` 起的沙箱继承了终端 PATH，所以这个 bug 只在正式 IDE 上暴露——
 * 与 [com.github.izerui.imux.terminal.launchCommand] 记录的是同一个坑，
 * 那次表现为「点开会话后标签页一片空白」。
 *
 * `-l` 读 profile 拿 PATH，`-i` 读 rc 拿 alias 与 nvm/rbenv 之类的 shim。
 */
internal class ShellBinaryProbe(
    private val shell: String = resolveShell(System.getenv("SHELL")),
    private val timeoutSeconds: Long = TIMEOUT_SECONDS,
) : BinaryProbe {

    override fun locate(binaries: Set<String>): Map<String, String?> {
        if (binaries.isEmpty()) return emptyMap()
        return runCatching {
            val process = ProcessBuilder(shell, "-l", "-i", "-c", buildProbeScript(binaries.toList()))
                .redirectErrorStream(false)
                .start()
            process.outputStream.close()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                // 超时不能退化成「全部未安装」——那会让 UI 谎报一堆缺口。
                // 返回空映射，上层据此标 UNKNOWN。
                return emptyMap()
            }
            parseProbeOutput(output)
        }.onFailure { LOG.warn("LSP 二进制探测失败，全部标记为无法确定", it) }
            .getOrDefault(emptyMap())
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        val LOG = logger<ShellBinaryProbe>()
    }
}
