package com.github.izerui.imux.settings

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.lsp.CliReport
import com.github.izerui.imux.lsp.LanguageFinding
import com.github.izerui.imux.lsp.LspDiagnostics
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
import com.intellij.ui.dsl.builder.panel
import java.awt.BorderLayout
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

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
                label(groupMessage(cliReport))
            }
            renderRemedy(remedy)
            return
        }

        val ready = cliReport.ready
        val gaps = cliReport.gaps

        // 装了、没有前置修复、却一条语言结果都没有：Codex 挂了 pi-lens-mcp 但本机没装
        // pi（或 pi 没装 pi-lens）就是这个状态。不兜底的话这里会是个只有标题的空分组。
        if (ready.isEmpty() && gaps.isEmpty()) {
            row {
                icon(AllIcons.General.Information)
                label(ImuxBundle.message("settings.lsp.no.findings"))
            }
            return
        }

        if (ready.isNotEmpty()) {
            row {
                icon(AllIcons.General.InspectionsOK)
                // 已就绪的折叠成一行：体检表一啰嗦就没人看
                label(
                    ImuxBundle.message("settings.lsp.ready", ready.size) + "  " +
                        ready.joinToString(" · ") { it.language.displayName },
                )
            }
        }

        if (gaps.isEmpty()) return
        row {
            icon(AllIcons.General.Warning)
            label(ImuxBundle.message("settings.lsp.gaps", gaps.size))
        }
        // 只有 pi-lens 供能的两组需要这句：缺口只是那几门 toolchain-gated 语言，
        // 其余 36 种 pi-lens 会自己装。少了它，用户会把「待补充（5）」读成「只覆盖 5 门」。
        if (coveredByPiLens(cliReport.agentType)) {
            row { comment(ImuxBundle.message("settings.lsp.pi.auto")) }
        }
        indent {
            gaps.forEach { finding ->
                row {
                    label("${finding.language.displayName}  —  ${statusText(finding)}")
                }
                finding.remedy?.let { renderRemedy(it) }
            }
        }
    }

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
     * 所以两组共享同一句说明；Claude Code 用的是自己的官方插件，不适用。
     */
    private fun coveredByPiLens(agentType: AgentType): Boolean = when (agentType) {
        AgentType.PI, AgentType.CODEX -> true
        else -> false
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

    private fun statusText(finding: LanguageFinding): String = when (finding.status) {
        LspStatus.MISSING_CONFIG -> ImuxBundle.message("settings.lsp.status.config")
        LspStatus.MISSING_BINARY -> ImuxBundle.message("settings.lsp.status.binary")
        LspStatus.UNKNOWN -> ImuxBundle.message("settings.lsp.status.unknown")
        LspStatus.READY -> ""
    }

    private companion object {
        val LOG = logger<ImuxLspConfigurable>()
    }
}
