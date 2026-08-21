package com.github.izerui.imux.settings

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.lsp.CliReport
import com.github.izerui.imux.lsp.LanguageFinding
import com.github.izerui.imux.lsp.LspDiagnostics
import com.github.izerui.imux.lsp.LspReport
import com.github.izerui.imux.lsp.LspStatus
import com.github.izerui.imux.lsp.Remedy
import com.github.izerui.imux.lsp.ShellBinaryProbe
import com.github.izerui.imux.lsp.StatusIconKind
import com.github.izerui.imux.lsp.canRun
import com.github.izerui.imux.lsp.readyServerText
import com.github.izerui.imux.lsp.runActionKey
import com.github.izerui.imux.lsp.runCommandLine
import com.github.izerui.imux.lsp.runRowKey
import com.github.izerui.imux.lsp.runTabName
import com.github.izerui.imux.lsp.runningStatusKey
import com.github.izerui.imux.lsp.statusIconKind
import com.github.izerui.imux.lsp.statusMessageKey
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.terminal.resolveShell
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.SystemInfo
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.Scrollable

/**
 * Tools | Imux | LSP —— 三个 CLI 的 LSP 覆盖体检。
 *
 * 纯只读页：没有任何可保存的状态，[isModified] 恒为 false。它只回答一个问题——
 * 「我的 CLI 现在能不能用 LSP，不能的话点哪个按钮」。
 *
 * **每门语言只占一行**：图标 · 语言名 · 状态 · 操作。命令不再铺在页面上，
 * 它收进了操作按钮的 tooltip。这一版是照用户原话改的——「体验感不好，激活后，
 * 就状态应该变了啊，而且也不需要复制了吧」。三句话指向同一个根因：那一整行原始命令
 * 是噪音，它占掉一整行、把按钮挤到右边，18 门语言 × 3 组之后整页密不透风；
 * 而按钮点下去之后页面纹丝不动，用户无从判断到底有没有发生什么。
 *
 * 不做启动扫描、不弹通知：imux 已经有轮次完成提醒，再加一类噪音不划算。
 */
internal class ImuxLspConfigurable : BoundConfigurable("LSP") {

    private val content = JPanel(BorderLayout())

    /**
     * 探测代次：只有最后一次发起的探测有权刷新页面。
     *
     * 按钮在探测期间会禁用，正常路径下不会有第二次并发；代次号防的是禁用生效前的
     * 连击与将来多出来的调用点——每个 [ShellBinaryProbe] 都是一个 `zsh -l -i`，
     * 叠起来既是重复付 profile 的开销，也会让**先发起、后返回**的旧结果盖掉新结果。
     */
    private val generation = AtomicInteger()

    /**
     * 「命令跑完了，该重新探测一次」这类请求的代次，与 [generation] 各管一段。
     *
     * [generation] 防的是**已经发起**的探测互相盖；这一个防的是**还没发起**的探测扎堆：
     * 用户完全可能一口气点四五个「激活」，四五个终端标签各自跑完、各自要求重新探测，
     * 而一次探测就是一个登录 shell。让最后到达的那一个去跑，前面的静静作废。
     */
    private val refreshRequest = AtomicInteger()

    private var refreshButton: JButton? = null

    /**
     * 最近一份体检结果。
     *
     * 点击按钮那一刻要立刻把那一行改成「正在激活…」，而重新渲染需要原始数据。
     * 留一份比在点击时现场探测便宜得多——探测是登录 shell，而这一步要的只是重画。
     */
    private var lastReport: LspReport? = null

    /**
     * 正在跑的行：[runRowKey] 给的行标识 &#8594; 那一行状态列此刻该显示的 bundle 键。
     *
     * 存**键**而不是存 `RemedyKind`，是为了让「性质 &#8594; 文案」这个判断只存在于
     * [runningStatusKey] 那个被真调用测试钉住的纯函数里；壳里连 `RemedyKind` 都不必 import，
     * 也就没有第二处按性质分支的地方。
     *
     * 用 [ConcurrentHashMap]：写入发生在 EDT（点击）与协程线程（跑完清除）两侧。
     */
    private val running = ConcurrentHashMap<String, String>()

