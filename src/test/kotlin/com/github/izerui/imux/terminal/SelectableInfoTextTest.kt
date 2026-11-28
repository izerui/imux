package com.github.izerui.imux.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.SwingUtilities

class SelectableInfoTextTest {
    @Test
    fun `只读文字可以选择且重复刷新保留选区`() {
        SwingUtilities.invokeAndWait {
            val field = SelectableInfoText("model-name")
            assertFalse(field.isEditable)
            assertTrue(field.isFocusable)
            field.select(0, 5)
            assertEquals("model", field.selectedText)
            field.text = "model-name"
            assertEquals("model", field.selectedText)
        }
    }
}
