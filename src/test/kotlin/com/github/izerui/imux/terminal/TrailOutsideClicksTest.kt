package com.github.izerui.imux.terminal

import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.Component
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JScrollBar
import javax.swing.SwingUtilities

/** 用真实的鼠标按下事件验证：点到轨迹卡片之外会收起，点在卡片里不会。 */
class TrailOutsideClicksTest {
    private var collapses = 0
    private val clicks = TrailOutsideClicks { collapses++ }

    private fun press(target: Component) {
        target.dispatchEvent(MouseEvent(target, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 1, 1, 1, false, MouseEvent.BUTTON1))
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    @Test
    fun `点终端正文和滚动条都会收起，点卡片不会`() = onEdt {
        val body = JPanel()
        val scrollBar = JScrollBar(JScrollBar.VERTICAL)
        val card = JPanel()
        clicks.bindEditor(listOf(body, scrollBar))

        press(body)
        assertEquals("正文", 1, collapses)
        press(scrollBar)
        assertEquals("滚动条", 2, collapses)
        press(card)
        assertEquals("卡片内部不收起", 2, collapses)
    }

    @Test
    fun `滚动条的子组件也算（某些外观下的箭头按钮）`() = onEdt {
        val scrollBar = JScrollBar(JScrollBar.VERTICAL)
        val arrow = javax.swing.JButton()
        scrollBar.add(arrow)
        clicks.bindEditor(listOf(scrollBar))

        press(arrow)
        assertEquals(1, collapses)
    }

    @Test
    fun `终端换了编辑器后只认新的正文和滚动条`() = onEdt {
        val oldBar = JScrollBar(JScrollBar.VERTICAL)
        val newBar = JScrollBar(JScrollBar.VERTICAL)
        clicks.bindEditor(listOf(oldBar))
        clicks.bindEditor(listOf(newBar))

        press(oldBar)
        assertEquals("旧编辑器的滚动条已解绑", 0, collapses)
        press(newBar)
        assertEquals(1, collapses)
    }

    @Test
    fun `固定目标与编辑器目标互不影响，重复挂载只触发一次`() = onEdt {
        val rail = JPanel()
        val bar = JScrollBar(JScrollBar.VERTICAL)
        clicks.attach(rail)
        clicks.attach(rail)
        clicks.bindEditor(listOf(bar))
        clicks.bindEditor(emptyList())

        press(rail)
        assertEquals("重复挂载只触发一次；换编辑器不影响固定目标", 1, collapses)
        press(bar)
        assertEquals(1, collapses)
    }

    @Test
    fun `释放后全部解绑`() = onEdt {
        val rail = JPanel()
        val bar = JScrollBar(JScrollBar.VERTICAL)
        clicks.attach(rail)
        clicks.bindEditor(listOf(bar))
        clicks.dispose()

        press(rail)
        press(bar)
        assertEquals(0, collapses)
    }
}
