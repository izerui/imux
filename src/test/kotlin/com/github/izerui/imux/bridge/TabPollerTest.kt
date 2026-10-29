package com.github.izerui.imux.bridge

import com.github.izerui.imux.bridge.core.BindingRegistry
import com.github.izerui.imux.bridge.core.BridgeRouter
import com.github.izerui.imux.bridge.core.BridgeTexts
import com.github.izerui.imux.bridge.core.ChannelAdapter
import com.github.izerui.imux.bridge.core.DeliveryResult
import com.github.izerui.imux.bridge.core.OutboundMessage
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.RobotOwner
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.bridge.core.SubmitResult
import com.github.izerui.imux.bridge.transcript.SessionFile
import com.github.izerui.imux.bridge.transcript.TailRegistry
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.ClaudeRuntimeSession
import com.github.izerui.imux.turn.RunningSessions
import com.github.izerui.imux.turn.RuntimeStatusTracker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

/** 服务层的读取与结算顺序：真实的会话文件、尾随器登记表与路由器，只把渠道换成记录发出内容的假实现。 */
class TabPollerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val robot = RobotId("r1")
    private val sent = mutableListOf<String>()
    private val bindings = BindingRegistry()

    private val channel =
        object : ChannelAdapter {
            override val robotId = robot
            override val maxTextBytes = 10_000

            override suspend fun send(message: OutboundMessage): DeliveryResult {
                sent += message.text
                return DeliveryResult.Accepted
            }
        }

    private object Texts : BridgeTexts {
        override fun unbound() = "UNBOUND"

        override fun offline() = "OFFLINE"

        override fun rebound() = "REBOUND"

        override fun notReady() = "NOT_READY"

        override fun emptyInput() = "EMPTY"

        override fun localInputLabel() = "LOCAL:"

        override fun waiting(reason: String?) = "WAIT($reason)"

        override fun released(sessionTitle: String) = "RELEASED"

        override fun transcriptRestarted() = "RESTARTED"

        override fun backgroundRunning(tasks: List<String?>) = "BG(${tasks.joinToString(",")})"

        override fun turnWithoutReply() = "NO_REPLY"

        override fun turnInterrupted() = "INTERRUPTED"

        override fun reconnected(
            project: String,
            agent: String,
            sessionTitle: String,
        ) = "RECONNECTED($project/$agent/$sessionTitle)"

        override fun connected(
            project: String,
            agent: String,
            sessionTitle: String,
        ) = "CONNECTED"

        override fun attachmentLine(path: String) = path

        override fun attachmentsOnly(paths: List<String>) = paths.joinToString()

        override fun attachmentFailed() = "ATT_FAILED"

        override fun attachmentTooLarge() = "ATT_TOO_LARGE"
    }

    private val router =
        BridgeRouter(bindings, { RobotOwner("me", "chat") }, { _, _ -> SubmitResult.SUBMITTED_TO_TERMINAL }, { channel }, Texts)

    private lateinit var transcript: Path

    private val poller by lazy {
        transcript = temp.newFile("s1.jsonl").toPath()
        val tails =
            TailRegistry(
                bindingForTab = bindings::bindingForTab,
                sessionFile = { if (fileKnown) SessionFile(transcript, Instant.EPOCH) else null },
                switchGeneration = { 1L },
            )
        TabPoller(tails, router) { true }.also { this.tails = tails }
    }
    private lateinit var tails: TailRegistry

    /** 会话文件是否已被会话模型发现；新建的会话要等下一次扫描才有。 */
    private var fileKnown = true

    /** Pi 记录：没有 stopReason 的助手正文不带结束标记，只能靠 IDE 完成信号结算。 */
    private fun write(vararg lines: String) =
        Files.writeString(transcript, lines.joinToString("") { "$it\n" }, StandardOpenOption.APPEND)

    private fun user(text: String) = """{"type":"message","message":{"role":"user","content":"$text"}}"""

    private fun assistant(text: String) = """{"type":"message","message":{"role":"assistant","content":[{"type":"text","text":"$text"}]}}"""

    private fun start() =
        runBlocking {
            poller
            bindings.bind(robot, SessionTarget("/p", "tab", AgentType.PI, "s1"))
            poller.poll("tab")
            sent.clear()
        }

    @Test
    fun `后台任务持续时中间轮次缺少结束标记也按轮发到手机`() =
        runBlocking {
            start()
            bindings.bind(robot, SessionTarget("/p", "tab", AgentType.CLAUDE, "s1"))
            poller.poll("tab")
            val tracker = RuntimeStatusTracker()
            fun runtime(status: String) =
                mapOf("s1" to ClaudeRuntimeSession("s1", 1, "interactive", status, "/p"))

            tracker.completedSince(runtime("busy"))
            write(
                """{"type":"user","origin":{"kind":"human"},"message":{"role":"user","content":"第一句"}}""",
                """{"type":"user","toolUseResult":{"backgroundTaskId":"task1","description":"后台测试"},"message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"tool1","content":"running"}]}}""",
                assistant("第一轮结论"),
            )
            // 故意不写 stop_reason，复现依靠 IDE 轮次事件兜底的路径。
            val first = tracker.completedSince(runtime("shell"))
            assertEquals(listOf("s1"), first.completed)
            assertEquals(setOf("s1"), RunningSessions.of(runtime("shell"), emptySet()))
            first.completed.forEach { poller.completed("tab", tails.mark("tab")) }
            assertEquals(listOf("LOCAL:\n第一句", "第一轮结论", "BG(后台测试)"), sent)

            tracker.completedSince(runtime("busy"))
            write(
                """{"type":"user","origin":{"kind":"human"},"message":{"role":"user","content":"第二句"}}""",
                assistant("第二轮结论"),
            )
            val second = tracker.completedSince(runtime("shell"))
            assertEquals(listOf("s1"), second.completed)
            assertEquals(setOf("s1"), RunningSessions.of(runtime("shell"), emptySet()))
            second.completed.forEach { poller.completed("tab", tails.mark("tab")) }
            assertEquals(
                listOf("LOCAL:\n第一句", "第一轮结论", "BG(后台测试)", "LOCAL:\n第二句", "第二轮结论"),
                sent,
            )
            assertEquals(emptyList<String>(), tracker.completedSince(runtime("idle")).completed)
        }

    @Test
    fun `结对审查开始：先结算发出本轮结论，再推审查开始，之后迟到的完成信号不重复发`() =
        runBlocking {
            start()
            write(user("第一句"), assistant("第一轮结论"))
            val mark = tails.mark("tab")
            poller.afterTurn("tab", "审查中", mark)
            poller.completed("tab", mark)
            assertEquals(listOf("LOCAL:\n第一句", "第一轮结论", "审查中"), sent)
        }

    @Test
    fun `结对审查开始时本轮结论还没写进记录：等完成信号结算后，结论在前、审查在后`() =
        runBlocking {
            start()
            write(user("第一句"))
            poller.afterTurn("tab", "审查中", tails.mark("tab"))
            write(assistant("第一轮结论"))
            poller.completed("tab", tails.mark("tab"))
            assertEquals(listOf("LOCAL:\n第一句", "第一轮结论", "审查中"), sent)
        }

    @Test
    fun `完成信号的边界不可靠、没能结算时，排着的提示照样发出`() =
        runBlocking {
            start()
            write(user("第一句"))
            poller.poll("tab")
            poller.afterTurn("tab", "审查中", tails.mark("tab"))
            // 边界为空：不结算
            poller.completed("tab", null)
            assertEquals(listOf("LOCAL:\n第一句", "审查中"), sent)
        }

    @Test
    fun `审查开始时下一轮的输入已写进记录：只读到本轮边界，审查提示不挂到下一轮名下`() =
        runBlocking {
            start()
            write(user("第一句"), assistant("第一轮结论"))
            val mark = tails.mark("tab")
            write(user("第二句"))
            poller.afterTurn("tab", "审查中", mark)
            poller.completed("tab", mark)
            poller.poll("tab")
            assertEquals(listOf("LOCAL:\n第一句", "第一轮结论", "审查中", "LOCAL:\n第二句"), sent)
        }

    @Test
    fun `审查很快失败：结果排在还没发出的审查开始之后`() =
        runBlocking {
            start()
            write(user("第一句"), assistant("第一轮结论"))
            val mark = tails.mark("tab")
            poller.afterTurn("tab", "审查中", mark)
            poller.status("tab", "审查失败")
            poller.completed("tab", mark)
            assertEquals(listOf("LOCAL:\n第一句", "第一轮结论", "审查中", "审查失败"), sent)
        }

    @Test
    fun `审查被新一轮取消：立即发出，不等新一轮的回复，排着的审查开始先发`() =
        runBlocking {
            start()
            write(user("第一句"))
            poller.poll("tab")
            poller.status("tab", "审查中")
            poller.statusNow("tab", "审查已取消")
            write(user("第二句"))
            poller.poll("tab")
            assertEquals(listOf("LOCAL:\n第一句", "审查中", "审查已取消", "LOCAL:\n第二句"), sent)
        }

    @Test
    fun `完成信号按记下的边界结算本轮结论`() =
        runBlocking {
            start()
            write(user("第一句"), assistant("第一轮结论"))
            val mark = tails.mark("tab")
            poller.completed("tab", mark)
            assertEquals(listOf("LOCAL:\n第一句", "第一轮结论"), sent)
        }

    @Test
    fun `记下完成边界后普通轮询先读了下一轮：完成回调放弃结算，下一轮的过程说明不发`() =
        runBlocking {
            start()
            write(user("第一句"), assistant("第一轮结论"))
            poller.poll("tab")
            // IDE 的完成信号在这一刻到达，记下边界
            val mark = tails.mark("tab")
            // CLI 紧接着开始下一轮，普通轮询抢先拿到标签锁读完
            write(user("第二句"), assistant("我先看看"))
            poller.poll("tab")
            poller.completed("tab", mark)
            assertEquals(listOf("LOCAL:\n第一句", "LOCAL:\n第二句"), sent)
        }

    @Test
    fun `完成信号到达时还没有尾随器：普通轮询随后建立尾随器并读进下一轮，旧完成回调不结算`() =
        runBlocking {
            poller
            bindings.bind(robot, SessionTarget("/p", "tab", AgentType.PI, "s1"))
            // 连接刚建立，尾随器还没建：信号记下的边界为空
            val mark = tails.mark("tab")
            assertEquals(null, mark)
            poller.poll("tab")
            write(user("下一句"), assistant("我先看看"))
            poller.poll("tab")
            poller.completed("tab", mark)
            assertEquals(listOf("LOCAL:\n下一句"), sent)
        }

    @Test
    fun `会话文件尚未被发现时到达的完成信号不结算：之后建立尾随器读到的过程说明不提前发`() =
        runBlocking {
            poller
            fileKnown = false
            bindings.bind(robot, SessionTarget("/p", "tab", AgentType.PI, "s1"))
            write(user("第一句"))
            poller.poll("tab")
            val mark = tails.mark("tab")
            poller.completed("tab", mark)
            // 文件被发现，尾随器从末尾建立（跳过已写入的输入），随后才写入过程说明
            fileKnown = true
            poller.poll("tab")
            write(assistant("我先看看"))
            poller.poll("tab")
            assertEquals(emptyList<String>(), sent)
        }

    @Test
    fun `记下边界后会话文件被替换：完成回调只报记录重启、不结算，之后的过程说明不提前发`() =
        runBlocking {
            start()
            write(user("第一句"))
            poller.poll("tab")
            val mark = tails.mark("tab")
            // CLI 用新文件整体替换了记录（新 inode）
            val replacement = temp.newFile("replacement.jsonl").toPath()
            Files.writeString(replacement, user("替换后") + "\n")
            Files.move(replacement, transcript, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            poller.completed("tab", mark)
            write(assistant("我先看看"))
            poller.poll("tab")
            assertEquals(listOf("LOCAL:\n第一句", "RESTARTED"), sent)
        }

    @Test
    fun `记下边界后被同样大小的新文件替换、普通轮询先发现重启：旧完成回调不结算，之后的过程说明不提前发`() =
        runBlocking {
            start()
            write(user("第一句"))
            poller.poll("tab")
            val mark = tails.mark("tab")
            // 新文件与旧文件字节数相同：普通轮询重启后读取位置恰好等于旧边界
            val replacement = temp.newFile("replacement.jsonl").toPath()
            Files.writeString(replacement, user("第二句") + "\n")
            assertEquals(Files.size(transcript), Files.size(replacement))
            Files.move(replacement, transcript, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            poller.poll("tab")
            poller.completed("tab", mark)
            write(assistant("我先看看"))
            poller.poll("tab")
            assertEquals(listOf("LOCAL:\n第一句", "RESTARTED"), sent)
        }
}
