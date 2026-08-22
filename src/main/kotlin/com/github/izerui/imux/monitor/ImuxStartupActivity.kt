package com.github.izerui.imux.monitor

import com.github.izerui.imux.session.imuxTabPidDir
import com.github.izerui.imux.session.sweepTabPidFiles
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.SystemInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 项目一打开就启动会话监听，不等用户点开工具窗口。
 *
 * 工具窗口的内容是懒加载的：不这么做，一个从没展开过 imux 面板的项目就收不到任何
 * 轮次完成提醒——而同时开着好几个项目窗口时，这种情况很常见，且用户不会察觉。
 */
class ImuxStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        sweepStaleTabPidFilesOnce()
        val monitor = SessionMonitor.getInstance(project)
        try {
            monitor.restoreSavedTabs()
        } finally {
            if (!project.isDisposed) monitor.start()
        }
    }

    /**
     * 抹掉上次 IDE 崩溃退出留下的 pid 文件残留。
     *
     * 正常关标签页时 [com.github.izerui.imux.terminal.TerminalHost.closeSession] 会自己删；
     * 崩溃或强杀时没人删，而 pid 会被系统复用——残留会把一个毫不相干的新进程认成某个
     * 标签的 shell，正是「认错比不迁移更糟」的那一类错。
     *
     * **必须整个应用只做一次。** 这个 [ProjectActivity] 每开一个项目窗口就跑一遍，而
     * pid 文件目录是全应用共用的：第二个项目打开时再清扫一次，会把第一个窗口里活着的
     * 标签的 pid 文件一并抹掉，那些标签从此认不出会话漂移。
     *
     * 只在 Windows 上有这些文件，别的平台连目录都不会建。
     */
    private fun sweepStaleTabPidFilesOnce() {
        if (!SystemInfo.isWindows) return
        if (!swept.compareAndSet(false, true)) return
        sweepTabPidFiles(imuxTabPidDir())
    }

    private companion object {
        val swept = AtomicBoolean(false)
    }
}
