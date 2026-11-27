package com.github.izerui.imux.progress

/** 已观察到的结束原因；不能作为验收成功的证明。 */
internal enum class ProgressEndReason { NORMAL, INTERRUPTED, FAILED, UNKNOWN }
