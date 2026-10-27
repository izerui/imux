package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeUiSourceTest {
    private val configurable =
        SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/RobotsConfigurable.kt")
    private val dialog =
        SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/AddRobotDialog.kt")

    @Test
    fun `设置页添加机器人后调用 reset 刷新绑定`() {
        val addBody = configurable.bodyAfter(".setAddAction", '{')
        assertTrue(
            "添加对话框关闭后必须调用 reset() 以同步开关状态",
            configurable.compact(addBody).contains("reset()"),
        )
    }

    @Test
    fun `添加对话框显示已自动开启桥接提示`() {
        assertTrue(
            "ensureBridgeEnabled 之后必须引用 bridge.add.enabledBridge 文案",
            dialog.normalized.contains("bridge.add.enabledBridge"),
        )
    }

    @Test
    fun `创建机器人失败时显示错误并可重试`() {
        val createdBody = dialog.bodyAfter("private fun created(result: OnboardingStep.Succeeded)", '{')
        assertTrue(
            "created() 必须捕获异常并显示 bridge.add.failed",
            dialog.compact(createdBody).contains("bridge.add.failed"),
        )
        assertTrue(
            "created() 异常路径必须显示重试按钮",
            dialog.compact(createdBody).contains("retryButton.isVisible=true"),
        )
    }
}
