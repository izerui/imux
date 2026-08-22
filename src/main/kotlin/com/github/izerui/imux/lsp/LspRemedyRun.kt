package com.github.izerui.imux.lsp

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.terminal.dialectOf
import com.github.izerui.imux.terminal.shellArgs

// 「一条修复建议能不能点一下就跑、跑起来长什么样」的纯逻辑。
//
// 与 LspStatusPresentation 同样的理由单独成文件、且**一行平台 API 都不碰**：
// 设置页只能做源码文本断言，而文本断言总能被「保留被钉住的字面量、在别处改语义」绕开。
// 搬到这里之后，这几个函数能被普通 JUnit 4 **真正调用**——把 canRun 改成恒 true，
// LspRemedyRunTest 当场变红，而设置页那一侧只剩一个调用点，壳里不许再有平台判断。
//
// isMac 作为**参数注入**而不是在这里读 SystemInfo，正是为了这一点：
// SystemInfo 是平台类，一旦在纯函数里读，这个函数就只能在开发者自己的机器上被测到
// 一半——而这里最要命的分支恰恰是「不在 macOS 上会怎样」。
//
// toolAvailability 同理：enableCommands 的正确性全在「哪一层已经在了、哪一层还缺」上，
// 一旦在函数里直接读探测结果，能被测到的就只有开发者这台机器当下的那一种组合。
// 注入之后三层的每一种在/缺组合都能被真调用断言。
//
// 用行注释而不是 KDoc：这段说的是文件用途，不挂在任何声明上。

/**
 * 执行按钮上那个词的 bundle 键——**只剩一个**。
 *
 * 从前这里是 `runActionKey(kind)`，按修复性质映到「激活」或「安装」两个词。用户原话：
 * 「虽然说我不知道你这两个是啥意思吧，你能让用户怎么方便怎么来就行了」。那个区分是按
 * **实现**分的（配置层 vs 二进制层），不是按用户心智分的——用户要的是「一行一个按钮，
 * 点了就能用」，而不是在两个词之间猜自己缺的是哪一层。
 *
 * 做成常量而不是在设置页里写字面量，是为了让「壳里不得自己拼这个键」继续可查：
 * 设置页那一侧只能做源码文本断言，而 `ImuxLspUiSourceTest` 的 import 检查会盯着这个
 * 名字——删掉 import 再在本文件补一个同名声明会被当场抓住。
 */
internal const val ENABLE_ACTION_KEY = "settings.lsp.action.enable"

/**
 * 命令**正在跑**的时候，那一行状态列显示的 bundle 键——同样**只剩一个**。
 *
 * 这一列平时说的是「未启用插件」「服务器不在 PATH 中」。点下按钮之后如果它一动不动，
 * 用户看到的就是「我点了，什么也没发生」——用户原话是「激活后，就状态应该变了啊」。
 * 所以点击那一刻这一列必须当场换词，等命令跑完再由重新探测覆盖回真实状态。
 *
 * 从前分成「正在激活…」与「正在安装…」两条，理由是「前者一两秒、后者几百兆下载」。
 * 那个理由在链式命令下**不再成立**：同一条链里既有 `claude plugin install`（秒级）
 * 也可能有 `brew install --cask dotnet-sdk`（几百兆），说成哪一种都是假话。
 * 真正的知情途径是按钮 tooltip 里那条完整命令链——用户点之前就看得到会跑什么。
 */
internal const val ENABLING_STATUS_KEY = "settings.lsp.status.enabling"

