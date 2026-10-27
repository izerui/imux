package com.github.izerui.imux.clarification

import com.intellij.util.diff.Diff

/**
 * 材料是已有记录的展开结果，只保存用户修改。删除部分仍可从此前记录恢复，不能再次完整存入历史。
 * 偏移与删除长度是原材料的 UTF-16 索引；新增文字完整保留，预览与上下文有固定上限。
 */
internal data class NotesEdit(
    val startOffset: Int,
    val deletedLength: Int,
    val removedPreview: String,
    val removalTruncated: Boolean,
    val insertedText: String,
    val beforeContext: String,
    val afterContext: String,
)

internal fun notesEdits(original: String, edited: String): List<NotesEdit> {
    if (original == edited) return emptyList()
    // 保留行尾，确保只增删末尾换行也能被识别，且偏移可以无损应用回原文。
    val before = original.split(AFTER_NEWLINE).toTypedArray()
    val after = edited.split(AFTER_NEWLINE).toTypedArray()
    val beforeOffsets = offsets(before)
    val afterOffsets = offsets(after)
    var change = Diff.buildChanges(before, after)
    return buildList {
        while (change != null) {
            val current = change
            var startBefore = beforeOffsets[current.line0]
            var startAfter = afterOffsets[current.line1]
            var endBefore = beforeOffsets[current.line0 + current.deleted]
            var endAfter = afterOffsets[current.line1 + current.inserted]
            // 行内公共部分不重复保存；否则长行末尾加一个字仍会复制整行历史。
            while (startBefore < endBefore && startAfter < endAfter && original[startBefore] == edited[startAfter]) {
                startBefore++
                startAfter++
            }
            if (startBefore > 0 && startBefore < original.length &&
                Character.isSurrogatePair(original[startBefore - 1], original[startBefore])
            ) {
                startBefore--
                startAfter--
            }
            while (endBefore > startBefore && endAfter > startAfter && original[endBefore - 1] == edited[endAfter - 1]) {
                endBefore--
                endAfter--
            }
            if (endBefore > 0 && endBefore < original.length &&
                Character.isSurrogatePair(original[endBefore - 1], original[endBefore])
            ) {
                endBefore++
                endAfter++
            }
            if (startBefore != endBefore || startAfter != endAfter) {
                val removedPoints = original.codePointCount(startBefore, endBefore)
                val previewEnd = original.offsetByCodePoints(startBefore, minOf(removedPoints, NOTES_EDIT_PREVIEW_POINTS))
                add(
                    NotesEdit(
                        startBefore,
                        endBefore - startBefore,
                        original.substring(startBefore, previewEnd),
                        removedPoints > NOTES_EDIT_PREVIEW_POINTS,
                        edited.substring(startAfter, endAfter),
                        original.substring(
                            original.offsetByCodePoints(startBefore, -minOf(original.codePointCount(maxOf(0, startBefore - 2 * NOTES_EDIT_CONTEXT_POINTS), startBefore), NOTES_EDIT_CONTEXT_POINTS)),
                            startBefore,
                        ),
                        original.substring(
                            endBefore,
                            original.offsetByCodePoints(endBefore, minOf(original.codePointCount(endBefore, minOf(original.length, endBefore + 2 * NOTES_EDIT_CONTEXT_POINTS)), NOTES_EDIT_CONTEXT_POINTS)),
                        ),
                    ),
                )
            }
            change = current.link
        }
    }
}

private fun offsets(lines: Array<String>): IntArray =
    IntArray(lines.size + 1).also { result ->
        lines.indices.forEach { result[it + 1] = result[it] + lines[it].length }
    }

private val AFTER_NEWLINE = Regex("(?<=\\n)")
internal const val NOTES_EDIT_PREVIEW_POINTS = 160
internal const val NOTES_EDIT_CONTEXT_POINTS = 60
