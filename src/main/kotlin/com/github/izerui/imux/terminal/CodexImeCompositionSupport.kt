package com.github.izerui.imux.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import org.jetbrains.plugins.terminal.view.TerminalContentChangeEvent
import org.jetbrains.plugins.terminal.view.TerminalCursorOffsetChangeEvent
import org.jetbrains.plugins.terminal.view.TerminalOutputModelListener
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.InputMethodEvent
import java.text.AttributedCharacterIterator
import javax.swing.SwingConstants

/**
 * 在 Codex terminal 中以非布局覆盖层显示输入法组合文本。
 *
 * IDEA 262 默认把未提交文字创建成 inline inlay。Codex 流式输出时每帧都会移动
 * terminal 光标，平台随之不断重建 inlay；inline inlay 会参与 soft-wrap 计算，
 * 因而输入区和整个视口会在组合期间反复多出一行。
 *
 * InlayProperties 的禁用换行选项对 inline inlay 无效，关闭整个 Editor soft-wrap
 * 又会破坏 Codex 输入区背景。因此这里在 AWT 分发前消费原始组合事件：已提交文字
 * 通过公开的 [TerminalView.sendText] 发送，未提交文字画在 Editor content component
 * 的透明子组件上，不进入 Editor 布局。
 *
 * 平台原有 InputMethodRequests 不变，所以 macOS 候选窗仍按 terminal 真实光标定位。
 */
internal class CodexImeCompositionSupport(
    private val editor: Editor,
    private val terminalView: TerminalView,
) : AWTEventListener {

    private val contentComponent = editor.contentComponent
    private val modelListenerDisposable: Disposable =
        Disposer.newDisposable("Codex IME composition model listener")
    private val compositionLabel = JBLabel().apply {
        isFocusable = false
        isOpaque = false
        horizontalAlignment = SwingConstants.LEFT
        verticalAlignment = SwingConstants.CENTER
        isVisible = false
    }
    private var composedText = ""
    private val modelListener = object : TerminalOutputModelListener {
        override fun afterContentChanged(event: TerminalContentChangeEvent) {
            updateCompositionBounds()
        }

        override fun cursorOffsetChanged(event: TerminalCursorOffsetChangeEvent) {
            updateCompositionBounds()
        }
    }

    init {
        contentComponent.add(compositionLabel)
        contentComponent.setComponentZOrder(compositionLabel, 0)
        terminalView.outputModels.active.value.addListener(
            modelListenerDisposable,
            modelListener,
        )
        Toolkit.getDefaultToolkit().addAWTEventListener(
            this,
            AWTEvent.INPUT_METHOD_EVENT_MASK,
        )
    }

    override fun eventDispatched(event: AWTEvent) {
        if (event.source !== contentComponent || event !is InputMethodEvent) return

        when (event.id) {
            InputMethodEvent.INPUT_METHOD_TEXT_CHANGED -> {
                val (committedText, newComposedText) = splitInputMethodText(
                    event.text,
                    event.committedCharacterCount,
                )
                if (committedText.isNotEmpty()) {
                    terminalView.sendText(committedText)
                }
                showComposedText(newComposedText)
                event.consume()
            }

            InputMethodEvent.CARET_POSITION_CHANGED -> event.consume()
        }
    }

    fun dispose() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(this)
        Disposer.dispose(modelListenerDisposable)
        contentComponent.remove(compositionLabel)
        contentComponent.repaint()
    }

    private fun showComposedText(text: String) {
        composedText = text
        compositionLabel.text = text
        compositionLabel.isVisible = text.isNotEmpty()
        if (text.isNotEmpty()) {
            updateCompositionBounds()
        } else {
            contentComponent.repaint()
        }
    }

    private fun updateCompositionBounds() {
        if (composedText.isEmpty() || editor.isDisposed) return

        val model = terminalView.outputModels.active.value
        val relativeOffset = (model.cursorOffset - model.startOffset)
            .coerceIn(0L, model.textLength.toLong())
            .toInt()
        val point = editor.offsetToXY(relativeOffset)
        val colorsScheme = editor.colorsScheme
        val foreground = colorsScheme.defaultForeground

        compositionLabel.font = colorsScheme.getFont(EditorFontType.CONSOLE_PLAIN)
        compositionLabel.foreground = foreground
        compositionLabel.border = JBUI.Borders.customLine(foreground, 0, 0, 1, 0)

        val width = compositionLabel.getFontMetrics(compositionLabel.font)
            .stringWidth(composedText)
            .coerceAtLeast(1)
        compositionLabel.setBounds(
            point.x,
            point.y,
            width,
            editor.lineHeight,
        )
        compositionLabel.repaint()
    }
}

internal data class InputMethodText(
    val committed: String,
    val composed: String,
)

internal fun splitInputMethodText(
    text: AttributedCharacterIterator?,
    committedCharacterCount: Int,
): InputMethodText {
    if (text == null) return InputMethodText("", "")

    val committed = StringBuilder()
    val composed = StringBuilder()
    val committedCount = committedCharacterCount.coerceAtLeast(0)
    var index = 0
    var current = text.first()
    while (current != AttributedCharacterIterator.DONE) {
        if (current >= ' ' && current != '\u007f') {
            if (index < committedCount) {
                committed.append(current)
            } else {
                composed.append(current)
            }
        }
        index++
        current = text.next()
    }
    return InputMethodText(committed.toString(), composed.toString())
}
