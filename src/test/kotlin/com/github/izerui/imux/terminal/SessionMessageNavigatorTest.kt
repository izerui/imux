package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.peer.PeerFeedbackHint
import com.github.izerui.imux.session.NavigationTranscriptIndex
import com.github.izerui.imux.session.SessionExchange
import org.jetbrains.plugins.terminal.view.TerminalOutputModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Point
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class SessionMessageNavigatorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun asked(vararg texts: String) = texts.map { SessionExchange(it, "reply") }

    private val outputModel = Proxy.newProxyInstance(
        TerminalOutputModel::class.java.classLoader,
        arrayOf(TerminalOutputModel::class.java),
    ) { _, _, _ -> null } as TerminalOutputModel

    @Test
    fun `副驾驶反馈发送后无需等待主会话回复就能显示锚点`() {
        val hint = PeerFeedbackHint("检查并发边界", 1_020L, outputModel)

        val anchors = peerFeedbackAnchors(emptyList(), listOf(hint), 1_000L, 1_030L)

        assertEquals(1, anchors.size)
        assertEquals(1_020L, anchors.single().absoluteOffset)
        assertEquals("检查并发边界", anchors.single().userPreview)
        assertEquals("", anchors.single().replyPreview)
    }

    @Test
    fun `副驾驶反馈原文已有锚点时优先保留准确位置`() {
        val native = UserMessageAnchor(30, "检查并发边界", "处理完成", 1_030L)
        val hint = PeerFeedbackHint("检查并发边界", 1_020L, outputModel)

        val anchors = peerFeedbackAnchors(listOf(native), listOf(hint), 1_000L, 1_050L)

        assertEquals(listOf(native), anchors)
    }

    @Test
    fun `相同反馈只有新一轮出现原生锚点时保留旧轮气泡`() {
        val first = PeerFeedbackHint("检查并发边界", 1_010L, outputModel)
        val second = PeerFeedbackHint("检查并发边界", 1_030L, outputModel)
        val latestNative = UserMessageAnchor(40, "检查并发边界", "处理完成", 1_040L)

        val anchors = peerFeedbackAnchors(listOf(latestNative), listOf(first, second), 1_000L, 1_050L)

        assertEquals("旧轮应保留合成锚点", listOf(1_010L, 1_040L), anchors.map(UserMessageAnchor::absoluteOffset))
        assertEquals("新轮应保留原生回复预览", "处理完成", anchors.last().replyPreview)
    }

    @Test
    fun `相同反馈两轮均有原生锚点时分别替代且不重复`() {
        val first = PeerFeedbackHint("检查并发边界", 1_010L, outputModel)
        val second = PeerFeedbackHint("检查并发边界", 1_030L, outputModel)
        val firstNative = UserMessageAnchor(20, "检查并发边界", "第一轮回复", 1_020L)
        val secondNative = UserMessageAnchor(40, "检查并发边界", "第二轮回复", 1_040L)

        val anchors = peerFeedbackAnchors(
            listOf(firstNative, secondNative),
            listOf(first, second),
            1_000L,
            1_050L,
        )

        assertEquals(listOf(firstNative, secondNative), anchors)
    }

    @Test
    fun `旧轮原生锚点不替代后来尚未落屏的相同反馈`() {
        val first = PeerFeedbackHint("检查并发边界", 1_010L, outputModel)
        val second = PeerFeedbackHint("检查并发边界", 1_030L, outputModel)
        val firstNative = UserMessageAnchor(20, "检查并发边界", "第一轮回复", 1_020L)

        val anchors = peerFeedbackAnchors(listOf(firstNative), listOf(first, second), 1_000L, 1_050L)

        assertEquals(listOf(1_020L, 1_030L), anchors.map(UserMessageAnchor::absoluteOffset))
        assertEquals("旧轮保留原生回复预览", "第一轮回复", anchors.first().replyPreview)
        assertEquals("新轮保留独立合成锚点", "", anchors.last().replyPreview)
    }

    @Test
    fun `副驾驶旧锚点被终端裁剪后不再显示`() {
        val hint = PeerFeedbackHint("旧反馈", 900L, outputModel)

        val anchors = peerFeedbackAnchors(emptyList(), listOf(hint), 1_000L, 1_050L)

        assertTrue(anchors.isEmpty())
    }

    @Test
    fun `软换行与连续空白不影响用户消息定位`() {
        val document =
            """
            assistant output
            这是 一条很长的
            用户消息
            more output
            """.trimIndent()

        val anchors = locateUserMessageAnchors(document, asked("这是   一条很长的 用户消息"))

        assertEquals(1, anchors.size)
        assertEquals(document.indexOf("这是"), anchors.single().offset)
        assertEquals("这是 一条很长的 用户消息", anchors.single().userPreview)
    }

    @Test
    fun `重复消息按对话顺序定位到不同位置`() {
        val document = "继续\n回答一\n继续\n回答二"

        val anchors = locateUserMessageAnchors(document, asked("继续", "继续"))

        assertEquals(
            listOf(document.indexOf("继续"), document.lastIndexOf("继续")),
            anchors.map(UserMessageAnchor::offset),
        )
    }

    @Test
    fun `终端裁剪旧重复消息后保留最新轮次回复`() {
        val document = "继续\n回答二"

        val anchors =
            locateUserMessageAnchors(
                document,
                listOf(
                    SessionExchange("继续", "回答一"),
                    SessionExchange("继续", "回答二"),
                ),
            )

        assertEquals(listOf(document.indexOf("继续")), anchors.map(UserMessageAnchor::offset))
        assertEquals(listOf("回答二"), anchors.map(UserMessageAnchor::replyPreview))
    }

    /**
     * 助手回复自己会把游标推过去，复述必然落在回复内部、也就必然在游标之后，够不着。
     * 挡住它靠的是这条顺序规律，不是认得某个 CLI 的提示符长什么样。
     */
    @Test
    fun `助手回复里复述提问时锚点仍落在用户输入处`() {
        val document = "> 继续\n先检查当前实现\n继续"

        val anchors =
            locateUserMessageAnchors(
                document,
                listOf(SessionExchange("继续", "先检查当前实现")),
            )

        assertEquals(document.indexOf("继续"), anchors.single().offset)
    }

    @Test
    fun `上一轮输出里出现相同文本时锚点落在本轮用户输入处`() {
        val document = "助手建议继续\n> 继续\n开始处理"

        val anchors =
            locateUserMessageAnchors(
                document,
                listOf(SessionExchange("继续", "开始处理")),
            )

        assertEquals(document.indexOf("继续", document.indexOf('>')), anchors.single().offset)
    }

    /**
     * 定位不认识任何渲染符号：CLI 用 `>`、用块字符、还是像 Pi 那样画成无前缀色块，
     * 结果都一样。换一版渲染字符不会让整轮消息从轨道上消失。
     */
    @Test
    fun `渲染前缀不影响定位`() {
        val exchanges = listOf(SessionExchange("继续", "开始处理"))

        listOf("> ", "▌ ", "· ", "").forEach { prefix ->
            val document = prefix + "继续\n开始处理"

            val anchors = locateUserMessageAnchors(document, exchanges)

            assertEquals("前缀[$prefix]应当定位到用户输入处", prefix.length, anchors.single().offset)
        }
    }

    /**
     * 末轮回复还没写进 transcript 时，该轮不参与匹配——没有右边界的用户文本会抢走
     * 上一轮的匹配位置。回复落盘后下一次 transcript 刷新会重新加入。
     */
    @Test
    fun `末轮回复尚未写入时该轮不参与匹配`() {
        val document = "> 继续\n工具输出\n继续"

        val pending = locateUserMessageAnchors(document, listOf(SessionExchange("继续", "")))
        assertTrue("末轮回复为空时不应产出锚点", pending.isEmpty())

        val settled = locateUserMessageAnchors(document, listOf(SessionExchange("继续", "工具输出")))
        assertEquals(document.indexOf("继续"), settled.single().offset)
    }

    @Test
    fun `相同会话状态的重复通知只触发首轮刷新`() {
        val tracker = NavigationSessionChangeTracker()
        val state = NavigationSessionState("session-id", null)

        assertTrue(tracker.changed(state))
        assertFalse(tracker.changed(state))
    }

    @Test
    fun `会话标识变化会重新触发刷新`() {
        val tracker = NavigationSessionChangeTracker()
        tracker.changed(NavigationSessionState("old-session", null))

        assertTrue(tracker.changed(NavigationSessionState("new-session", null)))
    }

    @Test
    fun `活动时间变化唤醒增量检查且文件路径变化会重绑定`() {
        val tracker = NavigationSessionChangeTracker()
        val created = Instant.parse("2026-09-01T00:00:00Z")
        val first =
            AgentSession("session-1", "标题", AgentType.PI, created, created, Path.of("/tmp/one.jsonl"))
        tracker.changed(NavigationSessionState("session-1", first))

        val activityOnly = first.copy(lastActiveAt = created.plusSeconds(1))
        val movedFile = first.copy(filePath = Path.of("/tmp/two.jsonl"))

        assertTrue("活动时间变化要兜住晚于 Terminal 事件的落盘", tracker.changed(NavigationSessionState("session-1", activityOnly)))
        assertTrue("会话文件切换必须重新读取", tracker.changed(NavigationSessionState("session-1", movedFile)))
    }

    @Test
    fun `Pi 用户消息 generation 变化会触发刷新`() {
        val tracker = NavigationSessionChangeTracker()
        tracker.changed(NavigationSessionState("pi-1", null, transcriptGeneration = 1L))

        assertTrue(tracker.changed(NavigationSessionState("pi-1", null, transcriptGeneration = 2L)))
    }

    @Test
    fun `锚点回复更新后重新绑定活动预览`() {
        val current = UserMessageAnchor(12, "继续", "")
        val updated = UserMessageAnchor(12, "继续", "新的回复")

        assertEquals(updated, refreshedAnchor(current, listOf(updated)))
    }

    @Test
    fun `标记屏幕坐标变化会重建预览`() {
        val anchor = UserMessageAnchor(12, "继续", "新的回复")

        assertTrue(
            previewNeedsRebuild(
                current = anchor,
                refreshed = anchor,
                currentScreenPoint = Point(100, 200),
                refreshedScreenPoint = Point(100, 220),
            ),
        )
    }

    @Test
    fun `同坐标锚点按点击顺序循环选择`() {
        val first = UserMessageAnchor(10, "第一轮", "回复一")
        val second = UserMessageAnchor(20, "第二轮", "回复二")
        val group = listOf(first, second)

        assertEquals(first, nextCollocatedAnchor(group, null))
        assertEquals(second, nextCollocatedAnchor(group, first))
        assertEquals(first, nextCollocatedAnchor(group, second))
    }

    @Test
    fun `终端历史已裁剪的旧消息不阻碍后续消息定位`() {
        val document = "仍在缓冲区的消息\n回答"

        val anchors =
            locateUserMessageAnchors(
                document,
                asked("已经被裁剪的旧消息", "仍在缓冲区的消息"),
            )

        assertEquals(listOf(document.indexOf("仍在缓冲区")), anchors.map(UserMessageAnchor::offset))
    }

    @Test
    fun `助手回复随对应的用户消息一起进入锚点`() {
        val document = "继续\n回答一\n换个思路\n回答二"

        val anchors =
            locateUserMessageAnchors(
                document,
                listOf(
                    SessionExchange("继续", "回答一"),
                    SessionExchange("换个思路", "回答二"),
                ),
            )

        assertEquals(
            listOf("回答一", "回答二"),
            anchors.map(UserMessageAnchor::replyPreview),
        )
    }

    @Test
    fun `裁剪掉的轮次不会把回复错配给后面的消息`() {
        val document = "仍在缓冲区的消息\n回答二"

        val anchors =
            locateUserMessageAnchors(
                document,
                listOf(
                    SessionExchange("已经被裁剪的旧消息", "回答一"),
                    SessionExchange("仍在缓冲区的消息", "回答二"),
                ),
            )

        assertEquals("回答二", anchors.single().replyPreview)
    }

    @Test
    fun `截图里的两条 Pi 用户消息分别生成锚点`() {
        val first = "原生 TradingAgents 引擎 是啥意思？"
        val second =
            "我的意思是让你就是针对这个仓库它拥有的这些分析的功能，然后统一整合，" +
                "就是统一用这个skills来去实现。"
        val document = "$first\n第一轮回复\n$second\n第二轮回复"

        val anchors =
            locateUserMessageAnchors(
                document,
                listOf(
                    SessionExchange(first, "第一轮回复"),
                    SessionExchange(second, "第二轮回复"),
                ),
            )

        assertEquals("第一条 Pi 输入应有独立锚点", document.indexOf(first), anchors[0].offset)
        assertEquals("第二条 Pi 输入应有独立锚点", document.indexOf(second), anchors[1].offset)
    }

    @Test
    fun `终端快照起点计入锚点绝对位置`() {
        val document = "前缀\n用户问题\n回复"

        val anchor =
            locateUserMessageAnchors(
                document,
                asked("用户问题"),
                documentStartOffset = 50_000L,
            ).single()

        assertEquals(document.indexOf("用户问题"), anchor.offset)
        assertEquals(50_000L + document.indexOf("用户问题"), anchor.absoluteOffset)
    }

    @Test
    fun `历史裁剪后绝对锚点换算到当前窗口`() {
        val anchor = UserMessageAnchor(110, "问题", "回复", absoluteOffset = 1_110L)

        assertEquals(110, relativeOffset(anchor, outputStartOffset = 1_000L, textLength = 500))
        assertEquals(10, relativeOffset(anchor, outputStartOffset = 1_100L, textLength = 400))
        assertEquals(null, relativeOffset(anchor, outputStartOffset = 1_111L, textLength = 400))
    }

    @Test
    fun `锚点之后追加输出不要求重定位而前方改写会要求`() {
        val anchors =
            listOf(
                UserMessageAnchor(10, "问题一", "", absoluteOffset = 1_010L),
                UserMessageAnchor(20, "问题二", "", absoluteOffset = 1_020L),
            )

        assertFalse(changeCanMoveAnchors(changeOffset = 30, anchors, outputStartOffset = 1_000L))
        assertTrue(changeCanMoveAnchors(changeOffset = 15, anchors, outputStartOffset = 1_000L))
    }

    @Test
    fun `最新用户轮次出现后停止待定位重试`() {
        val exchanges = asked("较早问题", "最新问题")
        val earlierOnly = listOf(IndexedUserMessageAnchor(0, UserMessageAnchor(10, "较早问题", "")))
        val includingLatest = earlierOnly + IndexedUserMessageAnchor(1, UserMessageAnchor(20, "最新问题", ""))

        assertFalse(latestExchangeResolved(exchanges, earlierOnly))
        assertTrue(latestExchangeResolved(exchanges, includingLatest))
    }

    @Test
    fun `无回复轮次只等待首次终端内容而不随工具输出持续重扫`() {
        val exchanges = listOf(SessionExchange("新的提问", ""))

        assertTrue(waitForTerminalContent(exchanges, transcriptChanged = true, latestResolved = false, outputChangedDuringLocate = false))
        assertFalse(waitForTerminalContent(exchanges, transcriptChanged = false, latestResolved = false, outputChangedDuringLocate = false))
    }

    @Test
    fun `空会话只在 transcript 变化后等待一次内容事件`() {
        val exchanges = emptyList<SessionExchange>()

        assertFalse(waitForTerminalContent(exchanges, transcriptChanged = false, latestResolved = true, outputChangedDuringLocate = false))
        assertTrue(waitForTerminalContent(exchanges, transcriptChanged = true, latestResolved = true, outputChangedDuringLocate = false))
        assertFalse(waitForTerminalContent(exchanges, transcriptChanged = false, latestResolved = true, outputChangedDuringLocate = false))
        assertFalse(waitForTerminalContent(exchanges, transcriptChanged = true, latestResolved = true, outputChangedDuringLocate = true))
    }

    @Test
    fun `已有回复但消息尚未落屏时继续等待且定位期间变化不重复等待`() {
        val exchanges = listOf(SessionExchange("新的提问", "回复"))

        assertTrue(waitForTerminalContent(exchanges, transcriptChanged = false, latestResolved = false, outputChangedDuringLocate = false))
        assertFalse(waitForTerminalContent(exchanges, transcriptChanged = false, latestResolved = true, outputChangedDuringLocate = false))
        assertFalse(waitForTerminalContent(exchanges, transcriptChanged = true, latestResolved = false, outputChangedDuringLocate = true))
    }

    @Test
    fun `重复提问只定位上一轮时不误判为末轮已解析`() {
        val exchanges =
            listOf(
                SessionExchange("继续", "已处理"),
                SessionExchange("继续", "已处理"),
            )
        val firstOnly = listOf(IndexedUserMessageAnchor(0, UserMessageAnchor(10, "继续", "")))

        assertFalse(
            "上一轮 preview 相同但 exchangeIndex 不是末轮，不应认为已解析",
            latestExchangeResolved(exchanges, firstOnly),
        )
    }

    @Test
    fun `助手回复未落盘时不展示最新用户输入的可点击圆点`() {
        val exchanges =
            listOf(
                SessionExchange("较早问题", "较早回复"),
                SessionExchange("最新问题", ""),
            )
        val indexed =
            listOf(
                IndexedUserMessageAnchor(0, UserMessageAnchor(10, "较早问题", "较早回复")),
                IndexedUserMessageAnchor(1, UserMessageAnchor(20, "最新问题", "")),
            )

        assertEquals(
            listOf("较早问题"),
            stableAnchorsForNavigation(exchanges, indexed).map(UserMessageAnchor::userPreview),
        )
    }

    @Test
    fun `末轮未定位时上一轮已定位的圆点不被误删`() {
        val exchanges =
            listOf(
                SessionExchange("较早问题", "较早回复"),
                SessionExchange("最新问题", ""),
            )
        val indexed =
            listOf(IndexedUserMessageAnchor(0, UserMessageAnchor(10, "较早问题", "较早回复")))

        assertEquals(
            listOf("较早问题"),
            stableAnchorsForNavigation(exchanges, indexed).map(UserMessageAnchor::userPreview),
        )
    }

    @Test
    fun `重复提问末轮未定位时上一轮同名圆点不被误删`() {
        val exchanges =
            listOf(
                SessionExchange("继续", "已处理"),
                SessionExchange("继续", ""),
            )
        val document = "继续\n已处理"
        val indexed = locateUserMessageAnchorsIndexed(document, exchanges)

        assertEquals("上一轮应产出锚点", 1, indexed.size)
        assertEquals("锚点应归属上一轮", 0, indexed.single().exchangeIndex)
        assertEquals(
            "上一轮的圆点不应因 preview 相同而被当成末轮删掉",
            listOf("继续"),
            stableAnchorsForNavigation(exchanges, indexed).map(UserMessageAnchor::userPreview),
        )
    }

    /**
     * 真实定位链路：重复提问 "继续" 两轮，末轮回复为空，终端只有上一轮内容。
     *
     * 末轮不参与匹配，上一轮的 "继续" 被正确标为 exchangeIndex=0，
     * stableAnchorsForNavigation 保留它，latestExchangeResolved 返回 false。
     */
    @Test
    fun `重复提问末轮回复未落盘时上一轮圆点保留且继续等待刷新`() {
        val exchanges =
            listOf(
                SessionExchange("继续", "已处理"),
                SessionExchange("继续", ""),
            )
        val document = "继续\n已处理"

        val indexed = locateUserMessageAnchorsIndexed(document, exchanges)

        assertEquals("上一轮应产出锚点", 1, indexed.size)
        assertEquals("锚点应归属上一轮而非末轮", 0, indexed.single().exchangeIndex)
        assertEquals(document.indexOf("继续"), indexed.single().anchor.offset)

        val stable = stableAnchorsForNavigation(exchanges, indexed)
        assertEquals("上一轮圆点应保留", 1, stable.size)

        assertFalse("末轮未定位，应继续等待刷新", latestExchangeResolved(exchanges, indexed))
    }

    @Test
    fun `轨道标记按可视行数等比例分布`() {
        assertEquals(10, markerY(line = 0, lineCount = 101, height = 220, padding = 10))
        assertEquals(110, markerY(line = 50, lineCount = 101, height = 220, padding = 10))
        assertEquals(210, markerY(line = 100, lineCount = 101, height = 220, padding = 10))
    }

    /**
     * 端到端：从 JSONL 解析经 NavigationTranscriptIndex 到锚点定位。
     *
     * 助手回复的 content 数组含 [text, tool_use, text]。旧实现把两段 text 拼成
     * "好的. All done."，终端里找不到（中间有工具输出），游标不推回，"请修复" 定到
     * 工具输出中复述的那处。修复后导航索引只取第一段 "好的."，游标正确推回。
     */
    @Test
    fun `助手回复跨 tool_use 文本经解析到锚点全链路定位正确`() {
        val file = temp.newFile("e2e.jsonl").toPath()
        Files.writeString(
            file,
            """
            {"type":"user","message":{"role":"user","content":"请修复"}}
            {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"好的."},{"type":"tool_use","id":"t1","name":"bash","input":{}},{"type":"text","text":"All done."}]}}
            """.trimIndent(),
        )
        val now = Instant.now()
        val session = AgentSession("s1", "标题", AgentType.CLAUDE, now, now, file)
        val index = NavigationTranscriptIndex()
        val exchanges = index.refresh(session).exchanges

        assertEquals("导航索引应只取第一段助手文本", "好的.", exchanges.single().assistantReply)

        val document = "请修复\n好的.\ntool output 请修复 somewhere\nAll done."
        val anchors = locateUserMessageAnchors(document, exchanges)

        assertEquals(
            "锚点应落在用户输入处而非工具输出中的复述处",
            document.indexOf("请修复"),
            anchors.single().offset,
        )
    }

    /**
     * 助手先调工具再给文本时，回复不能为空——否则 [stableAnchorsForNavigation] 会
     * 隐藏该轮圆点。[tool_use, text] 中 tool_use 前没有文本段，应跳过工具继续取后面的文本。
     */
    @Test
    fun `助手先调工具再回复文本时圆点仍然可见`() {
        val file = temp.newFile("e2e-tool-first.jsonl").toPath()
        Files.writeString(
            file,
            """
            {"type":"user","message":{"role":"user","content":"检查日志"}}
            {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"bash","input":{}},{"type":"text","text":"日志没有异常。"}]}}
            """.trimIndent(),
        )
        val now = Instant.now()
        val session = AgentSession("s1", "标题", AgentType.CLAUDE, now, now, file)
        val index = NavigationTranscriptIndex()
        val exchanges = index.refresh(session).exchanges

        assertEquals("tool_use 前无文本时应取后面的文本段", "日志没有异常。", exchanges.single().assistantReply)

        val document = "检查日志\ntool output\n日志没有异常。"
        val indexed = locateUserMessageAnchorsIndexed(document, exchanges)
        val stable = stableAnchorsForNavigation(exchanges, indexed)

        assertEquals("该轮圆点不应被隐藏", 1, stable.size)
    }

    /**
     * 第一轮助手只有 tool_use（解析后回复为空），第二轮同样问"继续"且尚无回复。
     *
     * 两轮回复都为空时，[dropLastWhile] 把两轮全部移出匹配，不会产出锚点。
     * 这避免了反向搜索把工具输出中的复述标为用户输入，代价是助手只有工具调用的
     * 轮次需要等后续文本回复落盘才能定位——这与终端高亮优先匹配在真实场景下互补。
     */
    @Test
    fun `两轮回复均为空的重复提问不互相抢位`() {
        val file = temp.newFile("e2e-both-empty-reply.jsonl").toPath()
        Files.writeString(
            file,
            """
            {"type":"user","message":{"role":"user","content":"继续"}}
            {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"bash","input":{}}]}}
            {"type":"user","message":{"role":"user","content":"继续"}}
            """.trimIndent(),
        )
        val now = Instant.now()
        val session = AgentSession("s1", "标题", AgentType.CLAUDE, now, now, file)
        val index = NavigationTranscriptIndex()
        val exchanges = index.refresh(session).exchanges

        assertEquals("两轮应都被解析", 2, exchanges.size)
        assertEquals("第一轮助手只有 tool_use，回复应为空", "", exchanges[0].assistantReply)
        assertEquals("末轮尚无回复", "", exchanges[1].assistantReply)

        val document = "继续\n工具输出复述：继续"
        val indexed = locateUserMessageAnchorsIndexed(document, exchanges)

        assertTrue(
            "两轮回复都为空时不产出锚点，避免定位到复述处",
            indexed.isEmpty(),
        )
        assertFalse("应继续等待刷新", latestExchangeResolved(exchanges, indexed))
    }

    /**
     * 助手只有 tool_use 的轮次无法建立右边界——即使后续轮次限制了搜索范围，
     * 工具输出中的复述仍在范围内，纯文本匹配无法区分。不展示该轮圆点。
     */
    @Test
    fun `工具调用轮次无文本回复时不展示圆点即使后续轮次有回复`() {
        val exchanges =
            listOf(
                SessionExchange("继续", ""),
                SessionExchange("修复 bug", "好的"),
            )
        val document = "继续\n工具输出复述：继续\n修复 bug\n好的"
        val indexed = locateUserMessageAnchorsIndexed(document, exchanges)
        val stable = stableAnchorsForNavigation(exchanges, indexed)

        assertEquals(
            "只有有回复的轮次展示圆点",
            listOf("修复 bug"),
            stable.map(UserMessageAnchor::userPreview),
        )
    }

}