    /**
     * 页面自己的协程作用域，**刻意不复用终端 view 的那一个**。
     *
     * 等命令跑完要收 `TerminalView.sessionState`，而 `TerminalView.coroutineScope` 会随
     * 标签页关闭一起取消：把收集协程挂上去的话，用户中途关掉终端标签，协程当场没了，
     * 那一行就永远停在「正在激活…」——恰恰是这次要修的那个毛病换了个形状复发。
     *
     * 反过来，页面被关掉（[disposeUIResources]）时这个作用域必须取消：否则几分钟后
     * `brew install` 跑完，一个早已 dispose 的页面还会白起一个登录 shell 去探测。
     */
    private var scope: CoroutineScope? = null

    override fun createPanel(): DialogPanel {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return panel {
            row {
                comment(ImuxBundle.message("settings.lsp.scope.note"))
            }
            row {
                refreshButton = button(ImuxBundle.message("settings.lsp.refresh")) { refresh() }.component
            }
            row {
                cell(content).align(AlignX.FILL)
            }
        }.also { refresh() }
    }

    override fun isModified(): Boolean = false

    override fun apply() = Unit

    /**
     * 页面关掉之后不许再有任何后台动作。
     *
     * 等待中的收集协程随作用域一起取消，[running] 一并清空——同一个 Configurable 实例
     * 被再次 `createComponent` 时，界面上不该凭空出现几行「正在激活…」，
     * 它们对应的终端标签早就是上一次会话的事了。
     */
    override fun disposeUIResources() {
        scope?.cancel()
        scope = null
        running.clear()
        lastReport = null
        super.disposeUIResources()
    }

    /**
     * 体检要起登录 shell 读 profile，绝不能落在 EDT 上。
     *
     * 与 [com.github.izerui.imux.session.PiReportEndpointCache] 记录的是同一类教训：
     * 那次是 `BuiltInServerManager.waitForStart()` 在 EDT 上现算，IDE 刚启动时
     * 当场卡死界面。这里更糟——登录 shell 的启动开销是稳定存在的，不是偶发。
     *
     * 回 EDT 必须显式带 [ModalityState.any]：`createPanel` 跑在设置对话框**弹出之前**，
     * 此刻的默认模态是 NON_MODAL，而对话框一弹出就会把 NON_MODAL 的事件压到关闭之后
     * 才派发——页面会一直停在「正在检测…」，直到用户关掉设置窗口才刷新，等于没刷新。
     * 这里只改自己那块 Swing 面板，不碰 PSI/VFS/项目模型，正是 `any()` 的适用场景。
     */
    private fun refresh() {
        val token = generation.incrementAndGet()
        refreshButton?.isEnabled = false
        showChecking()
        ApplicationManager.getApplication().executeOnPooledThread {
            // 失败必须留痕：这一页唯一的诊断入口就是 idea.log，
            // 与 ShellBinaryProbe.locate() 的处理方式保持一致。
            val report = runCatching { diagnostics().run() }
                .onFailure { LOG.warn("LSP 体检失败，页面显示为检测未完成", it) }
                .getOrNull()
            ApplicationManager.getApplication().invokeLater(
                {
                    if (token == generation.get()) {
                        if (report == null) showFailed() else showReport(report)
                        refreshButton?.isEnabled = true
                    }
                },
                ModalityState.any(),
            )
        }
    }

    private fun diagnostics() = LspDiagnostics(
        userHome = Path.of(System.getProperty("user.home")),
        binaryProbe = ShellBinaryProbe(),
    )

    private fun showChecking() = replaceContent(JBLabel(ImuxBundle.message("settings.lsp.checking")))

    /** 失败必须与「进行中」长得不一样，否则用户只会以为很慢，反复点重新检测。 */
    private fun showFailed() = replaceContent(JBLabel(ImuxBundle.message("settings.lsp.failed")))

    private fun showReport(report: LspReport) {
        lastReport = report
        replaceContent(
            panel {
                report.cliReports.forEach { cliReport ->
                    group(cliReport.agentType.displayName) { renderCli(cliReport) }
                }
            },
        )
    }

    private fun replaceContent(component: JComponent) {
        content.removeAll()
        content.add(component, BorderLayout.CENTER)
        content.revalidate()
        content.repaint()
    }

