package com.github.izerui.imux.terminal

import com.github.izerui.imux.session.SessionExchange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Point

class SessionMessageNavigatorTest {
    private fun asked(vararg texts: String) = texts.map { SessionExchange(it, "") }

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
     * 已知局限，写在这里是为了它别被当成 bug 重新发现一遍。
     *
     * 末轮的助手回复还没写进 transcript 时，这一轮没有右边界可用，游标只能停在文档末尾；
     * 若终端此刻已经渲染出含相同文本的输出，锚点会落在后面那处。这个窗口只有 transcript
     * 写入滞后于终端渲染的一瞬，回复一旦落盘，下一次刷新就把它纠正回用户输入处。
     *
     * 用它换掉了「靠白名单认 CLI 提示符」那套：后者在换一版渲染字符时会让整轮消息
     * 从轨道上消失，且不会自愈。
     */
    @Test
    fun `末轮回复尚未写入时锚点可能落在后续输出上`() {
        val document = "> 继续\n工具输出\n继续"

        val pending = locateUserMessageAnchors(document, listOf(SessionExchange("继续", "")))
        assertEquals(document.lastIndexOf("继续"), pending.single().offset)

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

    /**
     * 终端会裁剪历史，文档随时可能变短，而锚点是上一轮算出来的：刷新有防抖和 IO 延迟，
     * 这段窗口里拿越界 offset 去问行号会直接抛到 EDT（实测 `Wrong offset: 5648`）。
     *
     * 这条只管判据本身；「绘制和命中确实过了这道判据」由
     * `SessionMessageNavigatorSourceTest` 守——纯函数测试覆盖不到调用点，
     * 实测把调用点摘掉后这条依然是绿的。
     */
    @Test
    fun `越界的 offset 判定为无效`() {
        assertEquals(null, validOffset(5648, textLength = 5430))
        assertEquals(null, validOffset(-1, textLength = 5430))
        assertEquals(100, validOffset(100, textLength = 5430))
        assertEquals(5430, validOffset(5430, textLength = 5430))
    }

    @Test
    fun `轨道标记按文档行数等比例分布`() {
        assertEquals(10, markerY(line = 0, lineCount = 101, height = 220, padding = 10))
        assertEquals(110, markerY(line = 50, lineCount = 101, height = 220, padding = 10))
        assertEquals(210, markerY(line = 100, lineCount = 101, height = 220, padding = 10))
    }
}
