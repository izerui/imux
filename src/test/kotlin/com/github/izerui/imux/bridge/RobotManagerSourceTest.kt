package com.github.izerui.imux.bridge

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotManagerSourceTest {
    private val manager = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/RobotManager.kt").normalized

    @Test
    fun `入站先经过配对判定`() {
        assertTrue(manager.contains("Pairing.decide("))
        assertTrue(manager.contains("hub.inboundSink.accept("))
    }

    @Test
    fun `开关关闭时不建立连接：全部启动与单个连接一进来就检查开关`() {
        val code = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/RobotManager.kt")
        val guard = "{if(!BridgeSettings.getInstance().state.enabled)return"
        assertTrue(code.compact(code.bodyAfter("fun startAll()", '{')).startsWith(guard))
        assertTrue(code.compact(code.bodyAfter("private fun connect(record: RobotRecord)", '{')).startsWith(guard))
    }

    @Test
    fun `凭据只经凭据库读写`() {
        assertTrue(manager.contains("vault.save("))
        assertTrue(manager.contains("vault.load("))
        assertTrue(manager.contains("vault.delete("))
    }

}

class RobotManagerReconnectSourceTest {
    private val code = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/RobotManager.kt")

    @Test
    fun `重新连接先断开再在 IO 上连接`() {
        val body = code.compact(code.bodyAfter("fun reconnect(id: RobotId)", '{'))
        assertTrue(body.contains("scope.launch(Dispatchers.IO){disconnect(id)robot(id)?.let{connect(it)}"))
    }

    @Test
    fun `连接与断开在同一把锁内登记和摘除`() {
        val connect = code.compact(code.bodyAfter("private fun connect(record: RobotRecord)", '{'))
        assertTrue(connect.contains("synchronized(lifecycleLock){if(connections.putIfAbsent(record.id,connection)!=null)return"))
        val disconnect = code.compact(code.bodyAfter("private fun disconnect(id: RobotId)", '{'))
        assertTrue(disconnect.contains("synchronized(lifecycleLock){connections.remove(id)?.stop()"))
    }

    @Test
    fun `旧连接的状态回调被忽略`() {
        val connect = code.compact(code.bodyAfter("private fun connect(record: RobotRecord)", '{'))
        assertTrue(connect.contains("if(connections[record.id]!==connection)return@synchronized"))
    }

    @Test
    fun `配对码连续猜错达到上限即作废，只在作废时回复一次，成功或重新生成时清零`() {
        val manager = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/RobotManager.kt")
        val sink = manager.bodyAfter("private fun pairingSink(", '{')
        val wrong = sink.substringAfter("PairDecision.Wrong ->").substringBefore("PairDecision.Ignore")
        assertTrue(wrong.contains("if (attempts < Pairing.MAX_WRONG_ATTEMPTS) return@InboundSink"))
        assertTrue(wrong.contains("store.update { it.withPairCode(id, null, 0) }"))
        assertTrue(wrong.contains("reply(message.chatId, BundleBridgeTexts.pairingLocked())"))
        assertTrue(sink.substringAfter("is PairDecision.Paired ->").contains("wrongPairAttempts.remove(id)"))
        assertTrue(manager.bodyAfter("fun renewPairCode(id: RobotId)", '{').contains("wrongPairAttempts.remove(id)"))
    }
}
