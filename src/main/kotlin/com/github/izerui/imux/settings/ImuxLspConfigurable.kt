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
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import java.awt.BorderLayout
import java.nio.file.Path
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

    override fun createPanel(): DialogPanel = panel {
        row {
            comment(ImuxBundle.message("settings.lsp.scope.note"))
        }
        row {
            button(ImuxBundle.message("settings.lsp.refresh")) { refresh() }
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
        showChecking()
        ApplicationManager.getApplication().executeOnPooledThread {
            val report = runCatching { diagnostics().run() }.getOrNull()
            ApplicationManager.getApplication().invokeLater(
                { if (report == null) showChecking() else showReport(report) },
                ModalityState.any(),
            )
        }
    }

    private fun diagnostics() = LspDiagnostics(
        userHome = Path.of(System.getProperty("user.home")),
        binaryProbe = ShellBinaryProbe(),
    )

    private fun showChecking() {
        content.removeAll()
        content.add(JBLabel(ImuxBundle.message("settings.lsp.checking")), BorderLayout.CENTER)
        content.revalidate()
        content.repaint()
    }

    private fun showReport(report: LspReport) {
        content.removeAll()
        content.add(
            panel {
                report.cliReports.forEach { cliReport ->
                    group(cliReport.agentType.displayName) { renderCli(cliReport) }
                }
            },
            BorderLayout.CENTER,
        )
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

        val gaps = cliReport.gaps
        if (gaps.isEmpty()) return
        row {
            icon(AllIcons.General.Warning)
            label(ImuxBundle.message("settings.lsp.gaps", gaps.size))
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

    private fun groupMessage(cliReport: CliReport): String =
        when (cliReport.agentType) {
            AgentType.CODEX -> ImuxBundle.message("settings.lsp.codex.mount")
            else -> ImuxBundle.message("settings.lsp.pi.auto")
        }

    private fun statusText(finding: LanguageFinding): String = when (finding.status) {
        LspStatus.MISSING_CONFIG -> ImuxBundle.message("settings.lsp.status.config")
        LspStatus.MISSING_BINARY -> ImuxBundle.message("settings.lsp.status.binary")
        LspStatus.UNKNOWN -> ImuxBundle.message("settings.lsp.status.unknown")
        LspStatus.READY -> ""
    }
}
