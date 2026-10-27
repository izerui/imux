package com.github.izerui.imux.bridge.robot

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotManagerSourceTest {
    private val manager = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/robot/RobotManager.kt").normalized
    private val service = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/TerminalBridgeService.kt").normalized
    private val startup = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/BridgeStartupActivity.kt").normalized

    @Test
    fun `入站先经过配对判定`() {
        assertTrue(manager.contains("Pairing.decide("))
        assertTrue(manager.contains("hub.inboundSink.accept("))
    }

    @Test
    fun `开关关闭时不建立连接`() {
        assertTrue(manager.contains("if (!BridgeSettings.getInstance().state.enabled) return"))
    }

    @Test
    fun `凭据只经凭据库读写`() {
        assertTrue(manager.contains("vault.save("))
        assertTrue(manager.contains("vault.load("))
        assertTrue(manager.contains("vault.delete("))
    }

    @Test
    fun `观察服务在开关关闭时不轮询并同步持久化`() {
        assertTrue(service.contains("BridgeSettings.getInstance().state.enabled"))
        assertTrue(service.contains("RobotManager.getInstance().syncBindings()"))
    }

    @Test
    fun `启动时恢复机器人连接`() {
        assertTrue(startup.contains("RobotManager.getInstance().startAll()"))
    }
}

class RobotManagerReconnectSourceTest {
    private val code = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/robot/RobotManager.kt")

    @Test
    fun `重新连接先断开再在 IO 上连接`() {
        val body = code.compact(code.bodyAfter("fun reconnect(id: String)", '{'))
        assertTrue(body.contains("scope.launch(Dispatchers.IO){disconnect(id)robot(id)?.let{connect(it)}"))
    }

    @Test
    fun `连接与断开在同一把锁内登记和摘除`() {
        val connect = code.compact(code.bodyAfter("private fun connect(record: RobotRecord)", '{'))
        assertTrue(connect.contains("synchronized(lifecycleLock){if(connections.putIfAbsent(record.id,connection)!=null)return"))
        val disconnect = code.compact(code.bodyAfter("private fun disconnect(id: String)", '{'))
        assertTrue(disconnect.contains("synchronized(lifecycleLock){connections.remove(id)?.stop()"))
    }

    @Test
    fun `旧连接的状态回调被忽略`() {
        val connect = code.compact(code.bodyAfter("private fun connect(record: RobotRecord)", '{'))
        assertTrue(connect.contains("if(connections[record.id]!==connection)return@synchronized"))
    }
}
