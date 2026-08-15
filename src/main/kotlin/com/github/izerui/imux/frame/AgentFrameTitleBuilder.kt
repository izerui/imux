package com.github.izerui.imux.frame

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.settings.PluginLanguage
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.impl.PlatformFrameTitleBuilder

/**
 * 把会话状态写进项目窗口标题，形如 `imux（1 个未读 · 2 个运行中）`。
 *
 * 两者原本只在项目窗口**内部**可见（标签图标、会话列表）。macOS 上把多个项目窗口
 * 合并成标签栏后，标签上显示的是各窗口的 NSWindow title，切到别的项目就看不出
 * 哪个项目跑完了活、哪个还在跑。窗口标题由平台的 [PlatformFrameTitleBuilder] 生成，
 * 覆盖它是把这些状态送出窗口的唯一稳妥途径。
 *
 * 同时把文件名那一段抹掉，标题就只剩「项目名 + 状态」。平台没有隐藏文件名的设置项:
 * `UISettings.fullPathsInWindowHeader` 只在全路径与短名之间切，
 * `ide.show.fileType.icon.in.titleBar` 管的是文件类型图标。覆写生成端是唯一途径——
 * 改用 `setFileTitle("")` 清空没用，平台每次 selectionChanged 都会用
 * [getFileTitleAsync] 的结果写回。
 *
 * 标题只能是纯文本，无法加粗或着色——状态的视觉强度以字符本身为上限。
 */
class AgentFrameTitleBuilder : PlatformFrameTitleBuilder() {
    override fun getProjectTitle(project: Project): String {
        val monitor = monitorOf(project)
        return decorate(
            super.getProjectTitle(project),
            unreadCount = monitor?.unreadCount() ?: 0,
            runningCount = monitor?.runningIds?.size ?: 0,
        )
    }

    /**
     * 空串而不是 null：平台的 `appendTitlePart` 对 null 与空白一视同仁地早退，
     * 连同前面的 ` – ` 分隔符一起跳过，不会留下孤零零的破折号。
     *
     * 同步与异步两个重载都要覆写——平台按调用点分走两条路径。
     */
    override fun getFileTitle(
        project: Project,
        file: VirtualFile,
    ): String = ""

    override suspend fun getFileTitleAsync(
        project: Project,
        file: VirtualFile,
    ): String = ""

    companion object {
        /**
         * 只匹配自己生成的三种形态，不能宽泛成「结尾的任意全角括号」——项目名本身
         * 就可能带括号（`我的项目（旧）`），那会被连着吃掉。
         *
         * 括号里嵌着会变的数字，重复装饰会叠成「（1 个未读）（2 个未读）」，
         * 只能先剥离再追加。正常路径下 `base` 来自平台的 `getProjectTitle` 本就干净，
         * 这里永远空转；但标题会被反复重算，这份保险省不得。
         */
        private val STATUS_SUFFIX =
            Regex(
                " \\((?:\\d+ (?:unread|running)(?: · \\d+ running)?|" +
                    "\\d+ 个(?:未读|运行中)(?: · \\d+ 个运行中)?)\\)$",
            )

        /**
         * 未读排在运行中之前：未读是「要你去看」的强提示，运行中是「还在跑、不用管」
         * 的弱信息，强的靠前。
         */
        internal fun decorate(
            base: String,
            unreadCount: Int,
            runningCount: Int,
            language: PluginLanguage = ImuxBundle.currentLanguage(),
        ): String {
            val stripped = base.replace(STATUS_SUFFIX, "")
            val parts =
                buildList {
                    if (unreadCount > 0) {
                        add(ImuxBundle.message(language, "frame.status.unread", unreadCount))
                    }
                    if (runningCount > 0) {
                        add(ImuxBundle.message(language, "frame.status.running", runningCount))
                    }
                }
            return if (parts.isEmpty()) stripped else "$stripped (${parts.joinToString(" · ")})"
        }

        /**
         * 这个构建器对 IDE 里**所有**项目窗口生效，包括从没开过 AI 会话的项目。
         * 所以只能问「服务已经在了吗」，不能用 `SessionMonitor.getInstance(project)`
         * ——那会让平台每渲染一次标题就把项目服务连同它的轮询协程创建出来，
         * 用户没用过 imux 却被扫一遍会话目录。
         *
         * 标题也可能在项目关闭过程中被重算，所以先挡一道 dispose。
         */
        private fun monitorOf(project: Project): SessionMonitor? =
            if (project.isDisposed) {
                null
            } else {
                project.getServiceIfCreated(SessionMonitor::class.java)
            }
    }
}
