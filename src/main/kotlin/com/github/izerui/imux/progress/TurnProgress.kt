package com.github.izerui.imux.progress

/** 模型对本轮工作的估计，不是步骤计数或剩余耗时。 */
internal data class TurnProgress(val percent: Int, val message: String) {
    init {
        require(percent in 0..100)
        require(message.isNotBlank() && message.length <= 200)
    }
}
