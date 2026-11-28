package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import org.junit.Assert.*
import org.junit.Test

class CodexInputWaitingTest {
    private fun approval(title: String) = """
        $title
        $ echo test
        › 1. Yes, proceed (y)
          2. No, and tell Codex what to do differently (esc)
        Press enter to confirm or esc to cancel
    """.trimIndent()

    @Test fun `命令编辑权限和终端输入确认分别识别为等待`() {
        assertTrue(codexWaitingForInput(approval("Would you like to run the following command?")))
        assertTrue(codexWaitingForInput(approval("Would you like to make the following edits?")))
        assertTrue(codexWaitingForInput(approval("Would you like to grant these permissions?")))
        assertTrue(codexWaitingForInput(approval("Would you like to send input to the existing terminal?")))
    }

    @Test fun `实际问题的提交答案栏识别为等待`() {
        assertTrue(codexWaitingForInput("""
            Which approach?
            › 1. Keep the existing design
              2. Replace it
            Enter to submit answer
        """.trimIndent()))
    }

    @Test fun `历史确认下方已恢复执行时不继续等待`() {
        assertFalse(codexWaitingForInput(approval("Would you like to run the following command?") +
            "\nWorking...\nesc to interrupt"))
    }

    @Test fun `普通文本与模型选择菜单不能冒充审批等待`() {
        assertFalse(codexWaitingForInput("Would you like to run the following command?\nExplaining approval behavior"))
        assertFalse(codexWaitingForInput("1. Yes\n2. No\nPress enter to confirm or esc to cancel"))
        assertFalse(codexWaitingForInput("Select a model\n› 1. Model A\n2. Model B\nenter select · esc back"))
    }

    @Test fun `等待观察跟随活动缓冲并在编辑器关闭时释放`() {
        val code = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/CodexInputWaiting.kt").normalized
        assertTrue(code.contains("view.outputModels.active.collect"))
        assertTrue(code.contains("binding.activeModelChanged(it)"))
        assertTrue(code.contains("store.setWaiting(file.sessionKey, codexWaitingForInput(screen))"))
        assertTrue(code.contains("binding.unbind()"))
        assertTrue(code.contains("Disposer.register(parent, Disposable { job.cancel() })"))
    }
}
