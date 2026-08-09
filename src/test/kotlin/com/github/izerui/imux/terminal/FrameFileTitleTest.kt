package com.github.izerui.imux.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 标签页标题跟上了新会话标题，IDE 窗口标题却还挂着旧的。
 *
 * `updateFilePresentation` 只重画标签页；窗口标题的文件名段平台只在 selectionChanged
 * 时才重算，而 CLI 事后改标题不产生任何标签页切换事件——于是窗口标题一直停在旧值，
 * 直到用户手动切一次标签页才追上。
 */
class FrameFileTitleTest {

    @Test
    fun `改名的正是当前打开的标签页时要改窗口标题`() {
        assertEquals("分析工程结构", frameFileTitleAfterRename(isSelected = true, newTitle = "分析工程结构"))
    }

    /** 后台标签页改名不该把窗口标题抢过去——那上面写的是用户眼下正看的文件。 */
    @Test
    fun `后台标签页改名不动窗口标题`() {
        assertNull(frameFileTitleAfterRename(isSelected = false, newTitle = "分析工程结构"))
    }

    /** 空标题会把窗口标题刷成只剩项目名，那还不如留着旧的。 */
    @Test
    fun `空标题不覆盖窗口标题`() {
        assertNull(frameFileTitleAfterRename(isSelected = true, newTitle = ""))
    }
}