    private fun Panel.renderCli(cliReport: CliReport) {
        if (!cliReport.installed) {
            row {
                icon(AllIcons.General.Information)
                label(ImuxBundle.message("settings.lsp.cli.missing", cliReport.agentType.displayName))
            }
            return
        }

        cliReport.groupRemedy?.let { remedy ->
            row {
                icon(AllIcons.General.Warning)
                // 与下面 no.findings 同一把尺子：label 产出不折行的 JLabel，preferred width
                // 直接抬高整页最小宽度。这句是葡语 98 / 德语 90 / 俄语 93 字符，且命中率远高于
                // no.findings——没装 pi-lens 或没给 Codex 挂 MCP 的用户每次开页都看到它。
                comment(groupMessage(cliReport))
            }
            row {
                groupAction(cliReport.agentType, remedy)
            }
            return
        }

        // 装了、没有前置修复、却一条语言结果都没有：Codex 挂了 pi-lens-mcp 但本机没装
        // pi（或 pi 没装 pi-lens）就是这个状态。不兜底的话这里会是个只有标题的空分组。
        //
        // 用 comment 而不是 label：这是本页最长的几句之一（德语 122 字符、俄语 118），
        // 而 UI DSL 的 label 产出不折行的 JLabel，它的 preferred width 会直接抬高整页的
        // 最小宽度，把设置对话框撑宽或逼出横向滚动条。comment 会在 DEFAULT_COMMENT_WIDTH
        // 处折行，而这句是「什么都没查到」的说明，灰色说明文字的语义也更贴。
        if (cliReport.findings.isEmpty()) {
            row {
                icon(AllIcons.General.Information)
                comment(ImuxBundle.message("settings.lsp.no.findings"))
            }
            return
        }

        // 汇总放在滚动区**外面**：18 行列表滚到哪儿，「有没有事要做」这个结论都还在眼前。
        row {
            icon(if (cliReport.gaps.isEmpty()) AllIcons.General.InspectionsOK else AllIcons.General.Warning)
            label(summaryText(cliReport))
        }
        row {
            scrollCell(findingsPanel(cliReport.findings, cliReport.agentType)).align(AlignX.FILL)
        }
    }

    /**
     * 顶部汇总只给两个计数，**不再把语言名拼成一行**。
     *
     * 曾经这行是「已就绪（13） C · C++ · C# · Go · …」，任何语种下都约 116 字符
     * ——语言显示名不随语言包变化——不折行的 `label` 会直接把设置对话框撑宽。
     * 现在语言名各自成行、进了滚动区，汇总退回到纯计数，最长也就
     * 「Ready (13)  ·  Missing (5)」这个量级，用 `label` 是安全的。
     *
     * 分母刻意不写：`gaps` 已收紧为「用户真能采取行动」的两种状态，
     * ready + gaps 不等于 18，写成「13/18」反而会让人去找剩下的 5 门去哪了。
     */
    private fun summaryText(cliReport: CliReport): String =
        ImuxBundle.message("settings.lsp.ready", cliReport.ready.size) + "   ·   " +
            ImuxBundle.message("settings.lsp.gaps", cliReport.gaps.size)

    /**
     * 逐语言列表：目录表里的 **18 门语言一门不少**，每门**只占一行**。
     *
     * 这里绝不能再按状态过滤。此前只列「有问题的」语言，pi 组因此没有 TypeScript，
     * 真实用户据此得出「pi 不支持 TypeScript LSP」——而 pi-lens 恰恰会自动装它。
     * 对体检工具来说「没什么可查」和「不显示」差别极大：前者是好消息，后者是信息缺失。
     *
     * 四列固定：状态图标 · 语言名 · 状态一句话 · 操作。**命令不在这里出现**——
     * 它进了操作按钮的 tooltip。从前它单独占一行，18 门语言里但凡有五六处缺口，
     * 这张表就被撑成两倍高，而那些命令用户十有八九一条都不会读。
     *
     * 语言行必须显式声明 [RowLayout.PARENT_GRID]。`Panel.row` 在不带 label 时构造的是
     * `RowLayout.INDEPENDENT`，而 `PanelBuilder` 对 INDEPENDENT 的处理是给每行开一个
     * 子网格——**列宽跨行不共享**。默认值下 `C | clangd` 与
     * `TypeScript/JavaScript | installed on demand by pi-lens` 的第三列起点会差出上百像素，
     * 18 行是一份参差的清单而不是一张对得齐的表。
     *
     * 现在第四列也进同一个网格，而这只有在**第四列很窄**时才成立：那里放的是一个按钮
     * （「激活」/「安装」）或一个短链接（「文档 ↗」）。之所以敢这么做，正是因为
     * `npm install -g typescript-language-server typescript` 这种长度的东西已经搬进
     * tooltip；把它放回单元格里，第一列（图标）会被撑成那条命令的宽度，整张表当场散架。
     */
    private fun findingsPanel(findings: List<LanguageFinding>, agentType: AgentType): JComponent =
        CappedHeightView(
            panel {
                findings.forEach { finding ->
                    row {
                        icon(rowIcon(finding, agentType))
                        label(finding.language.displayName)
                        label(statusText(finding, agentType))
                        rowAction(finding, agentType)
                    }.layout(RowLayout.PARENT_GRID)
                }
            },
        )

