package com.github.izerui.imux.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeStackTest {
    private fun notice(key: String, tone: NoticeTone) = Notice(key, tone, key)

    @Test
    fun `多条提示按黄蓝绿叠放`() {
        val stack = NoticeStack()
        stack.flash(NoticeSource.TALK, notice("talk", NoticeTone.SUCCESS))
        stack.setPersistent(NoticeSource.PAIR, notice("pair", NoticeTone.INFO))
        stack.setPersistent(NoticeSource.TRAIL, notice("trail", NoticeTone.WARNING))
        assertEquals(listOf("trail", "pair", "talk"), stack.visible().map { it.notice.key })
    }

    @Test
    fun `同等严重程度按手机结对轨迹讨论排`() {
        val stack = NoticeStack()
        stack.setPersistent(NoticeSource.TRAIL, notice("trail", NoticeTone.WARNING))
        stack.setPersistent(NoticeSource.PHONE, notice("phone", NoticeTone.WARNING))
        assertEquals(listOf("phone", "trail"), stack.visible().map { it.notice.key })
    }

    @Test
    fun `常驻提示盖过同来源的结果提示`() {
        val stack = NoticeStack()
        stack.flash(NoticeSource.PAIR, notice("passed", NoticeTone.SUCCESS))
        stack.setPersistent(NoticeSource.PAIR, notice("running", NoticeTone.INFO))
        assertEquals(listOf("running"), stack.visible().map { it.notice.key })
        stack.setPersistent(NoticeSource.PAIR, null)
        assertTrue("进入新状态后旧的结果提示应作废", stack.visible().isEmpty())
    }

    @Test
    fun `收起的常驻提示在状态不变时不再出现`() {
        val stack = NoticeStack()
        stack.setPersistent(NoticeSource.TRAIL, notice("trail:failed:超时", NoticeTone.WARNING))
        stack.dismiss(NoticeSource.TRAIL)
        stack.setPersistent(NoticeSource.TRAIL, notice("trail:failed:超时", NoticeTone.WARNING))
        assertTrue(stack.visible().isEmpty())
    }

    @Test
    fun `收起后状态离开再回到同样的问题时重新提示`() {
        // 真实路径：轨迹失败 A → 点 × → 重试进入生成中（没有常驻提示）→ 又是同一个错误 A
        val stack = NoticeStack()
        stack.setPersistent(NoticeSource.TRAIL, notice("trail:failed:超时", NoticeTone.WARNING))
        stack.dismiss(NoticeSource.TRAIL)
        stack.setPersistent(NoticeSource.TRAIL, null)
        stack.setPersistent(NoticeSource.TRAIL, notice("trail:failed:超时", NoticeTone.WARNING))
        assertEquals(listOf("trail:failed:超时"), stack.visible().map { it.notice.key })
    }

    @Test
    fun `收起后直接换成另一个问题时重新提示`() {
        val stack = NoticeStack()
        stack.setPersistent(NoticeSource.PHONE, notice("phone:problem:1", NoticeTone.WARNING))
        stack.dismiss(NoticeSource.PHONE)
        stack.setPersistent(NoticeSource.PHONE, notice("phone:problem:2", NoticeTone.WARNING))
        assertEquals(listOf("phone:problem:2"), stack.visible().map { it.notice.key })
    }

    @Test
    fun `一个来源的状态离开不影响其它来源的关闭记录`() {
        val stack = NoticeStack()
        stack.setPersistent(NoticeSource.PHONE, notice("phone:problem:1", NoticeTone.WARNING))
        stack.dismiss(NoticeSource.PHONE)
        stack.setPersistent(NoticeSource.TRAIL, null)
        stack.setPersistent(NoticeSource.PHONE, notice("phone:problem:1", NoticeTone.WARNING))
        assertTrue(stack.visible().isEmpty())
    }

    @Test
    fun `结果提示倒数满后收起`() {
        val stack = NoticeStack(ttlMillis = 1_000)
        stack.flash(NoticeSource.PAIR, notice("passed", NoticeTone.SUCCESS))
        assertFalse(stack.tick(600))
        assertEquals(0.4f, stack.visible().single().remainingFraction, 0.001f)
        assertTrue(stack.tick(400))
        assertTrue(stack.visible().isEmpty())
    }

    @Test
    fun `被常驻提示挡住的结果提示不倒数`() {
        val stack = NoticeStack(ttlMillis = 1_000)
        stack.flash(NoticeSource.PAIR, notice("passed", NoticeTone.SUCCESS))
        stack.setPersistent(NoticeSource.PAIR, notice("failed", NoticeTone.WARNING))
        stack.dismiss(NoticeSource.PAIR)
        assertFalse(stack.hasCountdown())
        assertFalse(stack.tick(5_000))
    }

    @Test
    fun `收起结果提示只去掉这一条`() {
        val stack = NoticeStack()
        stack.flash(NoticeSource.PAIR, notice("passed", NoticeTone.SUCCESS))
        stack.flash(NoticeSource.TALK, notice("talk", NoticeTone.SUCCESS))
        stack.dismiss(NoticeSource.PAIR)
        assertEquals(listOf("talk"), stack.visible().map { it.notice.key })
    }

    @Test
    fun `新的结果提示换成另一行`() {
        val stack = NoticeStack()
        stack.flash(NoticeSource.PAIR, notice("passed", NoticeTone.SUCCESS))
        val first = stack.visible().single().id
        stack.flash(NoticeSource.PAIR, notice("passed", NoticeTone.SUCCESS))
        assertTrue(first != stack.visible().single().id)
    }
}
