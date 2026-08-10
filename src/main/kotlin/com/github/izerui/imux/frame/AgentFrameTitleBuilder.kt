package com.github.izerui.imux.frame

import com.github.izerui.imux.monitor.SessionMonitor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.impl.PlatformFrameTitleBuilder

/**
 * 把会话状态写进项目窗口标题：有未读加 `✳ ` 前缀，有会话在跑加「（N 个运行中）」后缀。
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
            unread = monitor?.hasUnread() == true,
            runningCount = monitor?.runningIds?.size ?: 0,
        )
    }

    /**
     * 空串而不是 null：平台的 `appendTitlePart` 对 null 与空白一视同仁地早退，
     * 连同前面的 ` – ` 分隔符一起跳过，不会留下孤零零的破折号。
     *
     * 同步与异步两个重载都要覆写——平台按调用点分走两条路径。
     */
    override fun getFileTitle(project: Project, file: VirtualFile): String = ""

    override suspend fun getFileTitleAsync(project: Project, file: VirtualFile): String = ""

    companion object {

        /**
         * 与会话列表、标签角标用的 `AllIcons.General.Modified` 同形：那是三条间隔
         * 60° 的线构成的六辐星号（`general/modified.svg`，#6E6E6E / #AFB1B3）。
         *
         * 窗口标题只能是纯文本，放不进 Icon 也无法着色，只能挑个形状最接近的字符。
         * 好在它的颜色跟随标题文字，深色主题下同样是浅灰，语义正好对上。
         */
        const val UNREAD_PREFIX = "✳ "

        /**
         * 前缀靠 [String.startsWith] 判断就够了，因为 `✳ ` 是定值；后缀嵌着会变的
         * 数字，重复装饰会叠成「（2 个运行中）（3 个运行中）」，只能先剥离再追加。
         *
         * 正常路径下 `base` 来自平台的 `getProjectTitle`，本就干净，这里永远空转。
         * 但标题会被反复重算，前缀已经付了这份保险，后缀没理由不付。
         */
        private val RUNNING_SUFFIX = Regex("（\\d+ 个运行中）$")

        internal fun decorate(base: String, unread: Boolean, runningCount: Int = 0): String {
            val stripped = base.replace(RUNNING_SUFFIX, "")
            val withSuffix =
                if (runningCount > 0) "$stripped（$runningCount 个运行中）" else stripped
            return if (unread && !withSuffix.startsWith(UNREAD_PREFIX)) {
                "$UNREAD_PREFIX$withSuffix"
            } else {
                withSuffix
            }
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