    /**
     * 一行末尾那一格：能跑的给按钮，跑不了的退回上游文档。
     *
     * 顺序不能反。按钮是**这一页唯一可执行的产出**，有它就不需要别的；
     * 而 [runRemedyButton] 会在闸门不放行时什么都不放（非 macOS 的安装命令、
     * 没有 POSIX shell、当前没开着项目窗口），此时这一格若也空着，
     * 用户就只剩「服务器不在 PATH 中」六个字，没有任何下一步——那正是删掉
     * `[复制]` 之后最容易掉进去的坑：命令收进了 tooltip，而 tooltip 依附于
     * 一个根本没被渲染出来的按钮。所以这里按**有没有真的放上按钮**来决定退路，
     * 而不是自己再判一次平台或命令有无（那就是第二处闸门了）。
     *
     * 没有 remedy 的行（就绪、pi-lens 自动管理、官方无对应插件、没查出来）
     * 这一格是空的：它们本来就没有下一步可做，摆个链接只会让人以为有事要办。
     */
    private fun Row.rowAction(finding: LanguageFinding, agentType: AgentType) {
        val remedy = finding.remedy ?: return
        val placed = remedy.command?.let { command ->
            runRemedyButton(runRowKey(agentType, finding.language), remedy, command)
        } ?: false
        if (!placed) {
            docsLink(remedy)
        }
    }

    /**
     * 组级修复（pi 未装 pi-lens、Codex 未挂 MCP）的那个按钮。
     *
     * 与语言行走同一套闸门和同一套「跑完自动重新探测」，只是行标识不来自语言：
     * 它是整组的前置条件，用 CLI 名加一个不可能与语言 id 相撞的后缀。
     */
    private fun Row.groupAction(agentType: AgentType, remedy: Remedy) {
        val placed = remedy.command?.let { command ->
            runRemedyButton(agentType.name + GROUP_ROW, remedy, command)
        } ?: false
        if (!placed) {
            docsLink(remedy)
        }
    }

    /** 上游文档链接。链接文字是短词，不是 URL——URL 有五十来个字符，会把这一列撑爆。 */
    private fun Row.docsLink(remedy: Remedy) {
        remedy.docsUrl?.let { url ->
            browserLink(ImuxBundle.message("settings.lsp.docs"), url)
        }
    }

