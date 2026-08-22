package com.github.izerui.imux.lsp

import com.github.izerui.imux.model.AgentType
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * 编排三个探针，产出一次完整体检。
 *
 * [userHome] 参数化是为了测试能指向临时目录，与 `SessionRepository.forUserHome()`
 * 的做法一致。[binaryProbe] 同理注入，单测不碰真实 shell。
 *
 * 全程只读：只 `Files.readString`，不创建、不写入、不执行安装。
 */
internal class LspDiagnostics(
    private val userHome: Path,
    private val binaryProbe: BinaryProbe,
) {
    fun run(): LspReport {
        // 一次问完：语言服务器 + 它们的安装命令依赖的工具链（brew/go/npm/gem/dotnet/
        // rustup/opam）+ 三个 CLI 自身。登录 shell 要读 profile，那份开销每次都要付，
        // 没有理由为查几个名字再起第二个 shell。
        val located =
            binaryProbe.locate(
                LspCatalog.allProbeTargets + AgentType.entries.map { it.cli } + setOf(PI_LENS_MCP_BIN, NPX_BIN),
            )

        val claude =
            claudeReport(
                configuredCommands =
                    parseConfiguredCommands(
                        read(".claude/settings.json"),
                        read(".claude/plugins/marketplaces/claude-plugins-official/.claude-plugin/marketplace.json"),
                    ),
                binaries = located,
                cliInstalled = isInstalled(located, AgentType.CLAUDE),
            )

        val pi =
            piReport(
                piLensInstalled = hasPiLens(read(".pi/agent/settings.json")),
                binaries = located,
                cliInstalled = isInstalled(located, AgentType.PI),
            )

        val standardPiLensMcp = standardPiLensMcp(userHome)
        val locatedPiLensMcp =
            located[PI_LENS_MCP_BIN]
                ?.let { value -> runCatching { Path.of(value) }.getOrNull() }
                ?.takeIf(Path::isAbsolute)
        val standardPiLensMcpAvailable = Files.isExecutable(standardPiLensMcp)
        val piLensMcpExecutable =
            if (standardPiLensMcpAvailable) standardPiLensMcp else locatedPiLensMcp ?: standardPiLensMcp
        val codex =
            codexReport(
                mounted = mountsPiLensMcp(read(".codex/config.toml"), userHome, located),
                // 挂载后与 pi 是同一套 server，语言结果原样复用
                piFindings = pi.findings,
                cliInstalled = isInstalled(located, AgentType.CODEX),
                piLensMcpExecutable = piLensMcpExecutable,
                piLensMcpAvailable = standardPiLensMcpAvailable || locatedPiLensMcp != null,
            )

        return LspReport(listOf(claude, pi, codex))
    }

    /**
     * 只有**确认查过且没查到**才算未安装。
     *
     * 探测超时返回空映射，那时键根本不存在——此时报「未安装」会把整页变成三行
     * 假消息。当作已安装，逐语言自然落到 UNKNOWN，用户看到的是「无法确定」，
     * 这才是真话。
     */
    private fun isInstalled(
        located: Map<String, String?>,
        agentType: AgentType,
    ): Boolean = !located.containsKey(agentType.cli) || located[agentType.cli] != null

    /**
     * 读不到就是读不到——不存在、无权限、编码坏了，一律降级为「未配置」。
     *
     * 降级不代表可以静默：无权限或编码坏掉的配置在 UI 上与「压根没配过」长得一模一样，
     * 用户没有任何线索能区分。日志只写相对路径，**不写文件内容**——这几份 settings
     * 里有用户主目录路径乃至令牌。
     */
    private fun read(relative: String): String? =
        runCatching {
            val file = userHome.resolve(relative)
            if (Files.isRegularFile(file)) Files.readString(file) else null
        }.onFailure { LOG.warn("读取 $relative 失败，按未配置处理", it) }
            .getOrNull()

    private companion object {
        val LOG = logger<LspDiagnostics>()
    }
}
