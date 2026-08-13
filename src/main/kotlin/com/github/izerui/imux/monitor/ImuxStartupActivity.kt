package com.github.izerui.imux.monitor

import com.github.izerui.imux.session.PiReportEndpointCache
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * 项目一打开就启动会话监听，不等用户点开工具窗口。
 *
 * 工具窗口的内容是懒加载的：不这么做，一个从没展开过 imux 面板的项目就收不到任何
 * 轮次完成提醒——而同时开着好几个项目窗口时，这种情况很常见，且用户不会察觉。
 */
class ImuxStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        // 顺手把 pi 的上报端点算出来。算它要等内置 HTTP 服务起来，而唯一的用处
        // 在 TerminalHost.createView 里——那是 EDT 路径，现算就会卡住 UI。
        // 这里是后台协程，且远早于用户点开任何会话。
        PiReportEndpointCache.warmUp()
        SessionMonitor.getInstance(project).start()
    }
}