    /**
     * 执行按钮——点一下开个终端标签，把命令跑起来。返回**是否真的放上了按钮**。
     *
     * **平台与性质的取舍完全交给 [canRun]，壳里一个平台判断都不许有。** 目录表里的
     * 安装命令只在 macOS 上核实过（`brew install llvm`、`gem install ruby-lsp`、
     * `opam install ocaml-lsp-server`），从前它们只是显示出来给人复制，平台不对用户
     * 自己一眼就看出来；现在按钮点下去是**直接执行**。这条闸门是「点错了就在用户机器上
     * 跑错东西」的唯一入口，所以它住在纯函数里、被真调用测试钉着，这里只剩一个调用点。
     *
     * 第二道闸门 [hasProjectWindow] 挡的是另一种「按钮在、点下去却什么都不发生」：
     * 终端标签是**项目级**的，而这一页是应用级设置，天生就会从欢迎页被打开。
     * 那时一个项目都没开，没有地方开标签——留一个点了没反应的按钮，比不给按钮更糟。
     * 它刻意**不**并进 `canRun`：那是平台与命令性质的取舍，是纯的、可测的；
     * 「现在有没有项目窗口」是运行期环境，两者会各自变化。
     *
     * 按钮上的词同理走 [runActionKey]：壳里出现 `when (remedy.kind)` 就能在两个字面量
     * 都还留在源码里的前提下把「激活」和「安装」对调——用户点一个写着「激活」的按钮，
     * 等来的是几百兆下载。
     *
     * `toolTipText` 是命令**唯一**的去处。用户原话「也不需要复制了吧」删掉的是复制按钮，
     * 不是知情权：鼠标停在按钮上就看得到马上要执行的整条命令，比原先把它铺在页面上
     * 更省地方，信息一点没少。
     *
     * `.enabled` 挡的是连点：命令在终端里异步跑，按钮不禁用的话用户会以为没反应而再点
     * 一次，于是同一条 `brew install` 开出两个标签抢同一把锁。它和守卫一样被逐字节钉住
     *——从前这个 `.enabled` 是被测试明令禁止的攻击写法（`.enabled(false)` 让按钮全灭），
     * 现在它有了正当语义，那就必须连**它的实参**一起钉死，否则等于把那扇门重新打开。
     *
     * 整段函数体被 `ImuxLspUiSourceTest` 逐字节钉住。这不是洁癖：它是本页唯一一个
     * **职责就是可见性**的函数，而 `.visible(false)` / 守卫后面再补一句 `return` /
     * 按 `kind.ordinal` 分支，都是「加法」——逐条列举被禁 token 的黑名单永远漏得掉，
     * 整段比对漏不掉。代价是改动这几行要来测试里点头一次。
     */
    private fun Row.runRemedyButton(key: String, remedy: Remedy, command: String): Boolean {
        if (!canRun(remedy, SystemInfo.isMac, !SystemInfo.isWindows)) {
            return false
        }
        if (!hasProjectWindow()) {
            return false
        }
        button(ImuxBundle.message(runActionKey(remedy.kind))) { event ->
            runInTerminal(key, remedy, command, event)
        }
            .enabled(!running.containsKey(key))
            .applyToComponent { toolTipText = command }
        return true
    }

    /**
     * 现在有没有一个能开终端标签的项目窗口。
     *
     * 262 上从欢迎页打开设置时，`CommonDataKeys.PROJECT` **取得到**东西——
     * `ShowSettingsUtilImplKt.createDialogWrapper` 第一句就是
     * `ProjectUtil.currentOrDefaultProject(project)`——但取到的是 **default project**，
     * 它没有窗口、也没有终端工具窗口。所以判据只能是「有没有真项目开着」，
     * 不能是「问不问得出一个 Project」。
     */
    private fun hasProjectWindow(): Boolean =
        ProjectManager.getInstance().openProjects.any { !it.isDisposed && !it.isDefault }