/**
 * 这条修复建议该不该配一个执行按钮。
 *
 * 三个条件缺一不可，每一条挡的都是一种「按钮在，点下去却不对」：
 *
 * 1. **有 POSIX shell**。命令是经 [runCommandLine] 交给 `shell -l -i -c` 跑的，
 *    而 shell 取自 `resolveShell(System.getenv("SHELL"))`——Windows 上取不到 `SHELL`，
 *    退回 `/bin/zsh`，标签页当场报 `Cannot run program /bin/zsh`。
 *    详见下面「为什么这一条要单独存在」。
 * 2. **有命令可跑**。链为空的两种情况都在这里被挡下：没有已知安装命令的
 *    （`kotlin-language-server`、`sourcekit-lsp` 等目录表里 installCommand 为 null 的
 *    那几门），以及卡在一个我们装不上的前置工具上的（[Remedy.blockingTool] 非空时
 *    [Remedy.commands] 必为空）。给一个点了必然失败的按钮是空头承诺。
 * 3. **整条链在本平台上验证过**。判据是[LspCatalog.macOnlyCommands]——目录表里的安装
 *    命令与前置工具的安装命令都只在 macOS 上核实过（`brew install llvm`、
 *    `gem install ruby-lsp`、`opam install ocaml-lsp-server`、`brew install --cask
 *    dotnet-sdk` 全是 macOS 形状）。而 `claude plugin install` / `pi install` /
 *    `codex mcp add` 是 CLI 自己的子命令，不依赖任何外部工具链，不在那张表里，
 *    因此不受这一条限制（但仍受第 1 条限制）。
 *
 * **为什么是「链里含任何一条 macOS-only 就整条按 macOS-only 处理」。** 链是用 `&&`
 * 串起来交给终端的：第一条跑不通，后面一条都跑不到。「前半段能跑」这种中间态不存在，
 * 而一个跑到一半就红着停下的终端标签，比一开始就没有按钮更让人以为是插件坏了。
 * 从前这道闸门按 `RemedyKind` 划，链式命令之后那个划法直接失效——同一条链里可以
 * 既有 `brew install --cask dotnet-sdk` 又有跨平台的 `claude plugin install`。
 *
 * 第 3 条是这次改动**唯一**真正危险的地方。这些命令从前只是显示出来给人复制，
 * 平台不对用户自己一眼就看出来了；现在按钮点下去是直接执行。
 *
 * **为什么 [hasPosixShell] 要单独占一条，而不是并进 [isMac]。** 别处（会话启动）在
 * Windows 上本来就没能用过，多一个坏入口用户什么也没失去；而这一页在 Windows 上
 * **本来是能用的**——每门语言一句状态，缺口那几行给出命令的短目标名、完整命令挂
 * tooltip，有上游文档时还多一个链接。这一条不是自然成立的，它由
 * `ImuxLspConfigurable.fallbackCell` 兜着：闸门挡下按钮时那一格必须仍有内容。
 * 旁边多一个更显眼的「启用」按钮、点下去报 `Cannot run program /bin/zsh`，
 * 用户会合理推断整页坏了：那是拿一个能用的东西换了一个不能用的。
 *
 * 三个维度分开而不是揉成一个布尔，是因为它们会**各自**变化：目录表补齐平台分版之后，
 * 第 3 条里的 `isMac ||` 该放开；`resolveShell` 支持 Windows 之后，第 1 条该去掉。
 * 揉在一起的话，将来放开其中一个必然会顺手把另一个也放了。
 */
internal fun canRun(
    remedy: Remedy,
    isMac: Boolean,
    hasPosixShell: Boolean,
): Boolean =
    hasPosixShell &&
        remedy.commands.isNotEmpty() &&
        (isMac || remedy.commands.none { it in LspCatalog.macOnlyCommands })

/**
 * 一条命令**自己**依赖的工具：命令的第一个 token。
 *
 * 不另维护一张「哪条 server 命令依赖哪个工具」的表，是因为依赖关系已经写在命令文本里
 * 了（`dotnet tool install --global csharp-ls` 的第一个词就是 `dotnet`），抄一遍就是
 * 两处会漂移的真相。反过来由 `LspCatalogTest` 钉住「每条命令推导出来的工具都在
 * [LspCatalog.tools] 里」——推导规则与数据表两头对齐，比一张手写映射更难写错。
 */
internal fun requiredTool(command: String): String = command.trim().substringBefore(' ')

