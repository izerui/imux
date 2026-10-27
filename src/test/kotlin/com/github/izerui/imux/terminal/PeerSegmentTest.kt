package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.peer.PeerProgressEvent
import com.github.izerui.imux.peer.PeerProgressKind
import com.github.izerui.imux.peer.PeerProgressSnapshot
import com.github.izerui.imux.peer.PeerStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class PeerSegmentTest {
    private fun status(
        running: Boolean = false,
        progress: PeerProgressSnapshot? = null,
        passed: Boolean = false,
    ) = PeerStatus(AgentType.CODEX, running, progress, passed)

    @Test
    fun `未绑定时是空闲`() {
        assertEquals(PeerSegmentKind.IDLE, peerSegmentModel(null, nowMillis = 0).kind)
    }

    @Test
    fun `已绑定且未在审查时是结对中`() {
        val model = peerSegmentModel(status(), nowMillis = 0)
        assertEquals(PeerSegmentKind.READY, model.kind)
        assertEquals(AgentType.CODEX.displayName, model.agentName)
    }

    @Test
    fun `审查中带轮次与耗时，优先于上一轮通过`() {
        val progress = PeerProgressSnapshot(2, 1_000, 3, PeerProgressEvent(PeerProgressKind.STARTING))
        val model = peerSegmentModel(status(running = true, progress = progress, passed = true), nowMillis = 19_500)
        assertEquals(PeerSegmentModel(PeerSegmentKind.RUNNING, AgentType.CODEX.displayName, 2, 18), model)
    }

    @Test
    fun `审查刚开始还没有进度时轮次为零`() {
        assertEquals(0, peerSegmentModel(status(running = true), nowMillis = 0).round)
    }

    @Test
    fun `时钟回拨时耗时不为负`() {
        val progress = PeerProgressSnapshot(1, 5_000, 0, PeerProgressEvent(PeerProgressKind.STARTING))
        assertEquals(0L, peerSegmentModel(status(running = true, progress = progress), nowMillis = 1_000).elapsedSeconds)
    }

    @Test
    fun `上一轮通过时显示无新反馈`() {
        assertEquals(PeerSegmentKind.PASSED, peerSegmentModel(status(passed = true), nowMillis = 0).kind)
    }
}