    /**
     * 开一个终端标签把命令跑起来——**不是后台静默执行**。
     *
     * 选终端而不是 `ProcessBuilder`，全部理由都在「用户看得见」这一条上：`brew` 偶尔要
     * 问 y/n，`npm` 会报权限错，装个 jdtls 要几分钟。后台执行的话用户只能对着一个
     * 转圈的按钮猜，而终端标签里输出可见、能答话、能 Ctrl-C。
     *
     * 因此 `closeOnProcessTermination` 必须**显式**写 false。它的默认值不是常量：
     * `TerminalToolWindowTabBuilderImpl` 的构造里读的是用户设置
     * `TerminalOptionsProvider.closeSessionOnLogout`。也就是说不写这一句的话，
     * 「命令跑完还看不看得到输出」取决于用户在终端设置里勾了什么——勾上的用户点一次
     * 「安装」，结果闪一下就没了。显式写 false 消除的是对一项**用户设置**的依赖，
     * 不只是覆盖一个默认值。
     *
     * **imux 自己仍然一个字节都不往用户文件里写。** `claude plugin install` 会改
     * `~/.claude/settings.json`，但改它的是 claude 这个 CLI，imux 只是替用户敲了那行字
     * ——敲在一个用户看得见、随时能打断的终端里。README 那句承诺因此继续成立。
     *
     * 开标签**之前**先把这一行标成「进行中」并立刻重画：新标签会抢焦点，页面这一刻
     * 已经不在用户眼前了；等回来时它必须已经变了样，而不是和点之前一模一样。
     */
    private fun runInTerminal(key: String, remedy: Remedy, command: String, event: ActionEvent) {
        // 渲染时 hasProjectWindow() 已经确认过有项目开着，这里再判一次是因为 262 的设置
        // 窗口是**非模态**的：从渲染到点击之间，用户完全可以把那个项目关掉。
        val project = targetProject(event)
        if (project == null) {
            LOG.warn("没有可用的项目窗口，无法执行：$command")
            return
        }
        running[key] = runningStatusKey(remedy.kind)
        lastReport?.let(::showReport)
        val tab = TerminalToolWindowTabsManager.getInstance(project)
            .createTabBuilder()
            .workingDirectory(project.basePath ?: System.getProperty("user.home"))
            .shellCommand(runCommandLine(resolveShell(System.getenv("SHELL")), command))
            .tabName(runTabName(ImuxBundle.message(runActionKey(remedy.kind)), command))
            .requestFocus(true)
            .closeOnProcessTermination(false)
            .createTab()
        refreshWhenFinished(key, tab.view)
    }

    /**
     * 等这条命令跑完，再整体重新探测一次。
     *
     * 这是用户那句「激活后，就状态应该变了啊」的正面回答。从前这里什么都不做，理由是
     * 「命令在终端里异步跑，我们不知道它什么时候结束，猜一个时机只会给出更假的信息」
     * ——理由本身没错，错在结论：262 的 `TerminalView.sessionState` **不用猜**，
     * 它会明确走到 [TerminalViewSessionState.Terminated]。
     *
     * 三条边界，每一条都能让那一行永远停在「正在激活…」：
     *
     * 1. **用户中途关掉终端标签**。关标签会取消 `view.coroutineScope`，`sessionState`
     *    从此再也不会变——只等 `Terminated` 的话就是干等到天荒地老。所以那个 scope 的
     *    Job 一结束就把等待协程取消掉，两条路汇到同一个出口（`join` 对正常完成与被取消
     *    一视同仁地返回）。**收集协程本身绝不能挂在 `view.coroutineScope` 上**，
     *    否则连「被取消了」这件事都没人来处理。
     * 2. **连点多个按钮**。多个标签并行跑完，多个重新探测请求扎堆，而一次探测是一个
     *    登录 shell。[refreshRequest] 代次号加一小段延时把它们合并成一次。
     * 3. **页面已经关闭**。作用域随 [disposeUIResources] 取消，协程在 `delay` 处就断了；
     *    `invokeLater` 那一刻再确认一次，挡住「刚好排在取消之前」的那一发。
     *
     * 重新探测走**完整**的一遍而不是只查这一条：探测本来就是一次批量 shell 调用，
     * 单独查一条既不更快，还要多写一条只在这里用到的窄路径。
     */
    private fun refreshWhenFinished(key: String, view: TerminalView) {
        val pageScope = scope ?: return
        pageScope.launch {
            val terminated = launch {
                view.sessionState.first { it is TerminalViewSessionState.Terminated }
            }
            val closed = view.coroutineScope.coroutineContext.job.invokeOnCompletion { terminated.cancel() }
            try {
                terminated.join()
            } finally {
                closed.dispose()
            }
            running.remove(key)
            val ticket = refreshRequest.incrementAndGet()
            delay(REFRESH_DEBOUNCE_MS)
            if (ticket == refreshRequest.get()) {
                ApplicationManager.getApplication().invokeLater(
                    { if (scope != null) refresh() },
                    ModalityState.any(),
                )
            }
        }
    }

