package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.IMUX_TAB_ENV
import com.github.izerui.imux.session.PiReportEndpoint

/**
 * 启动 CLI 的命令行。
 *
 * **必须经用户的 shell 启动，不能直接 exec `claude`。** 从 Dock/Finder 启动的 IDE
 * 只有系统默认 PATH（`/usr/bin:/bin:/usr/sbin:/sbin`），而 CLI 通常装在
 * `/opt/homebrew/bin`、`~/.local/bin` 这类地方，直接 exec 一律找不到——
 * 表现就是点开会话后标签页一片空白。
 * （从终端 `runIde` 起的沙箱继承了终端的 PATH，所以在沙箱里一切正常，
 * 只有装到正式 IDE 上才暴露。这个差异坑过一次。）
 *
 * 两个参数缺一不可：
 * - `-l` 读 profile，拿到用户配好的 PATH
 * - `-i` 读 rc，`claude` 这类 **alias** 才存在（实测本机的 claude 就是个 alias，
 *   还带着 `--dangerously-skip-permissions` 参数，丢了它行为就变了）
 *
 * 不用 `exec` 替换进程：多一层 shell 无妨，而 `-c` 执行完即退出，
 * CLI 一结束 shell 也跟着结束，「进程终止即关标签页」的行为不受影响。
 */
internal fun launchCommand(
    shell: String,
    agentType: AgentType,
    resumeId: String?,
    piExtension: java.nio.file.Path? = null,
    initialPrompt: String? = null,
): List<String> {
    val cli = agentType.cli
    val script =
        when {
            resumeId == null -> {
                cli
            }

            // pi 的 --session-id 对已存在的 id 是打开、不存在则以该 id 创建，
            // 所以新建与续聊是同一条命令——新建时 id 由 imux 预先生成传进来。
            agentType == AgentType.PI -> {
                buildString {
                    append("$cli --session-id ${singleQuote(resumeId)}")
                    // 脚本缺失时**不加** -e：拼出一个加载不了的扩展会让 pi 启动失败，
                    // 那是整个会话起不来；而少了上报只是标签页不自动跟随。
                    piExtension?.let { append(" -e ${singleQuote(it.toString())}") }
                }
            }

            agentType == AgentType.CODEX -> {
                "$cli resume ${singleQuote(resumeId)}"
            }

            else -> {
                "$cli --resume ${singleQuote(resumeId)}"
            }
        }
    val command =
        initialPrompt
            ?.takeIf { it.isNotBlank() }
            ?.let { "$script ${singleQuote(it)}" }
            ?: script
    return listOf(shell, "-l", "-i", "-c", command)
}

/**
 * 传给 CLI 进程的终端环境。
 *
 * [IMUX_TAB_ENV] 每种 agent 都要带：它是把一个 CLI 进程认回对应终端的唯一依据。
 * 会话 id 不是终端的固有属性——用户敲 `/clear` 或 `/new`，CLI 换一个会话 id 而进程
 * 不变，插件得靠这个标记发现这件事。用我们自己发的 tabId 而不用 pid，是因为命令是
 * `shell -l -i -c "cli"`，CLI 是 shell 的子进程，而 shell 是否 exec 掉自己
 * 因 shell 与平台而异。详见 [com.github.izerui.imux.session.LiveSessionProbe]。
 *
 * `CLAUDE_CODE_NATIVE_CURSOR` 只给 claude：它默认隐藏真实终端光标并自行绘制一个反色
 * 光标，而 IDEA 262 reworked terminal 的 cursor tracker 在光标隐藏期间不会发布位置
 * 变化，导致 output model 的 cursorOffset 停在 Claude 启动时的 grid home，
 * IME 候选窗也跟着定位到旧输出处。native cursor 模式保留完整 TUI，但会持续维护
 * 可被终端追踪的真实光标。Codex 本来就显式维护真实光标，不需要这个变量。
 *
 * pi 注入 `PI_HARDWARE_CURSOR=1`，让 IDEA 262 能用可见 terminal cursor 定位 macOS
 * 输入法候选窗。pi 同时画出的反色假光标由 imux 自带扩展通过官方 editor factory
 * 去掉（见 `pi-imux-reporter.js`）：保留 CURSOR_MARKER 与硬件光标，只去掉紧随 marker
 * 的反色渲染。这样候选窗能跟随，中文双宽字符间移动时也只有一套可见光标。
 */
internal fun launchEnvironment(
    agentType: AgentType,
    tabId: String,
    piReport: PiReportEndpoint? = null,
): Map<String, String> =
    buildMap {
        put(IMUX_TAB_ENV, tabId)
        when (agentType) {
            AgentType.CLAUDE -> {
                put("CLAUDE_CODE_NATIVE_CURSOR", "1")
            }

            AgentType.PI -> {
                put("PI_HARDWARE_CURSOR", "1")
                // 令牌只发给 pi：它是上报接口唯一的门禁，多发一个进程就多一份泄漏面
                piReport?.let {
                    put("IMUX_REPORT_URL", it.url)
                    put("IMUX_TOKEN", it.token)
                }
            }

            AgentType.CODEX -> {
                Unit
            }
        }
    }

/**
 * 新建会话时预先定下的会话 id，没有则返回 null。
 *
 * 只有 pi 有：它的 `--session-id` 接受任意 id，不存在就以该 id 创建，于是标签页与会话
 * 从启动那一刻起就是确定的，不必等 CLI 落盘后再靠时间窗去猜是哪一个
 * （见 [com.github.izerui.imux.session.SessionListModel.registerPending]）。
 *
 * claude 与 codex 没有对应能力，id 只能由 CLI 自己生成、插件事后认领。
 */
internal fun preassignedSessionId(
    agentType: AgentType,
    newId: () -> String = {
        java.util.UUID
            .randomUUID()
            .toString()
    },
): String? = if (agentType == AgentType.PI) newId() else null

/** 用户的登录 shell；取不到时退回 zsh（macOS 自 Catalina 起的默认）。 */
internal fun resolveShell(shellEnv: String?): String = shellEnv?.takeIf { it.isNotBlank() } ?: "/bin/zsh"

/**
 * 包成单引号字符串。
 *
 * 会话 id 来自文件名，不该假定它的内容安全——凡是拼进 shell 命令行的东西一律当作不可信。
 * 单引号内除单引号本身外一切都是字面量，所以只需把 `'` 换成 `'\''`（闭合、转义、重开）。
 */
internal fun singleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
