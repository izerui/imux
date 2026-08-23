package com.github.izerui.imux.terminal

import com.intellij.openapi.application.PathManager
import java.nio.file.Path

/** 打包时放进插件目录的 codex 上报脚本，见 build.gradle.kts 的 prepareSandbox 配置。 */
private const val CODEX_SCRIPT_RELATIVE_PATH = "scripts/codex-imux-reporter.ps1"

private object CodexReporterScriptLocation

/**
 * 包成 TOML 基本字符串。
 *
 * Windows 路径全是反斜杠，而 TOML 基本字符串里 `\` 是转义引导符——不转义的话
 * `C:\Users\me` 里的 `\U` 会被当成 Unicode 转义，codex 解析配置直接报错，
 * 表现是会话起不来。
 *
 * **两个 `replace` 的顺序不能换。** 先转义反斜杠再转义引号：换了顺序，第二步给
 * 引号添的那个反斜杠会被第一步再转义一次，整条 command 在 TOML 里被截断。
 */
internal fun tomlBasicString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * codex 的 `-c hooks.SessionStart=…` 实参，已按 [dialect] 加好外层引号。
 *
 * **为什么用 `-c` 而不是写 `~/.codex/hooks.json`。** 用户的要求是「别改 cli 的配置
 * 文件本身」，而 `plugin.xml` 的描述里也写着「imux does not host or modify your
 * sessions」。`-c` 是命令行覆盖，与 pi 的 `-e &lt;扩展路径&gt;` 是同一个形状。
 *
 * **schema 是实证得出的**，不是推断——用 `codex debug models -c` 逐层塞错类型试出来：
 * `hooks.SessionStart` 要 `MatcherGroup` 序列（塞字符串报 `invalid type: string,
 * expected a sequence`）；`MatcherGroup.hooks` 要 `HookHandlerConfig` 序列（内部标记
 * 枚举，标记字段是 `type`）；`type="command"` 时 `command` 必填且必须是字符串
 * （少了报 `missing field &#96;command&#96;`）。与 Claude Code 的 hook 结构同族。
 *
 * **已知代价：codex 0.148 对新出现的 hook 有一次交互式信任复核。** 首次带着这个
 * 覆盖启动时，codex 会先显示「Hooks need review / Trust all and continue /
 * Continue without trusting」，用户选择信任后由 **codex 自己**把
 * `hooks.state."/&lt;session-flags&gt;/config.toml:session_start:0:0"` 的 sha256
 * 写进 `~/.codex/config.toml`。imux 一个字节都不写。codex 另有
 * `--dangerously-bypass-hook-trust` 可以跳过这道门，**刻意不用**：它会连带放行
 * 用户配置里所有未经复核的 hook（项目级 hooks.json、插件、市场来源），
 * 等于替用户拆掉一道安全闸。
 *
 * **`-ExecutionPolicy Bypass` 不是可选的。** `-File` 是运行脚本文件，受执行策略管辖；
 * 所有作用域都是 `Undefined` 时，Windows **客户端**的有效策略是 `Restricted`
 * ——「Prevents running of all script files」。而这里跑的是 `powershell`，
 * 即随 Windows 附带的 Windows PowerShell 5.1（`pwsh` 7 才把默认改成 `RemoteSigned`，
 * 这条命令拿不到）。少了它有两重后果，都很重：干净的 Windows 上整个功能静默空转，
 * 正是本任务立项要消灭的那个症状；而且 PowerShell 在**读到脚本之前**就非 0 退出，
 * 脚本里的 `try / catch { exit 0 }` 一行都没机会跑，codex 于是每开一次会话就红一次字。
 *
 * 命令行上的 `-ExecutionPolicy` 是 **Process 作用域**——只活在
 * `$Env:PSExecutionPolicyPreference` 里，进程一结束就没了，**不写任何配置文件**，
 * 完全在「别改 cli 的配置文件本身」这条约束内。附带好处是 `Bypass` 不做 Zone 检查，
 * 顺手免疫解压出来的 `.ps1` 万一带上 MOTW。
 *
 * **边界**：它盖不过组策略（`MachinePolicy` / `UserPolicy` 优先级高于 Process）。
 * 企业环境里被 GPO 锁死执行策略的机器上，这条 hook 仍然跑不起来——那属于
 * 「认不出就跳过」，代价是标签不自动跟随，可接受。
 *
 * 只在 Windows 上注入：macOS 与 Linux 走 `lsof` / `/proc` 读文件句柄，那条路
 * 正在工作，不该为统一而动它。
 */
internal fun codexHookOverrideArg(
    dialect: ShellDialect,
    scriptPath: String,
): String {
    // 内层这对引号是**给 shell 看的**，不是 TOML 引号——codex 把 command 的值整条
    // 交给 shell 执行，路径里有空格时全靠它。因此这里用**原始路径**，不预先转义。
    val command = "powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File \"$scriptPath\""
    // TOML 转义只施加一次，作用于整条 command。反斜杠在这一步翻倍、内层引号在这一步
    // 变成 \"——这正是 CodexHookOverrideTest 里钉住的形状。写成
    // tomlBasicString(scriptPath) 会让路径被转义两次，而 codex **不会报错**：
    // 双重转义在 TOML 语法上完全合法，只是解析出来的路径不存在，hook 静默地永不触发。
    val toml = "hooks.SessionStart=[{hooks=[{type=\"command\",command=${tomlBasicString(command)}}]}]"
    return quote(dialect, toml)
}

/** 插件目录下的 codex 上报脚本；目录未知或文件不存在时返回 null。 */
internal fun codexReporterScriptIn(pluginPath: Path?): Path? = pluginScriptIn(pluginPath, CODEX_SCRIPT_RELATIVE_PATH)

/**
 * 上报脚本的绝对路径；找不到时返回 null。
 *
 * 返回 null 时调用方必须**不加** `-c` 参数：把一个不存在的路径拼进命令行，
 * hook 每次会话启动都会失败，而少了上报只是标签不自动跟随——理由与 pi 的 `-e`
 * 完全相同，见 [locatePiReporterScript]。
 */
internal fun locateCodexReporterScript(
    pluginPath: Path?,
    classPathEntry: Path?,
): Path? =
    codexReporterScriptIn(pluginPath)
        ?: pluginScriptNear(classPathEntry, CODEX_SCRIPT_RELATIVE_PATH)

/** 生产入口；定位规则与 [piReporterScript] 完全一致，只是换了脚本名。 */
internal fun codexReporterScript(): Path? {
    val pluginPath =
        runCatching {
            PathManager.getPluginsDir().resolve(PLUGIN_DIRECTORY_NAME)
        }.getOrNull()

    val classPathEntry =
        runCatching {
            val location =
                CodexReporterScriptLocation::class.java.protectionDomain.codeSource
                    ?.location
            location?.toURI()?.let(Path::of)
        }.getOrNull()
    return locateCodexReporterScript(pluginPath, classPathEntry)
}