/**
 * 让这一行**从当前状态走到可用**所需的命令，按执行顺序排列。
 *
 * 这是「一行一个按钮、点了就能用」的全部逻辑。返回空列表表示不给按钮——要么本来就没
 * 事可做（就绪 / pi-lens 自动提供 / 官方无对应插件 / 没查出来），要么我们组不出一条
 * 能跑通的链（见 [enableBlocker]）。
 *
 * 三层，**缺哪层加哪层**，顺序固定：
 *
 * | 层 | 何时加入 | 例子 |
 * | --- | --- | --- |
 * | 前置工具链 | 该安装命令依赖的工具不在 PATH | `brew install --cask dotnet-sdk` |
 * | 语言服务器 | server 二进制不在 PATH | `dotnet tool install --global csharp-ls` |
 * | CLI 插件 | 状态是 [LspStatus.MISSING_CONFIG] | `claude plugin install csharp-lsp@…` |
 *
 * 顺序不是审美：后一层依赖前一层跑成。链用 `&&` 串给终端，**哪一步失败就停在哪**，
 * 用户在终端里看得见是哪一条红的。
 *
 * **为什么 [LspStatus.MISSING_CONFIG] 也要查二进制。** 探针算状态时配置层排在二进制
 * 层前面（`binary !in configuredCommands` 一命中就返回 MISSING_CONFIG），于是这个状态
 * **压根没说过二进制在不在**。只装插件不装 server，用户点完按钮得到的是一个配好了、
 * 却指向一个不存在的程序的 LSP——那正是「点了没用」的另一种形状。所以这里对两种缺口
 * 都问一次 [toolAvailability]，让它保留“在 / 缺失 / 未知”三种答案。
 *
 * **[toolAvailability] 必须是注入的参数**，不在这里读探测结果或 `SystemInfo`：那样这个
 * 函数就只能在开发者自己那台机器上被测到一条路径，而这里最要紧的分支恰恰是
 * 「某一层已经在了会怎样」。注入之后每一层的加入/略过都能被真调用断言。
 */
internal fun enableCommands(
    language: LspLanguage,
    agentType: AgentType,
    status: LspStatus,
    toolAvailability: (String) -> BinaryAvailability,
): List<String> = enablePlan(language, agentType, status, toolAvailability).commands

/**
 * 卡住整条链的那个前置工具——它不在 PATH，而我们没有可靠的安装方式。
 *
 * `brew` 是最典型的一个：不能用 brew 装 brew。`go` / `npm` / `gem` 同理，它们的安装
 * 牵扯到版本管理器与系统包管理器，编一条命令跑下去坏的是用户的开发环境。
 *
 * 此时 [enableCommands] 返回空（不给按钮），UI 改显示「需要先安装 &lt;工具&gt;」加上
 * 该工具的官网链接。这比给一个点下去必然 `command not found` 的按钮诚实，也比什么都
 * 不说有用——用户缺的正是「到底该先装什么」这一句。
 */
internal fun enableBlocker(
    language: LspLanguage,
    agentType: AgentType,
    status: LspStatus,
    toolAvailability: (String) -> BinaryAvailability,
): LspTool? = enablePlan(language, agentType, status, toolAvailability).blocker

/**
 * 这一行在界面上该有的那条修复建议，没有可说的就返回 null。
 *
 * 三个探针共用它，而不是各写一份：从前 `ClaudeCodeLspProbe.remedyFor` 与 `piReport`
 * 里各有一段「取目录表 &#8594; 包成 Remedy」，两处漂移一次，就会出现同一门语言在两个
 * 分组里给出不同下一步。这里统一之后，探针只负责算状态。
 *
 * [docsUrl] 无条件带上（有的话）：从前 ACTIVATE 类修复的 docsUrl 恒为 null，于是闸门
 * 挡下按钮时 Claude Code 组 13 门语言那一格只剩一个短标签。现在配置缺口也带 server
 * 的上游文档——用户想自己动手时那是最有用的一个链接。
 */
internal fun remedyFor(
    language: LspLanguage,
    agentType: AgentType,
    status: LspStatus,
    toolAvailability: (String) -> BinaryAvailability,
): Remedy? {
    // 与 enablePlan 共用同一把尺子。这一句不能省：下面无条件取的 docsUrl 会让**每一行**
    // 都拿到一个非空 Remedy，于是就绪、pi-lens 自己提供、官方无对应插件的行末统统多出
    // 一个「文档 ↗」——用户对这些行什么都不用做，摆个链接只会让人以为有事要办。
    if (!isActionableGap(status)) {
        return null
    }
    val plan = enablePlan(language, agentType, status, toolAvailability)
    plan.blocker?.let { tool ->
        // 链组不出来时，唯一有用的链接是**那个工具**的官网，不是语言服务器的文档：
        // 用户下一步要做的事是装 brew / node / ruby，不是读 pyright 的 README。
        return Remedy(emptyList(), tool.docsUrl, tool.name)
    }
    val docsUrl = serverBinaryFor(language, agentType)?.let { LspCatalog.server(it)?.docsUrl }
    if (plan.commands.isEmpty() && docsUrl == null) {
        return null
    }
    return Remedy(plan.commands, docsUrl)
}

