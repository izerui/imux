package com.github.izerui.imux.settings

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.lsp.CliReport
import com.github.izerui.imux.lsp.LanguageFinding
import com.github.izerui.imux.lsp.LspDiagnostics
import com.github.izerui.imux.lsp.LspLanguage
import com.github.izerui.imux.lsp.LspReport
import com.github.izerui.imux.lsp.LspStatus
import com.github.izerui.imux.lsp.Remedy
import com.github.izerui.imux.lsp.ShellBinaryProbe
import com.github.izerui.imux.model.AgentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Rectangle
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
     * 语言覆盖是否来自 pi-lens。pi 与挂了 `pi-lens-mcp` 的 Codex 走的是同一套 server，
     * 所以两组的 server 二进制取 [LspLanguage.piLensBinary]；Claude Code 用的是自己的
     * 官方插件，取 [LspLanguage.claudeBinary]——Kotlin 上这两者是不同的两个程序。
     */
    private fun coveredByPiLens(agentType: AgentType): Boolean = when (agentType) {
        AgentType.PI, AgentType.CODEX -> true
        else -> false
    }

    /** 就绪时显示的是**哪个** server 在供能：Kotlin 一门就有 kotlin-lsp 与 kotlin-language-server 两种。 */
    private fun serverBinary(language: LspLanguage, agentType: AgentType): String? =
        if (coveredByPiLens(agentType)) language.piLensBinary else language.claudeBinary

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
     * 每门语言那一句状态。
     *
     * 全都短到可以用 `label`：最长的是俄语的 AUTO_MANAGED（45 字符），
     * 加上最长的语言名 `TypeScript/JavaScript`（21 字符）也远在会撑宽对话框的量级之下。
     * 若将来有译文明显变长，这一列必须改用 `comment` 或 `text` —— 见类顶部的折行教训。
     *
     * READY 显示 server 二进制名而不是「已就绪」：绿勾已经说了「就绪」，
     * 这一栏用来回答「是谁在供能」，恰好把 Kotlin 那种两边 server 不同的情况说清楚。
     */
    private fun statusText(finding: LanguageFinding, agentType: AgentType): String = when (finding.status) {
        LspStatus.READY -> serverBinary(finding.language, agentType).orEmpty()
        LspStatus.MISSING_CONFIG -> ImuxBundle.message("settings.lsp.status.config")
        LspStatus.MISSING_BINARY -> ImuxBundle.message("settings.lsp.status.binary")
        LspStatus.UNKNOWN -> ImuxBundle.message("settings.lsp.status.unknown")
        LspStatus.AUTO_MANAGED -> ImuxBundle.message("settings.lsp.status.auto")
        LspStatus.NOT_AVAILABLE -> ImuxBundle.message("settings.lsp.status.unavailable")
    }

    /**
     * 状态图标。只用 [AllIcons] 的语义图标，不自绘。
     *
     * AUTO_MANAGED 用 Information 而不是 Warning：pi-lens 自动装是**好消息**，
     * 挂个警告牌等于把这次改动想纠正的误解换个形式又说了一遍。
     * NOT_AVAILABLE 用 Note——它既不是警告也不是「一切正常」，是一条中性注记：
     * 用户对它做不了任何事，AllIcons.General 里语义最接近的中性图标就是它。
     */
    private fun statusIcon(status: LspStatus): Icon = when (status) {
        LspStatus.READY -> AllIcons.General.InspectionsOK
        LspStatus.MISSING_CONFIG, LspStatus.MISSING_BINARY -> AllIcons.General.Warning
        LspStatus.AUTO_MANAGED -> AllIcons.General.Information
        LspStatus.NOT_AVAILABLE -> AllIcons.General.Note
        LspStatus.UNKNOWN -> AllIcons.General.QuestionDialog
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
