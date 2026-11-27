package com.github.izerui.imux.bridge.core

/** Codex 0.171.0 viewport. [selected] indexes [options] on this page, or is -1 for a text/empty view. */
data class CodexReviewMenu(
    val title: String,
    val options: List<String>,
    val selected: Int,
    val hasPreviousPage: Boolean = false,
    val hasNextPage: Boolean = false,
    val searchQuery: String? = null,
    val hasCustomDraft: Boolean = false,
) {
    val isCustom: Boolean get() = title == CUSTOM

    fun sameChoices(other: CodexReviewMenu): Boolean = copy(selected = other.selected) == other

    fun keysFor(reply: String): List<String>? {
        val command = TerminalKeys.normalizeReply(reply)
        if (command == "0") return listOf("\u001b")
        if (isCustom) {
            val text = reply.replace("\r\n", "\n")
            if (hasCustomDraft || text.isBlank() || text.any { it.isISOControl() && it != '\n' && it != '\t' }) return null
            return listOf(paste(text), "\r")
        }
        if (command.equals("next", true) && hasNextPage) return listOf("\u001b[6~")
        if (command.equals("prev", true) && hasPreviousPage) return listOf("\u001b[5~")
        if (searchQuery != null && reply.startsWith('/')) {
            val query = reply.drop(1)
            if (query == searchQuery || query.any(Char::isISOControl)) return null
            return buildList {
                repeat(searchQuery.codePointCount(0, searchQuery.length)) { add("\u007f") }
                if (query.isNotEmpty()) add(paste(query))
            }
        }
        val number = command.toIntOrNull() ?: return null
        if (number !in 1..options.size || selected !in options.indices) return null
        val delta = number - 1 - selected
        return List(kotlin.math.abs(delta)) { if (delta < 0) "\u001b[A" else "\u001b[B" } + "\r"
    }

    companion object {
        private const val PRESET = "Select a review preset"
        private const val BRANCH = "Select a base branch"
        private const val COMMIT = "Select a commit to review"
        private const val CUSTOM = "Custom review instructions"
        private val presets = listOf("Review against a base branch", "Review uncommitted changes", "Review a commit", CUSTOM)
        private val presetRow = Regex("""(› |  )([1-4])\. (\S.*)""")
        private const val CUSTOM_PLACEHOLDER = "› Type instructions and press Enter"
        private const val SELECT = "enter select · esc back"
        private const val SUBMIT = "enter submit · esc back"
        private val style = Regex("""\x1b\[[0-9;:]*m""")
        private fun paste(text: String) = "\u001b[200~$text\u001b[201~"

        fun parse(screen: String): CodexReviewMenu? {
            val lines = style.replace(screen, "").lineSequence().map(String::trimEnd).toList().dropLastWhile(String::isBlank)
            val footer = lines.lastOrNull()?.trimStart()
            if (footer != SELECT && footer != SUBMIT) return null
            val index = reviewHeader(lines, lines.lastIndex)
            if (index >= 0) {
                val title = lines[index].trimStart()
                val body = lines.subList(index + 1, lines.lastIndex).filter(String::isNotBlank)
                if (title == CUSTOM) {
                    if (footer == SUBMIT && body.firstOrNull()?.let { it.startsWith("› ") || it == "›" } == true) {
                        val placeholder = body.drop(1).all { it.startsWith("  ") } &&
                            body.joinToString(" ") { it.removePrefix("  ") } == CUSTOM_PLACEHOLDER
                        return CodexReviewMenu(title, emptyList(), -1, hasCustomDraft = !placeholder)
                    }
                } else if (footer == SELECT) {
                    if (title == PRESET) return parsePresets(body)
                    parseList(title, body)?.let { return it }
                }
            }
            return null
        }

        private fun parsePresets(rows: List<String>): CodexReviewMenu? {
            val options = presets.toMutableList()
            var cursor = 0
            var previous = 0
            var selected = -1
            while (cursor < rows.size) {
                val row = presetRow.matchEntire(rows[cursor++]) ?: return null
                val (marker, prefix, firstLine) = row.destructured
                val number = prefix.toInt()
                if (previous != 0 && number != previous + 1) return null
                previous = number
                if (marker == "› ") {
                    if (selected != -1) return null
                    selected = number - 1
                }
                val label = buildString {
                    append(firstLine)
                    while (cursor < rows.size && rows[cursor].startsWith("     ")) {
                        append(' ').append(rows[cursor++].drop(5))
                    }
                }
                val expected = presets[number - 1]
                if (number == 1 && label in listOf("$expected  (PR Style)", "$expected (PR Style)")) {
                    options[0] = "$expected  (PR Style)"
                } else if (label != expected) return null
            }
            return if (selected == -1) null else CodexReviewMenu(PRESET, options, selected)
        }

        private fun parseList(title: String, body: List<String>): CodexReviewMenu? {
            var rows = body
            var query: String? = null
            if (title != PRESET) {
                if (title == BRANCH && rows.firstOrNull()?.trimStart()?.startsWith("Current branch: ") == true) rows = rows.drop(1)
                val field = rows.firstOrNull()?.takeIf { it.startsWith("  ") }?.drop(2) ?: return null
                val placeholder = if (title == BRANCH) "Type to search branches" else "Type to search commits"
                query = if (field.isNotEmpty() && placeholder.startsWith(field)) "" else field
                rows = rows.drop(1)
                if (rows == listOf("no matches")) return CodexReviewMenu(title, emptyList(), -1, searchQuery = query)
            }
            val previous = rows.firstOrNull() == "↑"
            val next = rows.lastOrNull() == "↓"
            val items = rows.drop(if (previous) 1 else 0).dropLast(if (next) 1 else 0)
            val selected = items.indices.singleOrNull { items[it].startsWith("› ") } ?: return null
            val options = items.map { row ->
                if (!row.startsWith("› ") && !row.startsWith("  ")) return null
                row.drop(2)
            }
            return CodexReviewMenu(title, options, selected, previous, next, query)
        }
    }
}