/**
 * 「用户**真能采取行动**」的那两种状态，与 [CliReport.gaps] 同一把尺子。
 *
 * 收成一个函数是因为它有两个调用点（[enablePlan] 与 [remedyFor]），而两处漂移的后果
 * 各不相同却都难看：前者漏了会给一行没事可做的语言配上按钮，后者漏了会让**每一行**
 * 都长出一个「文档 ↗」——包括就绪的和 pi-lens 自己提供的。
 *
 * [LspStatus.READY] 没事可做；[LspStatus.AUTO_MANAGED] 是 pi-lens 自己会装，让用户
 * 再装一遍是纯噪音（用户原话「为什么还要按需安装？」）；[LspStatus.NOT_AVAILABLE]
 * 做什么都改变不了；[LspStatus.UNKNOWN] 是没查出来，编不出下一步。
 */
private fun isActionableGap(status: LspStatus): Boolean = status == LspStatus.MISSING_CONFIG || status == LspStatus.MISSING_BINARY

/** [enableCommands] 与 [enableBlocker] 的共同实现——两个出口，一份逻辑，不会漂移。 */
private class EnablePlan(
    val commands: List<String>,
    val blocker: LspTool?,
)

private fun enablePlan(
    language: LspLanguage,
    agentType: AgentType,
    status: LspStatus,
    toolAvailability: (String) -> BinaryAvailability,
): EnablePlan {
    if (!isActionableGap(status)) {
        return NOTHING_TO_DO
    }
    val steps = mutableListOf<String>()
    val binary = serverBinaryFor(language, agentType)
    if (binary != null && toolAvailability(binary) == BinaryAvailability.MISSING) {
        // 二进制确认不在，而目录表里没有已知安装命令（kotlin-language-server /
        // sourcekit-lsp 那几门）：链跑完也不可用，不给按钮。UNKNOWN 不得在这里被
        // 推断为缺失；配置缺口仍可只安装 CLI 插件。
        val install = LspCatalog.server(binary)?.installCommand ?: return NOTHING_TO_DO
        val prerequisite = toolChain(requiredTool(install), toolAvailability)
        if (prerequisite.blocker != null) {
            return prerequisite
        }
        steps += prerequisite.commands
        steps += install
    }
    if (status == LspStatus.MISSING_CONFIG) {
        // MISSING_CONFIG 只有 Claude Code 会产出，而它必定带着 claudePlugin
        // （LspCatalogTest 钉住 claudePlugin 与 claudeBinary 同时存在或同时缺失）。
        val plugin = language.claudePlugin ?: return NOTHING_TO_DO
        steps += "claude plugin install $plugin@claude-plugins-official"
    }
    return EnablePlan(steps, null)
}

/**
 * 让 [name] 这个工具在 PATH 里出现所需的命令链；已经在了就是空链。
 *
 * 写成循环而不是递归，是为了让「表里不小心成环」有个确定的收场（当作装不上，不给按钮）
 * 而不是栈溢出。`addFirst` 让链保持**依赖在前**的顺序：装 dotnet 要先有 brew。
 */
private fun toolChain(
    name: String,
    toolAvailability: (String) -> BinaryAvailability,
): EnablePlan {
    val steps = ArrayDeque<String>()
    val seen = mutableSetOf<String>()
    var current = name
    while (toolAvailability(current) == BinaryAvailability.MISSING) {
        if (!seen.add(current)) {
            return NOTHING_TO_DO
        }
        // 推导出来的工具不在表里：LspCatalogTest 钉住这不可能发生，真发生了也不该
        // 硬编一条安装命令，而是安静地不给按钮。
        val tool = LspCatalog.tool(current) ?: return NOTHING_TO_DO
        val command = tool.installCommand ?: return EnablePlan(emptyList(), tool)
        steps.addFirst(command)
        current = requiredTool(command)
    }
    return EnablePlan(steps.toList(), null)
}

private val NOTHING_TO_DO = EnablePlan(emptyList(), null)

