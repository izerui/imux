package com.github.izerui.imux.frame

import com.github.izerui.imux.monitor.SessionMonitor
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.impl.PlatformFrameTitleBuilder

/**
 * 给有未读会话的项目窗口标题加一个星号前缀。
 *
 * 未读原本只在项目窗口**内部**可见（标签图标、会话列表）。macOS 上把多个项目窗口
 * 合并成标签栏后，标签上显示的是各窗口的 NSWindow title，切到别的项目就看不出
 * 哪个项目跑完了活。窗口标题由平台的 [PlatformFrameTitleBuilder] 生成，
 * 覆盖它是把未读状态送出窗口的唯一稳妥途径。
 *
 * 只覆盖项目名那一段：标签宽度有限，标题末尾会被省略号截掉，只有开头能保证可见。
 */
class UnreadFrameTitleBuilder : PlatformFrameTitleBuilder() {

    override fun getProjectTitle(project: Project): String =
        decorate(super.getProjectTitle(project), hasUnread(project))

    companion object {

        /**
         * 与会话列表、标签角标用的 `AllIcons.General.Modified` 同形：那是三条间隔
         * 60° 的线构成的六辐星号（`general/modified.svg`，#6E6E6E / #AFB1B3）。
         *
         * 窗口标题只能是纯文本，放不进 Icon 也无法着色，只能挑个形状最接近的字符。
         * 好在它的颜色跟随标题文字，深色主题下同样是浅灰，语义正好对上。
         */
        const val UNREAD_PREFIX = "✳ "

        internal fun decorate(base: String, unread: Boolean): String =
            if (unread && !base.startsWith(UNREAD_PREFIX)) "$UNREAD_PREFIX$base" else base

        /**
         * 这个构建器对 IDE 里**所有**项目窗口生效，包括从没开过 AI 会话的项目。
         * 所以只能问「服务已经在了吗」，不能用 `SessionMonitor.getInstance(project)`
         * ——那会让平台每渲染一次标题就把项目服务连同它的轮询协程创建出来，
         * 用户没用过 imux 却被扫一遍会话目录。
         *
         * 标题也可能在项目关闭过程中被重算，所以先挡一道 dispose。
         */
        private fun hasUnread(project: Project): Boolean =
            !project.isDisposed &&
                project.getServiceIfCreated(SessionMonitor::class.java)?.hasUnread() == true
    }
}