    /**
     * 标签该开在哪个项目窗口里。
     *
     * 这一页是**应用级**设置（`applicationConfigurable`），手里没有 Project，而终端标签
     * 是项目级的。
     *
     * 先问按钮所在的窗口：262 从项目窗口打开设置默认走非模态窗口，
     * `NonModalWindowWrapper.uiDataSnapshot` 无条件塞入当前 project，所以同时开着两个
     * 项目时，用户在哪个窗口打开的设置，标签就出现在哪个窗口。
     *
     * **过滤 default project 不是防御性冗余，它是这条路径的主要失败形态**：从欢迎页
     * 打开设置时 `CommonDataKeys.PROJECT` 照样答得出来，答的是
     * `ProjectUtil.currentOrDefaultProject(null)` 给的 default project——它没有窗口，
     * 也没有终端工具窗口。那种情况下按钮压根不会被渲染出来（见 [hasProjectWindow]），
     * 这里的过滤只是让「非模态窗口下项目中途被关掉」时也退得干净。
     *
     * `openProjects` 那一段是兜底，正常路径到不了。
     */
    private fun targetProject(event: ActionEvent): Project? {
        val fromDialog = (event.source as? Component)
            ?.let { CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(it)) }
        val candidates = listOfNotNull(fromDialog) + ProjectManager.getInstance().openProjects
        return candidates.firstOrNull { !it.isDisposed && !it.isDefault }
    }

    /**
     * 组级修复的说明文案。
     *
     * 两个分支都只在「前置条件没满足」时才走得到：Codex 是没挂 MCP，pi 是没装 pi-lens
     * ——`codexReport` 与 `piReport` 都只在否定分支上设 groupRemedy。所以这里说的必须是
     * 「未挂载 / 未安装」，绝不能是「已安装」。Claude Code 从不产生组级修复，落不到这里。
     */
    private fun groupMessage(cliReport: CliReport): String =
        when (cliReport.agentType) {
            AgentType.CODEX -> ImuxBundle.message("settings.lsp.codex.mount")
            else -> ImuxBundle.message("settings.lsp.pi.missing")
        }

    /**
     * 每门语言那一句状态——**薄壳**：向 [statusMessageKey] 取键、交给 [ImuxBundle]，
     * 没有键的（只有 READY）显示 server 二进制名。
     *
     * [running] 排在最前面：这一行正在跑命令时，说的必须是「正在激活…」，而不是那条
     * 还没被推翻的旧状态。用户点下按钮之后盯着的就是这一列——它不动，用户就认为点击
     * 没生效，然后再点一次。查表而不是 `if`，是为了让「性质 &#8594; 文案」的判断留在
     * [runningStatusKey] 那个能被真正调用测的纯函数里。
     *
     * 壳里不得再出现任何按 [LspStatus] 的判断。这不是洁癖：状态与文案的对应搬进
     * `LspStatusPresentation` 正是为了让它能被真正调用、被行为测试钉住；只要这里
     * 补一句 `if (status == X) return message(Y)`，那些行为测试就全部失效，
     * 而「文案字面量还在源码里」的文本断言一条都拦不住——这一类缺陷已经复活过两次。
     *
     * 文字长度：最长的是俄语的 AUTO_MANAGED（45 字符），加上最长的语言名
     * `TypeScript/JavaScript`（21 字符）仍远在会撑宽对话框的量级之下，用 `label` 安全。
     * 若将来有译文明显变长，这一列必须改用 `comment` 或 `text`——见类顶部的折行教训。
     */
    private fun statusText(finding: LanguageFinding, agentType: AgentType): String {
        val key = running[runRowKey(agentType, finding.language)]
            ?: statusMessageKey(finding.status)
            ?: return readyServerText(finding.language, agentType)
        return ImuxBundle.message(key)
    }

    /**
     * 一行最左边那个图标。
     *
     * 正在跑命令时换成进行中的图标，理由与 [statusText] 完全相同：那一行必须整体改变
     * 面貌，只换文字不换图标的话，一列绿勾/黄叹号里混着一句「正在激活…」，
     * 反而像是显示出错了。
     *
     * 用 [AllIcons.Process.Step_1] 这个**静态**帧，不用 `AnimatedIcon.Default`：
     * 后者不在 [AllIcons] 里（项目约定图标只取 AllIcons），而且它靠计时器重绘，
     * 挂在一张随时会被整体重建的表上只是白烧 EDT。这一行本来就会在几秒到几分钟内
     * 被一次完整重新探测覆盖掉，静态帧足够表达「这一格和别人不一样」。
     */
    private fun rowIcon(finding: LanguageFinding, agentType: AgentType): Icon {
        val inProgress = running.containsKey(runRowKey(agentType, finding.language))
        return if (inProgress) AllIcons.Process.Step_1 else statusIcon(finding.status)
    }

    /**
     * 状态图标——**薄壳**：语义类别由 [statusIconKind] 决定，这里只负责把类别换成
     * [AllIcons] 的常量，不自绘、也不再按 [LspStatus] 判断（理由同 [statusText]）。
     *
     * 下面这五条对应关系**同样被测试钉住**，改动前请想清楚。[statusIconKind] 那边的
     * 「哪些状态算警告」约束的是枚举值的归属，管不到这一层：把 `INFO` 这一行改成
     * `Warning`，那条不变量原封不动全绿，而 pi 组的 TypeScript / Python / Ruby / Rust /
     * PHP / C# 在界面上集体挂起黄色警告牌——用户看见的是图标，不是枚举常量。
     */
    private fun statusIcon(status: LspStatus): Icon = when (statusIconKind(status)) {
        StatusIconKind.OK -> AllIcons.General.InspectionsOK
        StatusIconKind.WARNING -> AllIcons.General.Warning
        StatusIconKind.INFO -> AllIcons.General.Information
        StatusIconKind.NEUTRAL -> AllIcons.General.Note
        StatusIconKind.QUESTION -> AllIcons.General.QuestionDialog
    }

    /**
     * 滚动区的视图：把可视高度封顶，超出部分交给滚动条。
     *
     * 三个分组各 18 门语言，光语言行就 54 行——不封顶的话设置页会被撑到上千像素，
     * 「重新检测」按钮以外的一切都得靠滚，而 `Row.scrollCell` 包出来的 `JBScrollPane`
     * 会原样跟着视图的 preferred height 长，等于白包一层。
     *
     * 封顶必须走 [Scrollable]：`JViewport` 的布局器（`ViewportLayout`）在视图实现
     * 该接口时取 [getPreferredScrollableViewportSize] 决定滚动面板的 preferred size，
     * 而直接改视图的 `preferredSize` 只会把内容压扁、连滚都滚不动。
     */
    private class CappedHeightView(view: JComponent) : JPanel(BorderLayout()), Scrollable {

        init {
            add(view, BorderLayout.CENTER)
            isOpaque = false
        }

        override fun getPreferredScrollableViewportSize(): Dimension =
            Dimension(preferredSize.width, minOf(preferredSize.height, JBUI.scale(MAX_VISIBLE_HEIGHT)))

        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
            JBUI.scale(UNIT_SCROLL)

        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
            visibleRect.height

        /**
         * 视口够宽就把内容拉满宽度，不够宽时交还横向滚动条。
         *
         * 恒返回 true 会让「宽度不足」直接表现为**截断**：本页最长的行是
         * `TypeScript/JavaScript` 加上俄语那句状态说明再加一个按钮，用户会看到一行
         * 读不全的状态。宁可出横向滚动条。
         */
        override fun getScrollableTracksViewportWidth(): Boolean {
            val viewport = parent as? JViewport ?: return true
            return viewport.width >= minimumSize.width
        }

        override fun getScrollableTracksViewportHeight(): Boolean = false

        private companion object {
            /** 未缩放像素，约 12 行——一屏能看到大半张表，又不至于把三个分组顶出可视区。 */
            const val MAX_VISIBLE_HEIGHT = 300
            const val UNIT_SCROLL = 24
        }
    }

    private companion object {
        val LOG = logger<ImuxLspConfigurable>()

        /**
         * 组级修复那一行的行标识后缀，接在 `AgentType.name` 后面。
         *
         * 带空格，与 [runRowKey] 拼出来的任何一行都不可能相撞（目录表里的语言 id
         * 全是 `c` / `cpp` 这类标识符）。撞上的话，用户点一次「安装 pi-lens」，
         * 某门语言会跟着假装自己在跑。
         */
        const val GROUP_ROW = "/group remedy"

        /**
         * 合并「跑完了要重新探测」请求的时间窗，毫秒。
         *
         * 只需要盖住「几个标签几乎同时结束」这一种情况，不是给用户看的延迟——
         * 取到秒级的话，用户会先看见一行已经不再「正在激活…」、状态却还是旧的表。
         */
        const val REFRESH_DEBOUNCE_MS = 300L
    }
}