/**
 * 一行在界面上的身份：**哪个 CLI 的哪门语言**。
 *
 * 设置页用它记住「哪几行正在跑」。必须带上 [agentType]——同一门语言在三个分组里各出现
 * 一次，只按语言记的话，用户在 Claude Code 那组点「启用」，pi 与 Codex 两组的同名语言
 * 会一起变成「正在启用…」，而那两行根本没有任何命令在跑；等 Claude 那条跑完重新探测，
 * 它们又一起变回去。三行同时说谎，用户会以为这一页在乱跳。
 *
 * 拼字符串而不是用 `Pair`：这一层刻意不引任何平台类型，字符串键让设置页那一侧的
 * 容器类型也保持平凡，且失败信息（键长什么样）人能直接读懂。
 * `AgentType.name` 全大写、语言 id 全小写，两段之间不可能互相串味。
 */
internal fun runRowKey(
    agentType: AgentType,
    language: LspLanguage,
): String = "${agentType.name}/${language.id}"

/**
 * 丢给终端标签的完整命令行。
 *
 * `-l` 与 `-i` 缺一不可，理由与 [com.github.izerui.imux.terminal.launchCommand]、
 * [ShellBinaryProbe] 完全相同，这个坑项目里踩过两次：
 *
 * - `-l` 读 profile。从 Dock/Finder 启动的 IDE 只有系统默认 PATH
 *   （`/usr/bin:/bin:/usr/sbin:/sbin`），而 `brew`、`go`、`npm`、`rustup`、`gem`
 *   一个都不在里面——不读 profile 的话，用户点「安装」得到的是 `command not found`。
 * - `-i` 读 rc，用户配成 **alias** 或 shell 函数的工具（本机的 `claude` 就是个 alias）
 *   才存在。
 *
 * 从终端 `runIde` 起的沙箱继承了终端的 PATH，所以这个缺陷**在沙箱里永远不会出现**，
 * 只有装到正式 IDE 上才暴露。
 *
 * PowerShell 反而要 `-NoProfile`，详见 [shellArgs] 的 KDoc。
 */
internal fun runCommandLine(
    shell: String,
    command: String,
): List<String> = listOf(shell) + shellArgs(dialectOf(shell)) + command

/**
 * 从命令里认出「这一标签在装什么」，用作终端标签名。
 *
 * 用户可能同时开着好几个安装标签（18 门语言，缺口往往不止一处），标签栏上只有
 * 十来个字符的位置——写完整命令的话，`brew install llvm` 与
 * `brew install lua-language-server` 在标签栏上前缀完全一样，认不出是哪个。
 * 取目标名之后是「启用 llvm」「启用 lua-language-server」，一眼可辨。
 *
 * 三步各去掉一层噪音，都对着目录表里的真实命令核过：
 * - 末段：`brew install --cask kotlin-lsp` &#8594; `kotlin-lsp`
 * - 去 `&#64;版本`：`gopls-lsp&#64;claude-plugins-official` &#8594; `gopls-lsp`
 * - 去路径前缀：`golang.org/x/tools/gopls` &#8594; `gopls`
 *
 * **喂给它的是整条命令链**（`a && b && c`），取的因此是**最后一步**的目标。
 * 这正是想要的：一条链的意义由它的终点决定——
 * `brew install --cask dotnet-sdk && dotnet tool install --global csharp-ls &&
 * claude plugin install csharp-lsp&#64;claude-plugins-official` 认出来是 `csharp-lsp`，
 * 而不是让用户在标签栏上看到 `dotnet-sdk` 去猜这跟 C# 有什么关系。
 *
 * 认不出来时退回整条命令：标签名难看远好过一个空标签。
 */
internal fun runTabTarget(command: String): String =
    command
        .trim()
        .substringAfterLast(' ')
        .substringBefore('@')
        .substringAfterLast('/')
        .ifEmpty { command.trim() }

/**
 * 终端标签的名字：动词 + 目标，例如「启用 kotlin-lsp」。
 *
 * [actionText] 由调用方拿 [ENABLE_ACTION_KEY] 过一遍 bundle 得到——这一层不碰
 * bundle（理由见文件顶部）。拼接本身搬进来，是为了让设置页那一侧的整条 builder 链
 * 能被逐字节钉住：链里留一个 `"${'$'}{…} ${'$'}{…}"` 字符串模板的话，断言要么写不出来，
 * 要么松到 `.tabName(` 前缀那个量级，而前缀断言拦不住 `.tabName("")`。
 */
internal fun runTabName(
    actionText: String,
    command: String,
): String = "$actionText ${runTabTarget(command)}"
