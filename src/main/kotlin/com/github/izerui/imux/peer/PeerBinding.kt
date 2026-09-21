package com.github.izerui.imux.peer

import com.github.izerui.imux.model.AgentType
import java.util.concurrent.atomic.AtomicLong

data class PeerBinding(
    val targetAgentType: AgentType,
    val task: String = "",
    val generation: Long = nextGeneration(),
) {
    companion object {
        private val counter = AtomicLong()
        private fun nextGeneration(): Long = counter.incrementAndGet()
    }
}

