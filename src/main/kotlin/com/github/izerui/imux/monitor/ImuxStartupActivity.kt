package com.github.izerui.imux.monitor

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
        SessionMonitor.getInstance(project).start()
    }
}
