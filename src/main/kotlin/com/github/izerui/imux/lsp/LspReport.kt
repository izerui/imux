package com.github.izerui.imux.lsp

import com.github.izerui.imux.model.AgentType

/**
 * 一门语言在某个 CLI 上的 LSP 可用状态。
 *
 * 「配置缺口」与「二进制缺口」必须分开：前者一行命令就能补（本机实测的真实例子是
 * kotlin-lsp 二进制已装、Claude Code 却没启用对应插件），后者要下载安装。
 * 混成一个「不可用」会让用户对着一个 1.3GB 的下载命令发呆，而其实只差一行配置。
 */
internal enum class LspStatus {
    /** 配置到位且 server 二进制在 PATH 里。 */
    READY,

    /** 二进制可能在，但该 CLI 没配上。 */
    MISSING_CONFIG,

    /** 配置到位，但 server 不在 PATH。 */
    MISSING_BINARY,

    /** 探测超时或失败，信息不足以判断——不猜。 */
    UNKNOWN,

    /**
     * pi-lens 会按需自动安装该语言的 server，用户无需干预。
     *
     * 刻意**不**退化成查 PATH：pi-lens 是懒安装的，用到才装，装到哪也不归 imux 管
     * （本机 `~/.pi/agent/npm/node_modules/.bin/` 里只有 pi-lens 自己的工具）。
     * 对非 gated 语言，PATH 里有就直接用、没有就按需装，两种情况最终都可用——
     * 查 PATH 的结果与真相无关，标成「未安装」比原先的「不显示」更糟。
     */
    AUTO_MANAGED,

    /** 该 CLI 生态里没有这门语言的接入方式（Claude Code 官方无对应 LSP 插件）。 */
    NOT_AVAILABLE,
}

/**
 * 一条修复建议：**按顺序跑完就能用**的命令链，外加没法跑时的退路。
 *
 * 从前这里有一个 `RemedyKind`（ACTIVATE / INSTALL），按钮上的词跟着它变成「激活」或
 * 「安装」。那个区分是**按实现分的**——配置层（装 CLI 插件）与二进制层（装 language
 * server）——而不是按用户心智分的。用户原话：「虽然说我不知道你这两个是啥意思吧，
 * 你能让用户怎么方便怎么来就行了」。现在一行只有一个按钮、一个词「启用」，点下去把缺的
 * 每一层按顺序跑完，`kind` 于是没有任何消费者，整个删掉。
 *
 * 平台闸门也不再看性质，改看命令本身在不在 [LspCatalog.macOnlyCommands] 里
 *（见 `canRun`）——一条链里可以同时含 `brew` 与跨平台的 `claude plugin install`，
 * 按性质根本分不出来。
 *
 * [commands] 用 `&&` 串起来交给终端：**哪一步失败就停在哪，用户在终端里看得见**。
 *
 * [blockingTool] 是「链根本组不出来」的那种情况：某条命令依赖的工具不在 PATH，而我们
 * 又没有可靠的安装方式（`brew` 本身、`go`、`npm`、`gem`）。此时 [commands] 必为空，
 * UI 那一格显示「需要先安装 &lt;工具&gt;」+ 该工具的官网链接（[docsUrl] 换成工具的）。
 * 编一条 `brew install brew` 出来，坏的是用户的开发环境。
 */
internal data class Remedy(
    val commands: List<String>,
    val docsUrl: String?,
    val blockingTool: String? = null,
) {
    /**
     * 交给终端的那一整行，`null` 表示没有可跑的东西。
     *
     * 拼接住在这里而不是设置页里，理由与 `runTabName` 相同：壳里留一个
     * `commands.joinToString(" && ")` 的话，把分隔符改成 `;`（哪一步失败都继续往下跑）
     * 或者改成 `" "`（拼成一条谁也不认识的命令）都只是改一个字面量，而设置页那一侧
     * 只能做源码文本断言。搬到这里之后它能被真正调用着测。
     */
    val chain: String? get() = commands.joinToString(" && ").takeIf(String::isNotEmpty)
}

internal data class LanguageFinding(
    val language: LspLanguage,
    val status: LspStatus,
    val remedy: Remedy?,
)

/**
 * 单个 CLI 的体检结果。
 *
 * [installed] 为 false 时 [findings] 必为空：用户只装了 pi 却收到一堆 Claude Code
 * 的缺口提示是纯噪音，README 已明确「装一个也能用」。
 *
 * [groupRemedy] 是整组级别的前置修复（pi 未装 pi-lens、Codex 未挂 MCP），
 * 它存在时逐语言的缺口没有意义——先把前置补上。
 */
internal data class CliReport(
    val agentType: AgentType,
    val installed: Boolean,
    val findings: List<LanguageFinding>,
    val groupRemedy: Remedy? = null,
) {
    val ready: List<LanguageFinding> get() = findings.filter { it.status == LspStatus.READY }

    /**
     * 「缺口」= 用户**真能采取行动**的那两种状态，而不是「一切非 READY」。
     *
     * [LspStatus.AUTO_MANAGED] 与 [LspStatus.NOT_AVAILABLE] 都不是缺口：前者是好消息，
     * 后者用户做什么都改变不了。[LspStatus.UNKNOWN] 也不算——我们并不知道它缺不缺，
     * 没有可执行的建议给用户，把它计进「待补充 N」只会制造焦虑。
     *
     * 这两个派生属性现在只用于计数与图标选择，逐行渲染走完整的 [findings]。
     */
    val gaps: List<LanguageFinding> get() = findings.filter {
        it.status == LspStatus.MISSING_CONFIG || it.status == LspStatus.MISSING_BINARY
    }
}

internal data class LspReport(val cliReports: List<CliReport>)
