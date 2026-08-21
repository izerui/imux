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
import com.github.izerui.imux.lsp.runTabName
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
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.SystemInfo
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.nio.file.Path
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
 * 「我的 CLI 现在能不能用 LSP，不能的话该敲哪条命令」。
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

    private var refreshButton: JButton? = null

    override fun createPanel(): DialogPanel = panel {
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

    override fun isModified(): Boolean = false

    override fun apply() = Unit

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

    private fun showReport(report: LspReport) = replaceContent(
        panel {
            report.cliReports.forEach { cliReport ->
                group(cliReport.agentType.displayName) { renderCli(cliReport) }
            }
        },
    )

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
            renderRemedy(remedy)
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
     * 逐语言列表：目录表里的 **18 门语言一门不少**，每门一句状态。
     *
     * 这里绝不能再按状态过滤。此前只列「有问题的」语言，pi 组因此没有 TypeScript，
     * 真实用户据此得出「pi 不支持 TypeScript LSP」——而 pi-lens 恰恰会自动装它。
     * 对体检工具来说「没什么可查」和「不显示」差别极大：前者是好消息，后者是信息缺失。
     *
     * 语言行必须显式声明 [RowLayout.PARENT_GRID]。`Panel.row` 在不带 label 时构造的是
     * `RowLayout.INDEPENDENT`，而 `PanelBuilder` 对 INDEPENDENT 的处理是给每行开一个
     * 子网格——**列宽跨行不共享**。默认值下 `C | clangd` 与
     * `TypeScript/JavaScript | installed on demand by pi-lens` 的第三列起点会差出上百像素，
     * 18 行是一份参差的清单而不是一张对得齐的表。
     *
     * 但 [renderRemedy] 的命令行**保持默认的 INDEPENDENT**：把
     * `npm install -g typescript-language-server typescript` 拉进同一个网格，
     * 会把第一列（图标）撑到那条命令的宽度，整张表当场散架。
     */
    private fun findingsPanel(findings: List<LanguageFinding>, agentType: AgentType): JComponent =
        CappedHeightView(
            panel {
                findings.forEach { finding ->
                    row {
                        icon(statusIcon(finding.status))
                        label(finding.language.displayName)
                        label(statusText(finding, agentType))
                    }.layout(RowLayout.PARENT_GRID)
                    finding.remedy?.let { renderRemedy(it) }
                }
            },
        )

    /** 缩进一级挂在触发它的那条缺口之下，让「命令属于哪门语言」一眼可辨。 */
    private fun Panel.renderRemedy(remedy: Remedy) {
        indent {
            remedy.command?.let { command ->
                row {
                    label(command)
                    button(ImuxBundle.message("settings.lsp.copy")) {
                        CopyPasteManager.copyTextToClipboard(command)
                    }
                    runRemedyButton(remedy, command)
                }
            }
            // 没有已知安装命令时至少给出上游文档，不让用户卡在「不可用」三个字上
            if (remedy.command == null) {
                remedy.docsUrl?.let { url ->
                    row { browserLink(url, url) }
                }
            }
        }
    }

    /**
     * `[复制]` 旁边那个执行按钮——点一下开个终端标签，把命令跑起来。
     *
     * **平台与性质的取舍完全交给 [canRun]，壳里一个平台判断都不许有。** 目录表里的
     * 安装命令只在 macOS 上核实过（`brew install llvm`、`gem install ruby-lsp`、
     * `opam install ocaml-lsp-server`），从前它们只是显示出来给人复制，平台不对用户
     * 自己一眼就看出来；现在按钮点下去是**直接执行**。这条闸门是本次改动里唯一
     * 「点错了就在用户机器上跑错东西」的地方，所以它住在纯函数里、被真调用测试钉着，
     * 这里只剩一个调用点。
     *
     * 第二道闸门 [hasProjectWindow] 挡的是另一种「按钮在、点下去却什么都不发生」：
     * 终端标签是**项目级**的，而这一页是应用级设置，天生就会从欢迎页被打开。
     * 那时一个项目都没开，没有地方开标签——留一个点了没反应的按钮，比不给按钮更糟，
     * 而这一页在没有项目时**本来是完整的**：`[复制]` 加文档链接，信息一样不少。
     * 它刻意**不**并进 `canRun`：那是平台与命令性质的取舍，是纯的、可测的；
     * 「现在有没有项目窗口」是运行期环境，两者会各自变化。
     *
     * 按钮上的词同理走 [runActionKey]：壳里出现 `when (remedy.kind)` 就能在两个字面量
     * 都还留在源码里的前提下把「激活」和「安装」对调——用户点一个写着「激活」的按钮，
     * 等来的是几百兆下载。
     *
     * 整段函数体被 `ImuxLspUiSourceTest` 逐字节钉住。这不是洁癖：它是本页唯一一个
     * **职责就是可见性**的函数，而 `.visible(false)` / 守卫后面再补一句 `return` /
     * 按 `kind.ordinal` 分支，都是「加法」——逐条列举被禁 token 的黑名单永远漏得掉，
     * 整段比对漏不掉。代价是改动这四行要来测试里点头一次。
     */
    private fun Row.runRemedyButton(remedy: Remedy, command: String) {
        if (!canRun(remedy, SystemInfo.isMac, !SystemInfo.isWindows)) {
            return
        }
        if (!hasProjectWindow()) {
            return
        }
        button(ImuxBundle.message(runActionKey(remedy.kind))) { event ->
            runInTerminal(remedy, command, event)
        }
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
     * 跑完**不自动刷新**报告：命令在终端里异步跑，我们不知道它什么时候结束，
     * 猜一个时机只会给出更假的信息。页面顶部就有「重新检测」，用户装完自己点。
     */
    private fun runInTerminal(remedy: Remedy, command: String, event: ActionEvent) {
        // 渲染时 hasProjectWindow() 已经确认过有项目开着，这里再判一次是因为 262 的设置
        // 窗口是**非模态**的：从渲染到点击之间，用户完全可以把那个项目关掉。
        val project = targetProject(event)
        if (project == null) {
            LOG.warn("没有可用的项目窗口，无法执行：$command")
            return
        }
        TerminalToolWindowTabsManager.getInstance(project)
            .createTabBuilder()
            .workingDirectory(project.basePath ?: System.getProperty("user.home"))
            .shellCommand(runCommandLine(resolveShell(System.getenv("SHELL")), command))
            .tabName(runTabName(ImuxBundle.message(runActionKey(remedy.kind)), command))
            .requestFocus(true)
            .closeOnProcessTermination(false)
            .createTab()
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
        val key = statusMessageKey(finding.status)
            ?: return readyServerText(finding.language, agentType)
        return ImuxBundle.message(key)
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
     * 三个分组各 18 门语言，光语言行就 54 行，再加上缺口下面的命令行——不封顶的话
     * 设置页会被撑到上千像素，「重新检测」按钮以外的一切都得靠滚，而 `Row.scrollCell`
     * 包出来的 `JBScrollPane` 会原样跟着视图的 preferred height 长，等于白包一层。
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
         * `npm install -g typescript-language-server typescript` 加一个复制按钮，
         * 用户会看到一条读不全、复制得到却不知道全貌的命令。宁可出横向滚动条。
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
    }
}
