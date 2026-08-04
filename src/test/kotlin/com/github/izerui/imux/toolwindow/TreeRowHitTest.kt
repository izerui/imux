package com.github.izerui.imux.toolwindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class TreeRowHitTest {

    @Test
    fun `点击行内文字右侧空白仍命中该行`() {
        val child = DefaultMutableTreeNode("短标题")
        val root = DefaultMutableTreeNode("root").apply { add(child) }
        val tree = JTree(DefaultTreeModel(root)).apply {
            isRootVisible = false
            setSize(400, 200)
        }
        val bounds = tree.getRowBounds(0)
        val y = bounds.y + bounds.height / 2

        assertTrue("测试点应位于节点文字右侧", tree.width - 1 > bounds.x + bounds.width)
        assertNull("JTree 原生精确命中不包含行内空白", tree.getPathForLocation(tree.width - 1, y))
        assertEquals(child, tree.pathForRowAt(y)?.lastPathComponent)
    }

    @Test
    fun `点击最后一行下方不命中任何节点`() {
        val root = DefaultMutableTreeNode("root").apply {
            add(DefaultMutableTreeNode("会话"))
        }
        val tree = JTree(DefaultTreeModel(root)).apply {
            isRootVisible = false
            setSize(400, 200)
        }
        val bounds = tree.getRowBounds(0)

        assertNull(tree.pathForRowAt(bounds.y + bounds.height))
    }
}
