package com.github.izerui.imux.bridge

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener

/** 保存失败后仍可取消关闭；只有确定关闭的事件才能停止桥接。 */
internal fun bridgeProjectCloseListener(owner: Project, onClosing: () -> Unit): ProjectCloseListener =
    object : ProjectCloseListener {
        override fun projectClosing(project: Project) {
            if (project === owner) onClosing()
        }
    }
