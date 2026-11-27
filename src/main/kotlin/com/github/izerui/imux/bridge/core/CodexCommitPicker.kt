package com.github.izerui.imux.bridge.core

private val commitStyle = Regex("""\x1b\[[0-9;:]*m""")
private const val commitTitle = "Select a commit to review"
private const val branchTitle = "Select a base branch"
private val reviewTitles = setOf(commitTitle, branchTitle, "Select a review preset", "Custom review instructions")

internal fun reviewHeader(lines: List<String>, footer: Int): Int {
    if (footer < 0) return -1
    val end = lines[footer].trimStart()
    if (end !in setOf("enter select · esc back", "enter submit · esc back")) return -1
    // The header precedes the input field and a contiguous block of option rows.
    // The same words inside that block belong to an option or to the search query.
    for (index in 0 until footer) {
        val title = lines[index].trimStart()
        if (title !in reviewTitles || (title == "Custom review instructions") != (end == "enter submit · esc back")) continue
        val body = (index + 1 until footer).filter { lines[it].isNotBlank() }
        if (body.isEmpty()) continue
        if (title == "Custom review instructions" && lines[body.first()].startsWith("›")) return index
        if (title == "Select a review preset") {
            if (body.all { lines[it].matches(Regex("""(?:› |  )[1-4]\. .+""")) || lines[it].startsWith("     ") } &&
                body.count { lines[it].startsWith("› ") } == 1) return index
            continue
        }
        val field = body.dropWhile { title == branchTitle && lines[it].trimStart().startsWith("Current branch: ") }.firstOrNull() ?: continue
        if (!lines[field].startsWith("  ")) continue
        val items = body.dropWhile { it <= field }
        if (items.isNotEmpty() && items.last() - items.first() + 1 == items.size &&
            items.all { lines[it].startsWith("  ") || lines[it].startsWith("› ") || lines[it] in setOf("↑", "↓", "no matches") } &&
            items.any { lines[it].startsWith("› ") || lines[it] == "no matches" }
        ) return index
    }
    return -1
}

internal fun reviewPickerTitle(screen: String): String? {
    val lines = commitStyle.replace(screen, "").lines().map(String::trimEnd)
    return lines.getOrNull(reviewHeader(lines, lines.indexOfLast(String::isNotBlank)))?.trimStart()
}

/** Convert the visible commit picker to the one-row-per-option format used by CodexReviewMenu. */
fun normalizeCommitPickerScreen(screen: String, subjects: List<String>): String =
    if (reviewPickerTitle(screen) == commitTitle) normalizeGitPickerScreen(screen, subjects) else screen

internal fun normalizeGitPickerScreen(screen: String, entries: List<String>): String {
    val raw = screen.lines()
    val lines = raw.map { commitStyle.replace(it, "").trimEnd() }
    val footer = lines.indexOfLast { it.isNotBlank() }
    if (footer < 0 || lines[footer].trimStart() != "enter select · esc back") return screen
    val title = reviewHeader(lines, footer)
    if (title < 0 || lines[title].trimStart() !in setOf(branchTitle, commitTitle)) return screen
    val branch = lines[title].trimStart() == branchTitle

    val field = (title + 1 until footer).firstOrNull {
        lines[it].isNotBlank() && !(branch && lines[it].trimStart().startsWith("Current branch: "))
    }
    val hasField = field != null && lines[field].startsWith("  ")
    val start = if (hasField) field!! + 1 else title + 1
    val first = (start until footer).firstOrNull { lines[it].isNotBlank() } ?: footer
    val end = (first until footer).lastOrNull { lines[it].isNotBlank() }?.plus(1) ?: first
    val previous = first < end && lines[first] == "↑"
    val next = first < end && lines[end - 1] == "↓"
    val itemsStart = first + if (previous) 1 else 0
    val itemsEnd = end - if (next) 1 else 0
    val grouped = if (hasField) groupCommitRows(lines.subList(itemsStart, itemsEnd), entries) else null
    return buildList {
        addAll(raw.take(first))
        if (!hasField) add(if (branch) "  Type to search branches" else "  Type to search commits")
        if (grouped == null) {
            add("no matches")
        } else {
            if (previous) add(raw[first])
            addAll(grouped)
            if (next) add(raw[end - 1])
        }
        addAll(raw.drop(end))
    }.joinToString("\n")
}

private data class CommitGroup(val subject: String, val end: Int)

private fun groupCommitRows(rows: List<String>, subjects: List<String>): List<String>? {
    val selected = rows.indices.singleOrNull { rows[it].startsWith("› ") } ?: return null
    if (subjects.isEmpty() || rows.any { !it.startsWith("  ") && !it.startsWith("› ") }) return null
    val fragments = rows.map { it.drop(2).trim() }
    if (fragments.any(String::isEmpty)) return null
    val counts = subjects.filter { it.isNotBlank() && it.none(Char::isISOControl) }.groupingBy { it }.eachCount()
    val byInitial = counts.keys.groupBy { it.first() }

    // Count complete suffix partitions, saturating at two. Equal Git subjects give the same
    // visible option, so they count once here and their multiplicity is checked on reconstruction.
    val ways = IntArray(rows.size + 1)
    ways[rows.size] = 1
    val groups = arrayOfNulls<CommitGroup>(rows.size)
    for (start in rows.indices.reversed()) {
        for (subject in byInitial[fragments[start].first()].orEmpty()) {
            var offset = 0
            var row = start
            while (row < rows.size && subject.startsWith(fragments[row], offset)) {
                offset += fragments[row++].length
                if (offset == subject.length) {
                    if (ways[row] > 0) {
                        groups[start] = CommitGroup(subject, row)
                        ways[start] = (ways[start] + ways[row]).coerceAtMost(2)
                    }
                    break
                }
                if (row == selected) break
                while (offset < subject.length && subject[offset].isWhitespace()) offset++
            }
            if (ways[start] == 2) break
        }
    }
    if (ways[0] != 1) return null
    val remaining = counts.toMutableMap()
    return buildList {
        var start = 0
        while (start < rows.size) {
            val group = groups[start] ?: return null
            val count = remaining.getValue(group.subject)
            if (count == 0) return null
            remaining[group.subject] = count - 1
            add((if (start == selected) "› " else "  ") + group.subject)
            start = group.end
        }
    }
}
