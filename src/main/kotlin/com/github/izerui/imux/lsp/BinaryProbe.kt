package com.github.izerui.imux.lsp

import com.github.izerui.imux.terminal.dialectOf
import com.github.izerui.imux.terminal.probeScript
import com.github.izerui.imux.terminal.resolveShell
import com.github.izerui.imux.terminal.shellArgs
import com.github.izerui.imux.terminal.singleQuote
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import org.jetbrains.plugins.terminal.TerminalOptionsProvider
import java.util.concurrent.TimeUnit

/** 二进制探测结果；键不存在与确认不存在必须保持可区分。 */
internal enum class BinaryAvailability {
    PRESENT,
    MISSING,
    UNKNOWN,
}

internal fun binaryAvailability(
    binaries: Map<String, String?>,
    name: String,
): BinaryAvailability =
    when {
        !binaries.containsKey(name) -> BinaryAvailability.UNKNOWN
        binaries[name] == null -> BinaryAvailability.MISSING
        else -> BinaryAvailability.PRESENT
    }

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
    output
        .lineSequence()
        .mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab < 0) return@mapNotNull null
            val name = line.substring(0, tab).trim()
            if (name.isEmpty()) return@mapNotNull null
            name to line.substring(tab + 1).trim().takeIf(String::isNotEmpty)
        }.toMap()

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
 *
 * **stderr 必须 DISCARD，不能留独立管道、也不能合并进 stdout。**
 *
 * 留独立管道会死锁：这里全程没人读 stderr，而 nvm / rbenv / conda init 之类的 rc
 * 往 stderr 写超过管道缓冲（约 64KB）时，子进程会阻塞在写 stderr 上，于是永远不关
 * stdout，`readText()` 永久阻塞，下面那行 `waitFor(timeout)` **根本执行不到**——
 * 超时机制形同虚设。这个坑格外隐蔽：用户自己的终端把 stderr 直接画到屏幕上、不经
 * 管道，所以**用户终端一切正常，只有 imux 挂死**。挂死还会连锁：invokeLater 回调
 * 永不执行 → 页面永久停在「正在检测…」→ 重新检测按钮永久禁用（重新启用就写在那个
 * 回调里）→ 用户重开设置页触发 `createPanel().also { refresh() }` → 再泄漏一个登录
 * shell，可无限累积。
 *
 * 合并进 stdout（`redirectErrorStream(true)`）确实也能消除死锁，但会把 profile 的
 * 噪音混进探测结果：[parseProbeOutput] 靠制表符筛行，任何恰好含制表符的告警都会被
 * 当成一条「名称→路径」，反而更糟。stderr 本来就无人读取，DISCARD 不丢任何信息。
 *
 * **PowerShell 那一侧同样要 DISCARD，只是坏法换了个名字。** 那边跑的不是登录 shell
 *（[shellArgs] 给的是 `-NoProfile`），profile 噪音这条理由不成立；但脚本本身会往
 * stderr 写——`Get-Command` 查不到时即便带了 `-ErrorAction SilentlyContinue`，
 * 上游模块自动加载失败之类的告警仍可能落到 error stream。18 个二进制逐个查，
 * 无人读取的 stderr 一旦写满管道缓冲，死锁与 `waitFor` 形同虚设这两条后果
 * **与 POSIX 侧一模一样**。而合并进 stdout 在这边更糟：PowerShell 的 ErrorRecord
 * 多行输出里带制表符缩进，正好被 [parseProbeOutput] 当成「名称→路径」。
 *
 * 命令行的三段（shell、参数、脚本）现在**全部按方言取**（[dialectOf] &#8594;
 * [shellArgs] / [probeScript]），一段都不能写死：给 PowerShell 发 `-l -i -c`
 * 是当场报错，给它发 POSIX 的 `printf` 脚本则是 18 门语言全落 UNKNOWN 而不报错。
 */
internal class ShellBinaryProbe(
    private val shell: String =
        resolveShell(
            System.getenv("SHELL"),
            isWindows = SystemInfo.isWindows,
            configuredShell = service<TerminalOptionsProvider>().shellPath,
        ),
    private val timeoutSeconds: Long = TIMEOUT_SECONDS,
) : BinaryProbe {
    override fun locate(binaries: Set<String>): Map<String, String?> {
        if (binaries.isEmpty()) return emptyMap()
        return runCatching {
            val dialect = dialectOf(shell)
            val process =
                ProcessBuilder(
                    listOf(shell) + shellArgs(dialect) + probeScript(dialect, binaries.toList()),
                ).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            process.outputStream.close()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                // 超时不能退化成「全部未安装」——那会让 UI 谎报一堆缺口。
                // 返回空映射，上层据此标 UNKNOWN。
                //
                // 这是本页最可能发生的失败，必须留痕：UI 上它只表现为一屏「无法确定」，
                // 而 settings.lsp.failed 的译文正让用户「详情见 IDE 日志」。
                LOG.warn("LSP 二进制探测超时（${timeoutSeconds}s），全部标记为无法确定")
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
