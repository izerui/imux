package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.progress.ProgressEndReason
import com.github.izerui.imux.progress.ProgressRecord
import com.github.izerui.imux.progress.ProgressSnapshot
import com.github.izerui.imux.progress.TurnProgress
import org.junit.Assert.*
import org.junit.Test

class SessionProgressNoticeTest {
    @Test
    fun `尚未上报时显示未知进度且不伪造百分比`() {
        val notice = checkNotNull(sessionProgressNotice("session", ProgressSnapshot(active = true)))
        assertEquals(ImuxBundle.message("progress.unknown"), notice.text)
        assertNotNull(notice.progress)
        assertNull(notice.progress?.percent)
        assertFalse(notice.closable)
        assertSame(com.intellij.ui.AnimatedIcon.Default.INSTANCE, notice.icon)
    }

    @Test
    fun `等待确认保留上报内容且满格仍等实际结束才收起`() {
        val record = ProgressRecord()
        record.report(40, "检查调用链")
        record.observe(active = true, waiting = true)
        val waiting = checkNotNull(sessionProgressNotice("session", record.snapshot()))
        assertEquals(ImuxBundle.message("progress.waiting") + " · 检查调用链", waiting.text)
        assertEquals(40, waiting.progress?.percent)
        assertSame(com.intellij.icons.AllIcons.Actions.Pause, waiting.icon)

        record.report(100, "检查完成")
        assertNotNull(sessionProgressNotice("session", record.snapshot()))
        record.observe(active = false)
        assertNull(sessionProgressNotice("session", record.snapshot()))
    }

    @Test
    fun `执行失败后移除运行进度`() {
        val record = ProgressRecord()
        record.report(30, "运行测试")
        record.observe(active = false, endReason = ProgressEndReason.FAILED)
        assertNull(sessionProgressNotice("session", record.snapshot()))
    }

    @Test
    fun `用户中断后移除运行进度`() {
        val record = ProgressRecord()
        record.report(30, "运行测试")
        record.observe(active = false, endReason = ProgressEndReason.INTERRUPTED)
        assertNull(sessionProgressNotice("session", record.snapshot()))
    }

    @Test
    fun `进度与手机异常及审查并列且结束只移除进度`() {
        val stack = NoticeStack()
        stack.setPersistent(NoticeSource.PHONE, Notice("phone", NoticeTone.WARNING, "连接失败"))
        stack.setPersistent(NoticeSource.PAIR, Notice("pair", NoticeTone.INFO, "正在审查"))
        stack.setPersistent(NoticeSource.PROGRESS, sessionProgressNotice("session",
            ProgressSnapshot(active = true, progress = TurnProgress(40, "检查调用链"))))

        assertEquals(listOf("phone", "pair", "progress:session"), stack.visible().map { it.id })
        assertFalse(stack.hasCountdown())
        stack.tick(20_000)
        assertEquals(3, stack.visible().size)

        stack.setPersistent(NoticeSource.PROGRESS, sessionProgressNotice("session", ProgressSnapshot()))
        assertEquals(listOf("phone", "pair"), stack.visible().map { it.id })
    }

    @Test
    fun `切换会话后不沿用旧提示身份与旧百分比`() {
        val stack = NoticeStack()
        stack.setPersistent(NoticeSource.PROGRESS, sessionProgressNotice("old",
            ProgressSnapshot(active = true, progress = TurnProgress(80, "旧任务"))))
        stack.setPersistent(NoticeSource.PROGRESS, sessionProgressNotice("new", ProgressSnapshot(active = true)))
        val shown = stack.visible().single()
        assertEquals("progress:new", shown.id)
        assertNull(shown.notice.progress?.percent)
        assertFalse(shown.notice.text.contains("旧任务"))
    }
}
