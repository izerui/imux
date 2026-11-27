package com.github.izerui.imux.bridge.terminal

import com.github.izerui.imux.bridge.core.BindResult
import com.github.izerui.imux.bridge.core.BindingRegistry
import com.github.izerui.imux.bridge.core.CodexReviewMenu
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.bridge.core.SubmitResult
import com.github.izerui.imux.model.AgentType
import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.plugins.terminal.view.TerminalOffset
import org.jetbrains.plugins.terminal.view.TerminalOutputModel
import org.jetbrains.plugins.terminal.view.TerminalOutputModelsSet
import org.jetbrains.plugins.terminal.view.TerminalSendTextBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class CodexTerminalReviewTest {
    private val bindings = BindingRegistry()
    private val binding = (bindings.bind(RobotId("phone"), SessionTarget("project", "tab", AgentType.CODEX, "session")) as BindResult.Bound).binding
    private val menu = CodexReviewMenu("Select a review preset", listOf("base", "changes", "commit"), 0)

    private fun <T> stub(type: Class<T>, call: (String, Array<out Any?>?) -> Any?): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> call(method.name, args) })

    private fun submitWithoutEditor(
        alternative: Boolean,
        output: String,
        writes: MutableList<String>,
        entries: (SessionTarget, GitPicker) -> List<String> = { _, _ -> error("普通界面读取了 Git") },
    ): SubmitResult {
        val model = stub(TerminalOutputModel::class.java) { method, args ->
            when (method) {
                "getStartOffset" -> TerminalOffset.of(200)
                "getEndOffset" -> TerminalOffset.of(200L + output.length)
                "getText" -> {
                    assertEquals(listOf(200L, 200L + output.length), args!!.map { (it as TerminalOffset).toAbsolute() })
                    output
                }
                else -> error(method)
            }
        }
        val models = stub(TerminalOutputModelsSet::class.java) { method, _ ->
            when (method) {
                "getActive" -> MutableStateFlow(model)
                "getAlternative" -> if (alternative) model else stub(TerminalOutputModel::class.java) { name, _ -> error(name) }
                else -> error(method)
            }
        }
        lateinit var builder: TerminalSendTextBuilder
        builder = stub(TerminalSendTextBuilder::class.java) { method, args ->
            when (method) {
                "requireBracketedPasteMode", "shouldExecute" -> builder
                "trySend" -> writes.add(args!!.single() as String)
                else -> error(method)
            }
        }
        val view = stub(TerminalView::class.java) { method, _ ->
            when (method) {
                "getSessionState" -> MutableStateFlow(TerminalViewSessionState.Running)
                "getGridSize" -> null
                "getOutputModels" -> models
                "createSendTextBuilder" -> builder
                else -> error(method)
            }
        }
        val project = stub(Project::class.java) { method, _ ->
            when (method) {
                "isOpen" -> true
                "isDisposed" -> false
                "getServiceIfCreated" -> null
                else -> error(method)
            }
        }
        return kotlinx.coroutines.runBlocking {
            ImuxTerminalSessionPort(viewAt = { _, _ -> view }, bindings = { bindings }, projectAt = { project }, pickerEntries = entries)
                .submitOnce(binding.target, "hello")
        }
    }

    @Test fun `提交入口遇到未能核对的换行标题仍拦住普通输入`() {
        val writes = mutableListOf<String>()
        val output = "Select a commit to review\n  Type to search commits\n› new\n  subject\nenter select · esc back"
        var reads = 0
        assertEquals(SubmitResult.NOT_READY, submitWithoutEditor(true, output, writes) { target, picker ->
            assertEquals(binding.target, target)
            assertEquals(GitPicker.COMMIT, picker)
            reads++
            emptyList()
        })
        assertEquals(1, reads)
        assertTrue(writes.isEmpty())
    }

    @Test fun `没有编辑器和网格尺寸时普通手机消息仍送进运行中的 Codex`() {
        val writes = mutableListOf<String>()
        assertEquals(SubmitResult.SUBMITTED_TO_TERMINAL, submitWithoutEditor(false, "", writes))
        assertEquals(listOf("hello"), writes)
    }

    @Test fun `没有编辑器时全屏缓冲区里的审查菜单仍阻止普通输入`() {
        val writes = mutableListOf<String>()
        val output = "Select a review preset\n› 1. Review against a base\n     branch\n  2. Review uncommitted changes\nenter select · esc back"
        assertEquals(SubmitResult.NOT_READY, submitWithoutEditor(true, output, writes))
        assertTrue(writes.isEmpty())
    }

    @Test fun `电脑移动光标后从新位置一次写入方向键和回车`() {
        val writes = mutableListOf<String>()
        assertTrue(answerCurrentReview(bindings, binding, menu, menu.copy(selected = 2), "2", writes::add))
        assertEquals(listOf("\u001b[A\r"), writes)
    }

    @Test fun `同一手机重绑原标签后旧回复不能按键`() {
        bindings.bind(binding.robotId, binding.target)
        assertFalse(answerCurrentReview(bindings, binding, menu, menu, "2") { error("旧绑定写入了终端") })
    }

    @Test fun `菜单选项已替换时旧回复不能按键`() {
        val changed = menu.copy(options = listOf("another option", "changes", "commit"))
        assertFalse(answerCurrentReview(bindings, binding, menu, changed, "2") { error("旧选项写入了终端") })
    }
}
