package com.github.izerui.imux.settings

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.lsp.CliReport
import com.github.izerui.imux.lsp.ENABLE_ACTION_KEY
import com.github.izerui.imux.lsp.ENABLING_STATUS_KEY
import com.github.izerui.imux.lsp.LanguageFinding
import com.github.izerui.imux.lsp.LspDiagnostics
import com.github.izerui.imux.lsp.LspReport
import com.github.izerui.imux.lsp.LspStatus
import com.github.izerui.imux.lsp.Remedy
import com.github.izerui.imux.lsp.ShellBinaryProbe
import com.github.izerui.imux.lsp.StatusIconKind
import com.github.izerui.imux.lsp.canRun
import com.github.izerui.imux.lsp.readyServerText
import com.github.izerui.imux.lsp.runCommandLine
import com.github.izerui.imux.lsp.runRowKey
import com.github.izerui.imux.lsp.runTabName
import com.github.izerui.imux.lsp.runTabTarget
import com.github.izerui.imux.lsp.statusIconKind
import com.github.izerui.imux.lsp.statusMessageKey
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.terminal.resolveShell
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.ToolWindowManager
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
import org.jetbrains.plugins.terminal.TerminalOptionsProvider
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
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
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.Scrollable

/**
 * Tools | Imux | LSP —— 三个 CLI 的 LSP 覆盖体检。
 *
 * 纯只读页：没有任何可保存的状态，[isModified] 恒为 false。它只回答一个问题——
 * 「我的 CLI 现在能不能用 LSP，不能的话点哪个按钮」。
 *
 * **每门语言只占一行**：图标、语言名、状态、操作。完整命令只出现在操作按钮的
 * tooltip 中；按钮启动后仅就地更新对应行，保留滚动位置与焦点。
 *
 * 不做启动扫描、不弹通知：只有用户打开本页或主动刷新时才执行探测。
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
     * 用户完全可能一口气点四五个「启用」，四五个终端标签各自跑完、各自要求重新探测，
     * 而一次探测就是一个登录 shell。让最后到达的那一个去跑，前面的静静作废。
     */
    private val refreshRequest = AtomicInteger()

    private var refreshButton: JButton? = null

    /**
     * 最近一份体检结果。
     *
     * 它现在只回答一个是非题：**这一页有没有拿出过结果**。没有（首次打开）才需要
     * [showChecking] 那句「正在检测…」；有过的话，重新探测期间屏幕原样不动——见 [refresh]。
     *
     * 刻意**不**在探测期间拿它重画一遍。命令跑完那条路上 `running.remove(key)` 已经先
     * 执行，用旧 finding 重跑一次映射得到的是**装之前**的状态，等于把刚被推翻的数据
     * 当成新鲜的摆出来。
     *
     * 从前它还兼着「点一下按钮就整页重画」的差事，那条路已经换成 [refreshRow] 的就地
     * 更新了：为了让一行改个字而重建 54 行组件树，正是用户抱怨的那次闪烁。
     */
    private var lastReport: LspReport? = null

    /**
     * 正在跑的行：[runRowKey] 给的行标识 &#8594; 那一行状态列此刻该显示的 bundle 键。
     *
     * 存**键**而不是存一个布尔，是为了让 [statusText] 那条「查表 &#8594; 过 bundle」的
     * 薄壳一个字都不用改：值取自 [ENABLING_STATUS_KEY]（壳外的常量），壳里没有任何一处
     * 自己拼这个键的地方。从前这里存的是按 `RemedyKind` 映出来的两个键之一，而那个
     * 「激活 / 安装」的区分已经随本轮一起删掉了——它是按实现分的，不是按用户心智分的。
     *
     * 用 [ConcurrentHashMap]：写入发生在 EDT（点击）与协程线程（跑完清除）两侧。
     */
    private val running = ConcurrentHashMap<String, String>()

    /**
     * 每一行**就地可改**的那两个组件：[runRowKey] 给的行标识 &#8594; 状态图标与状态文案。
     *
     * 该映射只保存重算显示所需的模型与组件，不缓存“原图标/运行中图标”两套值；
     * [refreshRow] 始终经 [rowIcon] / [statusText] 重算，因此进入和退出运行态共用同一路径。
     *
     * [replaceContent] 在构造新组件之前清空映射，避免后续更新落到已从界面移除的组件。
     */
    private val rowCells = ConcurrentHashMap<String, RowCells>()

    /**
     * 页面自己的协程作用域，**刻意不复用终端 view 的那一个**。
     *
     * 等命令跑完要收 `TerminalView.sessionState`，而 `TerminalView.coroutineScope` 会随
     * 标签页关闭一起取消：把收集协程挂上去的话，用户中途关掉终端标签，协程当场没了，
     * 那一行就永远停在「正在启用…」——恰恰是这次要修的那个毛病换了个形状复发。
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
            // 从欢迎页打开设置时一个执行按钮都不会有（见 hasProjectWindow），而页面从前
            // 对此只字不提。用户原话：「没看到激活按钮啊，没有任何操作按钮是咋回事」——
            // 他看到的是一列数据加一片空白，连第四列那个 `kotlin-lsp` 短名都读不成
            // 「可操作信息」。技术原因是实的（终端工具窗口是项目级的），但那是我们的实现
            // 细节，不说出来就等于让用户以为插件坏了。
            //
            // 判据刻意**复用同一个** hasProjectWindow()，不另写一句「有没有项目」：
            // 两处若各判各的，就会出现「说明说没按钮、按钮却在」或者反过来的组合。
            if (!hasProjectWindow()) {
                row {
                    icon(AllIcons.General.Information)
                    comment(ImuxBundle.message("settings.lsp.no.project"))
                }
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
     * 被再次 `createComponent` 时，界面上不该凭空出现几行「正在启用…」，
     * 它们对应的终端标签早就是上一次会话的事了。
     */
    override fun disposeUIResources() {
        scope?.cancel()
        scope = null
        running.clear()
        rowCells.clear()
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
     *
     * **手里已经有一份结果时，探测期间屏幕上一个像素都不许动。** 这一页的探测有两个
     * 入口，只有一个该改屏幕：
     *
     * - **首次打开**：手里什么都没有，[showChecking] 那句「正在检测…」是唯一能说的话。
     * - **重新探测**：手动点「重新检测」，或者一条安装命令跑完之后自动来这一趟。
     *   这时候什么都不做——屏幕上摆着的正是 [refreshRow] 刚刚改好的那一行
     *  （「正在启用…」+ 转圈图标），让它一直摆到真结果回来。反馈由「重新检测」按钮
     *   自己禁用给出，不需要动内容区。
     *
     * 这里**绝不能**写成 `showReport(lastReport)`「先用旧数据重画一遍」。看着像是省掉了
     * 一次闪白，实际是把一份**刚刚被推翻的数据重新算一遍再当成新鲜的摆出来**：命令跑完
     * 那条路上 `running.remove(key)` 已经先执行了，拿旧 finding 重跑 [rowIcon] /
     * [statusText] 得到的正是**装之前**的状态。用户刚看着一条 `brew install` 成功退出，
     * 设置页立刻告诉他「未启用插件」——比闪白更假。同一个理由让「检测未完成」之后点
     * 重新检测不会先弹出一整张完整的结果表（看起来像重试成功了）再翻回失败。
     *
     * 顺带把 [refreshRow] 那句「不重画整页」兑现到**完成时刻**：走 `showReport` 的话，
     * 命令跑完仍然会整棵树重建一次、滚动位置照样回到顶部，用户抱怨的那次刷新只是被
     * 推迟了几秒。现在整页重建只发生在**真结果到手**的那一刻，一次，不可避免的那一次。
     *
     * 「进行中」与「失败」仍然是两副面孔（[showChecking] 与 [showFailed] 各说各的），
     * 这条约定没动——变的只是「进行中」什么时候需要露面。
     *
     * 这里的**两个 `if` 都刻意写成带大括号**的形式。`if (…) 单句` 加大括号是一次
     * IDEA intention、零语义变化，而这个函数体是被逐字节钉住的；先写成带括号的样子，
     * 那次纯排版操作就成了 no-op，不会有人因为按了一下 Alt+Enter 而收到一条
     * 「你让整页闪白了」的误报。`report == null` 那个分支从前是不带括号的写法，
     * 同一次 intention 会让它连红两条用例——顺手一起改成带括号。
     */
    private fun refresh() {
        val token = generation.incrementAndGet()
        refreshButton?.isEnabled = false
        if (lastReport == null) {
            showChecking()
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            // 失败必须留痕：这一页唯一的诊断入口就是 idea.log，
            // 与 ShellBinaryProbe.locate() 的处理方式保持一致。
            val report =
                runCatching { diagnostics().run() }
                    .onFailure { LOG.warn("LSP 体检失败，页面显示为检测未完成", it) }
                    .getOrNull()
            ApplicationManager.getApplication().invokeLater(
                {
                    if (token == generation.get()) {
                        if (report == null) {
                            showFailed()
                        } else {
                            showReport(report)
                        }
                        refreshButton?.isEnabled = true
                    }
                },
                ModalityState.any(),
            )
        }
    }

    private fun diagnostics() =
        LspDiagnostics(
            userHome = Path.of(System.getProperty("user.home")),
            binaryProbe = ShellBinaryProbe(),
        )

    private fun showChecking() = replaceContent { JBLabel(ImuxBundle.message("settings.lsp.checking")) }

    /**
     * 失败必须与「进行中」长得不一样，否则用户只会以为很慢，反复点重新检测。
     *
     * `lastReport = null` 不是清理，是**行为**：[refresh] 拿 [lastReport] 判断
     * 「这一页有没有拿出过结果」，而探测失败之后**它拿不出结果了**——屏幕上摆的是
     * 「检测未完成」，不是一张表。不清的话会出现同一个可见状态两种行为：
     *
     * - 首探即失败 &#8594; 点重新检测 &#8594; `lastReport` 是 null &#8594; 有「正在检测…」
     * - 先成功、后失败 &#8594; 点重新检测 &#8594; `lastReport` 还留着 &#8594; 什么都不画，
     *   内容区在整个探测期间零反馈
     *
     * 用户看到的是同一句「检测未完成」，点同一个按钮，结果一个有反馈一个没有。
     * 清掉之后两条路合并成前者。好路径一概不受影响：[showReport] 每次都会把它设回去。
     */
    private fun showFailed() {
        lastReport = null
        replaceContent { JBLabel(ImuxBundle.message("settings.lsp.failed")) }
    }

    /** 整页重画——**只在真结果到手那一刻走这里**，见 [refresh]。 */
    private fun showReport(report: LspReport) {
        lastReport = report
        replaceContent {
            panel {
                report.cliReports.forEach { cliReport ->
                    group(cliReport.agentType.displayName) { renderCli(cliReport) }
                }
            }
        }
    }

    /**
     * 换掉内容区——本页**唯一**的重建原语，[rowCells] 的清空因此只能住在这里。
     *
     * 收口不是洁癖，是顺序问题。清空必须发生在**新组件被构造之前**：`panel { … }` 一跑
     * 就会往 [rowCells] 里登记这一批新 JLabel，清空若排在它后面（无论是写在
     * [showReport] 末尾，还是写在本函数里但排在构造之后），刚登记的东西当场被抹掉，
     * 于是每一次点击都在 `rowCells[key] ?: return` 处悄悄返回——按钮灰了，图标和文案
     * 纹丝不动，**用户看到的与「点了没用」那个老毛病一模一样**，而所有整段比对全绿。
     *
     * 所以这里收的是**构造函数**而不是构造好的组件：实参在调用点先于函数体求值，
     * 传组件的写法根本没有「先清空再构造」这个可能。
     *
     * 顺带盖住 [showChecking] / [showFailed] 那两条路：它们同样把整棵树换掉，
     * 留在表里的引用同样是一批已经摘下来的旧组件。
     */
    private fun replaceContent(build: () -> JComponent) {
        rowCells.clear()
        val component = build()
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
     * （「启用」）或一个短链接（「文档 ↗」）。之所以敢这么做，正是因为
     * `npm install -g typescript-language-server typescript` 这种长度的东西已经搬进
     * tooltip；把它放回单元格里，第一列（图标）会被撑成那条命令的宽度，整张表当场散架。
     *
     * 前两列的组件顺手记进 [rowCells]。`Cell.component` 拿到的就是 UI DSL 摆上去的那个
     * JLabel 本体，之后点按钮时直接改它——整页重建那条老路的代价是滚动位置丢失、整页闪烁，
     * 而这两件事恰恰发生在用户最需要看清一行的时刻。
     */
    private fun findingsPanel(
        findings: List<LanguageFinding>,
        agentType: AgentType,
    ): JComponent =
        CappedHeightView(
            panel {
                findings.forEach { finding ->
                    row {
                        val iconLabel = icon(rowIcon(finding, agentType)).component
                        label(finding.language.displayName)
                        val statusLabel = label(statusText(finding, agentType)).component
                        rowAction(finding, agentType)
                        rowCells[runRowKey(agentType, finding.language)] =
                            RowCells(finding, agentType, iconLabel, statusLabel)
                    }.layout(RowLayout.PARENT_GRID)
                }
            },
        )

    /**
     * 一行末尾那一格：能跑的给按钮，跑不了的走 [fallbackCell]。
     *
     * 顺序不能反。按钮是**这一页唯一可执行的产出**，有它就不需要别的；
     * 而 [runRemedyButton] 会在闸门不放行时什么都不放，此时这一格若也空着，
     * 用户就只剩「未启用插件」四个字，没有任何下一步。所以这里按**有没有真的放上
     * 按钮**来决定退路，而不是自己再判一次平台或命令有无（那就是第二处闸门了）。
     *
     * 函数体被 `ImuxLspUiSourceTest` 逐字节钉住，理由与 [runRemedyButton] 同一条：
     * 这里的每一种缺陷都是**加法**。实测在第一行前面补一句
     * `if (finding.status == LspStatus.MISSING_CONFIG) return`，Claude Code 组 13 门语言的
     * 「启用」按钮全部消失，而「壳里不得有第二处平台判断」只禁 `SystemInfo.`、
     * 不禁 `LspStatus.`，整套断言全绿。逐条列举被禁 token 漏得掉下一种，
     * 整段比对漏不掉。
     *
     * 没有 remedy 的行（就绪、pi-lens 自动提供、官方无对应插件、没查出来）
     * 这一格是空的：它们本来就没有下一步可做，摆个链接只会让人以为有事要办。
     * **`AUTO_MANAGED` 现在正是靠这一条不给按钮**——用户看到「由 pi-lens 提供」加一个
     * 绿勾、行末什么都没有，读出来的就是「这一行我不用管」。他从前读的是「按需安装」加
     * 一个信息图标，于是问「为什么还要按需安装？」。
     */
    private fun Row.rowAction(
        finding: LanguageFinding,
        agentType: AgentType,
    ) {
        val remedy = finding.remedy ?: return
        val placed = runRemedyButton(runRowKey(agentType, finding.language), remedy)
        if (!placed) {
            fallbackCell(remedy)
        }
    }

    /**
     * 组级修复（pi 未装 pi-lens、Codex 未挂 MCP）的那个按钮。
     *
     * 与语言行走同一套闸门、同一套退路、同一套「跑完自动重新探测」，只是行标识不来自
     * 语言：它是整组的前置条件，用 CLI 名加一个不可能与语言 id 相撞的后缀。
     */
    private fun Row.groupAction(
        agentType: AgentType,
        remedy: Remedy,
    ) {
        val placed = runRemedyButton(agentType.name + GROUP_ROW, remedy)
        if (!placed) {
            fallbackCell(remedy)
        }
    }

    /**
     * 闸门挡下按钮时的退路——**这一格绝不能是空的**。
     *
     * 删掉复制按钮之后，命令唯一的去处是按钮的 tooltip；而 tooltip 依附于一个
     * [runRemedyButton] 可能压根没渲染出来的按钮。空着的后果比想象中大：
     *
     * - [hasProjectWindow] 这道闸门在**所有平台**都会关——这一页是 `applicationConfigurable`，
     *   从欢迎页打开设置是完全正常的路径。macOS 用户一样撞得上。
     * - 撞上的行不是少数：Claude Code 组 13 门带官方插件的语言只要没启用都算，
     *   Codex 组的 `groupRemedy` 更是连 `docsUrl` 都没有。只给文档链接的话，
     *   那一整组会退化成「一句警告 + 什么也没有」。
     *
     * 三样东西，各答一个问题，**有几样给几样，绝不二选一**（二选一就是第二处闸门）：
     *
     * 1. [Remedy.blockingTool]——「要先装什么」。链根本组不出来时唯一有用的一句：
     *    `brew` / `go` / `npm` / `gem` 不在 PATH，而我们没有可靠的安装方式。
     *    此时 [Remedy.chainFor] 必为空，[Remedy.docsUrl] 换成的是**那个工具**的官网。
     * 2. [Remedy.chainFor]——「会跑什么」。摆一个**不可点的短标签**，文字取 [runTabTarget]
     *   （整条链取最后一步：`… && claude plugin install kotlin-lsp@…` &#8594; `kotlin-lsp`），
     *    完整链挂 tooltip。用户缺的恰恰就是那个插件名/包名——CLI 的官方文档只会告诉他
     *    「有 plugin install 这个子命令」，不会告诉他 Kotlin 对应哪个插件。短标签让这一
     *    列仍然只有十来个字符，不会退回「一整行原始命令」那种把表撑爆的形态。
     * 3. [Remedy.docsUrl]——「去哪儿自己动手」。非 macOS 上命令是 macOS 形状（所以没有
     *    按钮），文档链接才是那个平台的正路，而命令本身仍然是有用的线索。
     */
    private fun Row.fallbackCell(remedy: Remedy) {
        remedy.blockingTool?.let { tool ->
            label(ImuxBundle.message("settings.lsp.tool.missing", tool))
        }
        remedy.chainFor(remedyShell())?.let { chain ->
            label(runTabTarget(chain)).applyToComponent { toolTipText = chain }
        }
        remedy.docsUrl?.let { url ->
            browserLink(ImuxBundle.message("settings.lsp.docs"), url)
        }
    }

    /**
     * 执行按钮——点一下开个终端标签，把命令跑起来。返回**是否真的放上了按钮**。
     *
     * **平台与性质的取舍完全交给 [canRun]，壳里一个平台判断都不许有。** brew 与 opam
     * 系的安装命令只在 macOS 上核实过（`brew install llvm`、`brew install opam`、
     * `opam install ocaml-lsp-server`），npm / go / gem / dotnet / rustup 那 8 条三平台
     * 形态完全相同已经放开。这条闸门是「点错了就在用户机器上跑错东西」的唯一入口，
     * 所以它住在纯函数里、被真调用测试钉着，这里只剩一个调用点。
     *
     * 第二道闸门 [hasProjectWindow] 挡的是另一种「按钮在、点下去却什么都不发生」：
     * 终端标签是**项目级**的，而这一页是应用级设置，天生就会从欢迎页被打开。
     * 那时一个项目都没开，没有地方开标签——留一个点了没反应的按钮，比不给按钮更糟。
     * 它刻意**不**并进 `canRun`：那是平台与命令性质的取舍，是纯的、可测的；
     * 「现在有没有项目窗口」是运行期环境，两者会各自变化。
     *
     * 按钮上**只有一个词「启用」**，不再按修复性质分成「激活」与「安装」。那个区分是
     * 按实现分的（配置层 vs 二进制层），用户原话：「虽然说我不知道你这两个是啥意思吧，
     * 你能让用户怎么方便怎么来就行了」。他不需要知道底下有几层、缺的是哪一层——
     * 点一下，缺的每一层按顺序跑完。
     *
     * `toolTipText` 是命令链**唯一**的去处，而它同时承担了从前「安装」那个词的差事：
     * 「点下去要不要等」现在由**看得见的整条链**回答（`brew install --cask dotnet-sdk
     * && …` 一眼就知道有大件下载），比一个概括的词准确。用户原话「也不需要复制了吧」
     * 删掉的是复制按钮，不是知情权。
     *
     * `.enabled` 挡的是连点：命令在终端里异步跑，按钮不禁用的话用户会以为没反应而再点
     * 一次，于是同一条 `brew install` 开出两个标签抢同一把锁。它和守卫一样被逐字节钉住
     *——从前这个 `.enabled` 是被测试明令禁止的攻击写法（`.enabled(false)` 让按钮全灭），
     * 现在它有了正当语义，那就必须连**它的实参**一起钉死，否则等于把那扇门重新打开。
     *
     * 已知代价：Swing 的 `ToolTipManager` 不给 disabled 组件派发鼠标事件，所以**这一行
     * 正在跑的那几秒里，命令 tooltip 读不到**。转瞬即逝且只影响已经点过的那一行
     *（命令刚刚才执行过），而闸门挡下按钮那种**持续**读不到的情况由 [fallbackCell] 兜住。
     *
     * 整段函数体被 `ImuxLspUiSourceTest` 逐字节钉住。这不是洁癖：它是本页唯一一个
     * **职责就是可见性**的函数，而 `.visible(false)` / 守卫后面再补一句 `return` /
     * 按 `kind.ordinal` 分支，都是「加法」——逐条列举被禁 token 的黑名单永远漏得掉，
     * 整段比对漏不掉。代价是改动这几行要来测试里点头一次。
     */
    private fun Row.runRemedyButton(
        key: String,
        remedy: Remedy,
    ): Boolean {
        if (!canRun(remedy, SystemInfo.isMac)) {
            return false
        }
        if (!hasProjectWindow()) {
            return false
        }
        val command = remedy.chainFor(remedyShell()) ?: return false
        button(ImuxBundle.message(ENABLE_ACTION_KEY)) { event ->
            runInTerminal(key, command, event)
        }.enabled(!running.containsKey(key))
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
    private fun hasProjectWindow(): Boolean = ProjectManager.getInstance().openProjects.any { !it.isDisposed && !it.isDefault }

    /**
     * 这一页要用的 shell——**拼链与执行必须用同一个**。
     *
     * 收成一个函数而不是在两处各写一遍，是因为它现在有两个消费者：
     * [Remedy.chainFor] 按它的方言决定命令之间怎么串（PowerShell 5.1 不认 `&&`），
     * [runCommandLine] 按它的方言决定 shell 参数。两处若各取各的，
     * 就可能拿 POSIX 的 `&&` 交给 PowerShell 去跑——那是一屏解析错误、一条命令都不跑。
     *
     * 本身不做任何取舍：平台判断作为实参交给 [resolveShell]，那是被真调用测试钉住的纯函数。
     */
    private fun remedyShell(): String =
        resolveShell(
            System.getenv("SHELL"),
            isWindows = SystemInfo.isWindows,
            configuredShell = service<TerminalOptionsProvider>().shellPath,
        )

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
     * 「启用」，结果闪一下就没了。显式写 false 消除的是对一项**用户设置**的依赖，
     * 不只是覆盖一个默认值。
     *
     * **imux 自己仍然一个字节都不往用户文件里写。** `claude plugin install` 会改
     * `~/.claude/settings.json`，但改它的是 claude 这个 CLI，imux 只是替用户敲了那行字
     * ——敲在一个用户看得见、随时能打断的终端里。README 那句承诺因此继续成立。
     *
     * **开标签之前必须先把终端工具窗口显示出来**，而 `deferSessionStartUntilUiShown`
     * 反过来**必须留给平台默认值 `true`**。这一句是上一轮的反话，照实重写。
     *
     * 上一轮的诊断只对了一半：262 的 `TerminalToolWindowTabBuilderImpl` 构造里这个字段
     * 默认确实是 `true`（`iconst_1 / putfield`），语义确实是「等 UI 真正显示出来再启动
     * 会话」。错在结论——写死 `false` 只是把「不跑」换成了「跑了但没人收」，用户看到的
     * 仍然是通体空白的标签。这一次有 `idea.log` 作证，不是推断：
     *
     * ```
     * #c.i.t.f.s.StateAwareTerminalSession - Failed to emit output to the collector
     *   in 3 seconds, is there a connection problem? Terminating the output flow.
     * ```
     *
     * 那句话出自 `StateAwareTerminalSession.getOutputFlow()`，字节码上核过它是
     * `channelFlow { incrementalUpdateFlow.collect { withTimeout(3.seconds) { send(it) } } }`，
     * 而 `catch (TimeoutCancellationException)` 只 `LOG.info(…)` 就让 producer 块正常
     * 返回——channel 随之关闭，唯一的下游（`TerminalSessionController.handleEvents`，
     * 它把每一批事件 `withContext(Dispatchers.EDT + ModalityState.any())` 交给文档）
     * 收到的是「流结束了」。**平台里没有任何一处会重新订阅**。也就是说这不是一次卡顿，
     * 是一次性的、不可逆的掐断：3 秒之内没接上，这个标签页此后永远空白。
     *
     * 会走到那 3 秒，是因为 `deferSessionStartUntilUiShown(false)` 让会话在**前端还没
     * 具象化**的时候就跑了起来。`TerminalToolWindowTabsManagerImpl.createTab` 的顺序是
     * `createTerminalViewAndStartSession(builder)` **在前**、`doCreateTab(view)` 在后，
     * 于是写死 false 时进程比「把这个标签放进工具窗口」还早开始吐字节。
     *
     * 而工具窗口那一头，平台**不会**替我们把它显示出来：`addTabToToolWindow` 里只有
     * `if (requestFocus && !toolWindow.isActive) toolWindow.activate(Runnable { select() }, false, false)`，
     * 且 `ToolWindowImpl.isActive` 读的是 `toolWindowManager.activeToolWindowId`，
     * 后者的实现第一句就是遍历 pane 找 `frame.isActive()`——**设置对话框拿着焦点时，
     * 项目主窗口不是 active window，这个判据恒为假**。于是选中新标签的那一句
     * `select()` 被塞进 `ToolWindowManagerImpl` 的 `Dispatchers.EDT`（那次 launch
     * **没有** `ModalityState.any()`，取的是 nonModal）里晚一拍才跑。
     *
     * 所以这一轮改成：**自己先 `activate` 终端工具窗口，再建标签，会话交回给平台的
     * `initOnShow` 去启动**。`scheduleSessionStart` 在 flag 为 `true` 时走的正是
     * `UiScopeKt.initOnShow(view.component, "Terminal Session start", NonCancellable) { … }`
     *——会话在组件**真的显示出来的那一刻**启动，收集器早已在位，3 秒预算根本不会被
     * 触发。附带的好处也在同一条分支上：只有 flag 为 `true` 时
     * `prepareStartupOptions` 才会 `await` `TerminalUiUtils.getComponentSizeInitializedFuture(component)`
     * 并把**真实**网格尺寸写进 `initialTermSize`；写死 false 的那一版连初始尺寸都没有。
     *
     * `activate(null, false)` 的两个实参都是有讲究的：第二个是 `autoFocusContents`，
     * 传 false 表示「显示出来但别抢键盘焦点」——真正把焦点带过去的是 builder 上那句
     * `requestFocus(true)`，经由 `select()` 的 `setSelectedContent(content, true)`。
     * 单参重载 `activate(runnable, autoFocusContents)` 内部补的第三个实参 `forced` 是
     * `iconst_1`，即强制激活，正是我们要的。
     *
     * **设置对话框是模态时会怎样**：262 的 `ide.ui.non.modal.settings.window`
     * 默认 `true`（`intellij.idea.ultimate.customization.jar` 里那条 `advancedSetting`
     * 上写着 `default="true"`），本改动的判据也是在非模态下成立的。用户若把它关掉，
     * `activate` 的同步部分（`showToolWindowImpl`）照样把工具窗口显示出来——它不经过
     * `LaterInvocator`，不受模态影响；但平台那句被推迟的 `select()` 是 nonModal 模态的
     * EDT 任务，会**一直等到对话框关闭**才执行。后果是：标签在对话框开着时不会被选中、
     * 会话也就不会启动，**用户关掉对话框之后它才开始跑**。慢，但不空——因为
     * `initOnShow` 从不「错过」时机，它是注册在组件上的，而写死 false 那一版恰恰是
     * 在这个窗口期里把输出流永久掐断的。宁可晚几秒，不要永久空白。
     *
     * 开标签**之前**先把这一行标成「进行中」：新标签会抢焦点，页面这一刻已经不在用户
     * 眼前了；等回来时它必须已经变了样，而不是和点之前一模一样。
     *
     * 改的是**这一行**，不是整页。从前这里写的是 `lastReport?.let(::showReport)`
     *——为了让一行改个字，把三个分组 54 行整棵组件树重建一遍。用户原话
     * 「点击激活就会刷新设置页，体验不好」：整页闪一下、滚动位置回到顶部，
     * 而他刚点的那一行多半已经滚出可视区。
     *
     * 开标签**必须**包在 `runCatching` 里，且失败时把标记撤回来。标记写在建标签之前
     * （那是对的，否则抢焦点之后才改就晚了），于是一旦 `createTab()` 抛异常，那一行会
     * 永久停在「正在启用…」、按钮永久禁用——而 [refresh] 从不清 [running]（那是刻意的：
     * 真在跑的安装不该被一次「重新检测」清掉），用户只能关掉整个设置对话框才能复位。
     *
     * 整段函数体被 `ImuxLspUiSourceTest` 逐字节钉住。它和 [runRemedyButton] 一样是
     * 「一件事」的形状，而这里每一种缺陷都是加法：在标记与开标签之间插一句
     * `running.remove(key)`，「点下立刻变进行中」当场失效；在末尾之前插一句 `return`，
     * 自动重新探测整个失效——两种写法下三条 `contains` 断言全部照常命中。
     */
    private fun runInTerminal(
        key: String,
        command: String,
        event: ActionEvent,
    ) {
        // 渲染时 hasProjectWindow() 已经确认过有项目开着，这里再判一次是因为 262 的设置
        // 窗口是**非模态**的：从渲染到点击之间，用户完全可以把那个项目关掉。
        val project = targetProject(event)
        if (project == null) {
            LOG.warn("没有可用的项目窗口，无法执行：$command")
            return
        }
        running[key] = ENABLING_STATUS_KEY
        refreshRow(key, event)
        val tab =
            runCatching {
                ToolWindowManager
                    .getInstance(project)
                    .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
                    ?.activate(null, false)
                TerminalToolWindowTabsManager
                    .getInstance(project)
                    .createTabBuilder()
                    .workingDirectory(project.basePath ?: System.getProperty("user.home"))
                    .shellCommand(runCommandLine(remedyShell(), command))
                    .tabName(runTabName(ImuxBundle.message(ENABLE_ACTION_KEY), command))
                    .requestFocus(true)
                    .closeOnProcessTermination(false)
                    .createTab()
            }.onFailure {
                LOG.warn("开终端标签失败，撤回这一行的进行中标记：$command", it)
                running.remove(key)
                refreshRow(key, event)
            }.getOrNull() ?: return
        refreshWhenFinished(key, tab.view)
    }

    /**
     * 把**一行**改成它此刻该有的样子——整页一个组件都不重建。
     *
     * 三件东西各有各的取法，刻意不走同一个映射表：
     *
     * - **被点的那个按钮**从 `ActionEvent.source` 现取。它就是用户刚按下去的那一个，
     *   不必存、也不会认错行。这一句必须排在查表**之前**：组级行在 [rowCells] 里查不到，
     *   排到后面的话组级按钮永远不会被禁用。
     * - **状态图标与状态文案**从 [rowCells] 取组件，值原样回头问 [rowIcon] / [statusText]。
     *   那两个函数第一件事就是查 [running]，于是「标记进行中」与「撤回标记」两个方向
     *   共用同一段代码——这里绝不能再写一次「进行中该显示成什么」，那就是第二处判断，
     *   而这一页在第二处判断上栽过不止一次。
     *
     * **已知缺口**：组级修复行（pi 没装 pi-lens、Codex 没挂 MCP）没有 [rowCells]
     * 条目，因此执行期间只禁用按钮，不显示“正在启用”。要补齐该状态，组级行也需要保存
     * 可更新的文本组件；当前的 `?: return` 仅允许按钮更新后安全结束。
     *
     * `component.isEnabled` 是直接改 Swing 组件，**绕过了 UI DSL 的 `Cell.enabled()` 记账**。
     * 本页安全，因为没有任何 `Panel.enabled()` / `enabledIf()` 会在之后重新下发一遍父级
     * 使能状态，把这里的改动覆盖掉。哪天页面上长出那种链式使能，这一行会静默失效——
     * 那时应该把 Cell 也存进 [RowCells]，而不是在这里加补丁。
     *
     * 两个调用点（点击、开标签失败回滚）都在 EDT 上：按钮的 ActionListener 与它同步的
     * `runCatching` 失败分支。所以这里不必再 `invokeLater`，也不该——多绕一圈就意味着
     * 「点下去那一刻立刻变样」变成了「下一轮事件循环才变样」。
     */
    private fun refreshRow(
        key: String,
        event: ActionEvent,
    ) {
        (event.source as? JComponent)?.isEnabled = !running.containsKey(key)
        val cells = rowCells[key] ?: return
        cells.icon.icon = rowIcon(cells.finding, cells.agentType)
        cells.status.text = statusText(cells.finding, cells.agentType)
    }

    /**
     * 等这条命令跑完，再整体重新探测一次。
     *
     * 这是用户那句「激活后，就状态应该变了啊」的正面回答。从前这里什么都不做，理由是
     * 「命令在终端里异步跑，我们不知道它什么时候结束，猜一个时机只会给出更假的信息」
     * ——理由本身没错，错在结论：262 的 `TerminalView.sessionState` **不用猜**，
     * 它会明确走到 [TerminalViewSessionState.Terminated]。
     *
     * **这一条是整段逻辑的前提，所以在 262 的字节码上核过，不是推断的**：
     * `TerminalViewImpl` 的构造里注册了
     * `controller.addTerminationCallback(scope.asDisposable()) { mutableSessionState.value = Terminated }`，
     * 而 `TerminalSessionController.fireSessionTerminated()` 只在它从后端事件流里收到
     * `TerminalSessionTerminatedEvent` 时才触发——那是**会话/进程**事件，与标签页开着还是
     * 关着无关。反向佐证在同一份字节码里：`TerminalToolWindowTabsManagerImpl.doCreateTab`
     * 只有当 `closeOnProcessTermination == true` 时才起一个协程收 `sessionState`，
     * 收到 `Terminated` 就关掉那个 Content。也就是说平台**自己**拿这个状态当「进程结束了」
     * 的信号；若它只在关标签时才到，那个特性根本无法成立。
     * 所以本页显式写的 `closeOnProcessTermination(false)` 只是让平台不去自动关标签，
     * 不影响状态何时翻转。项目里 `TerminalHost.closeTabWhenTerminated` 早就依赖同一条性质。
     *
     * 三条边界，每一条都能让那一行永远停在「正在启用…」：
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
    private fun refreshWhenFinished(
        key: String,
        view: TerminalView,
    ) {
        val pageScope = scope ?: return
        pageScope.launch {
            val terminated =
                launch {
                    view.sessionState.first { it is TerminalViewSessionState.Terminated }
                }
            val closed =
                view.coroutineScope.coroutineContext.job
                    .invokeOnCompletion { terminated.cancel() }
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
        val fromDialog =
            (event.source as? Component)
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
     * [running] 排在最前面：这一行正在跑命令时，说的必须是「正在启用…」，而不是那条
     * 还没被推翻的旧状态。用户点下按钮之后盯着的就是这一列——它不动，用户就认为点击
     * 没生效，然后再点一次。查表而不是 `if`，是为了让这一列的取值只有一条路：
     * 「有没有在跑」与「静态状态说什么」共用同一个「取键 &#8594; 过 bundle」。
     *
     * 壳里不得再出现任何按 [LspStatus] 的判断。这不是洁癖：状态与文案的对应搬进
     * `LspStatusPresentation` 正是为了让它能被真正调用、被行为测试钉住；只要这里
     * 补一句 `if (status == X) return message(Y)`，那些行为测试就全部失效，
     * 而「文案字面量还在源码里」的文本断言一条都拦不住——这一类缺陷已经复活过两次。
     *
     * 文字长度：最长的是俄语的 MISSING_BINARY（约 30 字符），加上最长的语言名
     * `TypeScript/JavaScript`（21 字符）仍远在会撑宽对话框的量级之下，用 `label` 安全。
     * 若将来有译文明显变长，这一列必须改用 `comment` 或 `text`——见类顶部的折行教训。
     */
    private fun statusText(
        finding: LanguageFinding,
        agentType: AgentType,
    ): String {
        val key =
            running[runRowKey(agentType, finding.language)]
                ?: statusMessageKey(finding.status)
                ?: return readyServerText(finding.language, agentType)
        return ImuxBundle.message(key)
    }

    /**
     * 一行最左边那个图标。
     *
     * 正在跑命令时换成进行中的图标，理由与 [statusText] 完全相同：那一行必须整体改变
     * 面貌，只换文字不换图标的话，一列绿勾/黄叹号里混着一句「正在启用…」，
     * 反而像是显示出错了。
     *
     * 用**静态**帧而不是 `AnimatedIcon.Default`，理由只有一条、也只需要一条：
     * 后者不在 [AllIcons] 里，而本项目约定图标只取 AllIcons。这一行本来就会在几秒到
     * 几分钟内被一次完整重新探测覆盖掉，静态帧足够表达「这一格和别人不一样」。
     *
     * 取 8 帧 spinner 中段的 [AllIcons.Process.Step_4] 而不是首帧 `Step_1`：
     * 代码评审在真机上看到 `Step_1` 近乎空心圆，静态摆着看不出是「在转」。
     */
    private fun rowIcon(
        finding: LanguageFinding,
        agentType: AgentType,
    ): Icon {
        val inProgress = running.containsKey(runRowKey(agentType, finding.language))
        return if (inProgress) AllIcons.Process.Step_4 else statusIcon(finding.status)
    }

    /**
     * 状态图标——**薄壳**：语义类别由 [statusIconKind] 决定，这里只负责把类别换成
     * [AllIcons] 的常量，不自绘、也不再按 [LspStatus] 判断（理由同 [statusText]）。
     *
     * 下面这四条对应关系**同样被测试钉住**，改动前请想清楚。[statusIconKind] 那边的
     * 「哪些状态算警告」约束的是枚举值的归属，管不到这一层：把 `OK` 这一行改成
     * `Warning`，那条不变量原封不动全绿，而三个分组里所有就绪的语言、外加 pi 组那批
     * 「由 pi-lens 提供」的，在界面上集体挂起黄色警告牌——用户看见的是图标，不是枚举常量。
     *
     * 从前这里有第五条 `INFO -> Information`，只服务 `AUTO_MANAGED`。那条随本轮一起
     * 删掉了：`AUTO_MANAGED` 现在与 `READY` 同为 `OK`（判据是用户视角——绿 = 我不用做
     * 任何事），`INFO` 于是没有任何状态映到它，枚举值一并删除。
     */
    private fun statusIcon(status: LspStatus): Icon =
        when (statusIconKind(status)) {
            StatusIconKind.OK -> AllIcons.General.InspectionsOK
            StatusIconKind.WARNING -> AllIcons.General.Warning
            StatusIconKind.NEUTRAL -> AllIcons.General.Note
            StatusIconKind.QUESTION -> AllIcons.General.QuestionDialog
        }

    /**
     * 一行里**可以就地改**的那点东西：两个组件，加上重新算出「该显示成什么」所需的输入。
     *
     * 存 [finding] 与 [agentType] 而不是存两个现成的值（比如「原来的图标」「进行中的图标」），
     * 是为了让更新只有一条路：回头调 [rowIcon] / [statusText]，它们会先查 [running]。
     * 存现成值的话，「切到进行中」与「撤回」必然变成两套赋值，而那两套之间的任何不一致
     * 都只有真机点一次才看得见。
     */
    private class RowCells(
        val finding: LanguageFinding,
        val agentType: AgentType,
        val icon: JLabel,
        val status: JLabel,
    )

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
    private class CappedHeightView(
        view: JComponent,
    ) : JPanel(BorderLayout()),
        Scrollable {
        init {
            add(view, BorderLayout.CENTER)
            isOpaque = false
        }

        override fun getPreferredScrollableViewportSize(): Dimension =
            Dimension(preferredSize.width, minOf(preferredSize.height, JBUI.scale(MAX_VISIBLE_HEIGHT)))

        override fun getScrollableUnitIncrement(
            visibleRect: Rectangle,
            orientation: Int,
            direction: Int,
        ): Int = JBUI.scale(UNIT_SCROLL)

        override fun getScrollableBlockIncrement(
            visibleRect: Rectangle,
            orientation: Int,
            direction: Int,
        ): Int = visibleRect.height

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
         * 全是 `c` / `cpp` 这类标识符）。撞上的话，用户点一次 pi 那一组的「启用」，
         * 某门语言会跟着假装自己在跑。
         */
        const val GROUP_ROW = "/group remedy"

        /**
         * 合并「跑完了要重新探测」请求的时间窗，毫秒。
         *
         * 只需要盖住「几个标签几乎同时结束」这一种情况，不是给用户看的延迟——
         * 取到秒级的话，用户会先看见一行已经不再「正在启用…」、状态却还是旧的表。
         */
        const val REFRESH_DEBOUNCE_MS = 300L
    }
}
