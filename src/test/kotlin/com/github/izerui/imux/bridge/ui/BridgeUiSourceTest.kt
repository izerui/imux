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
    fun `设置页添加机器人后刷新列表`() {
        val addBody = configurable.bodyAfter(".setAddAction", '{')
        assertTrue(
            "添加对话框关闭后刷新机器人列表",
            configurable.compact(addBody).contains("AddRobotDialog(null).show()refresh()"),
        )
    }

    @Test
    fun `机器人管理不再提供总开关或自动开启提示`() {
        assertTrue(!configurable.normalized.contains("bridge.settings.enable"))
        assertTrue(!dialog.normalized.contains("bridge.add.enabledBridge"))
        assertTrue(
            dialog.compact(dialog.bodyAfter("private fun startPairing(id: RobotId)", '{'))
                .contains("reconnectIfDown(id)")
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

class RobotsSettingsRecoverySourceTest {
    private val configurable = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/RobotsConfigurable.kt")
    private val dialog = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/AddRobotDialog.kt")

    @Test
    fun `设置页提供重新连接与配对`() {
        val text = configurable.compact(configurable.normalized)
        assertTrue(text.contains("ImuxBundle.message(\"bridge.settings.reconnect\")"))
        assertTrue(text.contains("RobotManager.getInstance().reconnect(row.id)"))
        assertTrue(text.contains("ImuxBundle.message(\"bridge.settings.pair\")"))
        assertTrue(text.contains("AddRobotDialog(null,pairRobotId=row.id).show()"))
    }

    @Test
    fun `设置页随机器人、状态与绑定变化刷新，不定时轮询；重建面板前先退订旧的`() {
        val body = configurable.compact(configurable.bodyAfter("override fun createPanel(): DialogPanel", '{'))
        assertTrue(body.indexOf("uiDisposable?.let(Disposer::dispose)") in 0 until body.indexOf("subscribe(SessionDecorationsListener.TOPIC,SessionDecorationsListener{refresh()})"))
        assertTrue(!configurable.normalized.contains("Timer("))
    }

    @Test
    fun `设置页开着时会话改名也刷新：订阅每个打开项目的会话列表变化，随面板一起退订`() {
        val body = configurable.compact(configurable.bodyAfter("override fun createPanel(): DialogPanel", '{'))
        assertTrue(body.contains("ProjectManager.getInstance().openProjects.filterNot{it.isDisposed}.forEach{project->SessionMonitor.getInstance(project).addListener(disposable){refresh()}}"))
    }

    @Test
    fun `配对入口续期配对码并等待认主`() {
        val body = dialog.compact(dialog.bodyAfter("private fun startPairing(id: RobotId)", '{'))
        assertTrue(body.contains("manager.renewPairCode(id)"))
        assertTrue(body.contains("awaitPaired(id)"))
    }

    @Test
    fun `对话框的等待认主交给 awaitPairing，按中枢版本等事件，认主后进入完成页`() {
        assertTrue(dialog.normalized.contains("@Volatile private var robotId: RobotId? = null"))
        val body = dialog.compact(dialog.bodyAfter("private suspend fun awaitPaired(id: RobotId)", '{'))
        assertTrue(body.contains("awaitPairing(revision=BridgeHub.getInstance().revision,now=System::currentTimeMillis,robot={manager.robot(id)},status={manager.statusOf(id)})"))
        assertTrue(body.contains("if(result==PairWait.PAIRED)ui{finish()}"))
    }

    @Test
    fun `配对码失效、过期或连接异常时说明原因，重新生成时一并重连`() {
        val wait = dialog.compact(dialog.bodyAfter("private suspend fun awaitPaired(id: RobotId)", '{'))
        assertTrue(
            "先投递到 EDT，再与页面此刻的显示比较",
            wait.contains("{wait,status->ui{pairView.offer(wait,status)}}")
        )
        val state = dialog.compact(dialog.bodyAfter("private fun renderPairState(", '{'))
        val show = dialog.compact(dialog.bodyAfter("private fun showPair(code: String)", '{'))
        assertTrue("重新生成后经显示状态记录改回等待", show.contains("pairView.show(PairWait.WAITING,null)"))
        assertTrue(state.contains("bridge.add.pair.invalidated"))
        assertTrue(state.contains("bridge.add.pair.expired"))
        assertTrue(state.contains("bridge.add.pair.disconnected"))
        val renew = dialog.compact(dialog.bodyAfter("private fun renew(id: RobotId)", '{'))
        assertTrue(renew.startsWith("{reconnectIfDown(id)manager.renewPairCode(id)"))
        assertTrue(dialog.compact(dialog.normalized).contains("renewButton.addActionListener{robotId?.let(::renew)}"))
    }
}
