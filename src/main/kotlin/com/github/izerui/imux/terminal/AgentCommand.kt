package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType

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
internal fun launchCommand(shell: String, agentType: AgentType, resumeId: String?): List<String> {
    val cli = agentType.cli
    val script = when {
        resumeId == null -> cli
        agentType == AgentType.CODEX -> "$cli resume ${singleQuote(resumeId)}"
        else -> "$cli --resume ${singleQuote(resumeId)}"
    }
    return listOf(shell, "-l", "-i", "-c", script)
}

/**
 * 传给 CLI 进程的终端环境。
 *
 * Claude 默认隐藏真实终端光标并自行绘制一个反色光标。IDEA 262 reworked terminal 的
 * cursor tracker 在光标隐藏期间不会发布位置变化，导致 output model 的 cursorOffset
 * 停在 Claude 启动时的 grid home，IME 候选窗也跟着定位到旧输出处。
 *
 * Claude 自带的 native cursor 模式保留完整 TUI，但会持续维护可被终端追踪的真实光标。
 * Codex 本来就显式维护真实光标，不需要这个 Claude 专用变量。
 */
internal fun launchEnvironment(agentType: AgentType): Map<String, String> =
    if (agentType == AgentType.CLAUDE) {
        mapOf("CLAUDE_CODE_NATIVE_CURSOR" to "1")
    } else {
        emptyMap()
    }

/** 用户的登录 shell；取不到时退回 zsh（macOS 自 Catalina 起的默认）。 */
internal fun resolveShell(shellEnv: String?): String =
    shellEnv?.takeIf { it.isNotBlank() } ?: "/bin/zsh"

/**
 * 包成单引号字符串。
 *
 * 会话 id 来自文件名，不该假定它的内容安全——凡是拼进 shell 命令行的东西一律当作不可信。
 * 单引号内除单引号本身外一切都是字面量，所以只需把 `'` 换成 `'\''`（闭合、转义、重开）。
 */
private fun singleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
