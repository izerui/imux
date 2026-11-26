package com.github.izerui.imux.code

/** VFS events identify a path, not its writer. Overlapping turns in the same tree are ambiguous. */
internal class EditCheckTurns {
    internal class Turn(val root: String) {
        var paths: MutableSet<String>? = mutableSetOf()
        var ambiguous = false
        var stopping = false
    }

    private val active = mutableMapOf<String, Turn>()

    @Synchronized fun start(token: String, root: String) {
        val turn = Turn(root.trimEnd('/'))
        active.remove(token)
        for (other in active.values) {
            if (turn.root == other.root || turn.root.startsWith("${other.root}/") || other.root.startsWith("${turn.root}/")) {
                other.ambiguous = true
                other.paths = null
                turn.ambiguous = true
                turn.paths = null
            }
        }
        active[token] = turn
    }

    @Synchronized fun record(path: String) {
        for (turn in active.values) {
            if (path.startsWith("${turn.root}/")) turn.paths?.add(path)
        }
    }

    @Synchronized fun claim(token: String): Turn? {
        val turn = active[token] ?: return null
        if (turn.stopping) return null
        turn.stopping = true
        return turn
    }

    @Synchronized fun owns(token: String, turn: Turn) = active[token] === turn && !turn.ambiguous

    @Synchronized fun takeFiles(token: String, turn: Turn): Set<String> {
        if (!owns(token, turn)) return emptySet()
        return turn.paths.orEmpty().also { turn.paths = null }
    }

    @Synchronized fun finish(token: String, turn: Turn) { active.remove(token, turn) }
    @Synchronized fun awaitRepair(token: String, turn: Turn): Boolean {
        if (!owns(token, turn)) return false
        turn.stopping = false
        return true
    }
    @Synchronized fun remove(token: String) { active.remove(token) }
    @Synchronized fun clear() { active.clear() }
}

internal suspend fun finishEditCheck(
    turns: EditCheckTurns,
    token: String,
    turn: EditCheckTurns.Turn,
    enabled: () -> Boolean,
    refresh: suspend () -> Unit,
    inspect: suspend (Set<String>) -> String?,
    budgetMillis: Long = 35_000L,
): String? {
    var repairing = false
    try {
        val report = kotlinx.coroutines.withTimeoutOrNull(budgetMillis) {
            if (!enabled() || !turns.owns(token, turn)) return@withTimeoutOrNull null
            refresh()
            val files = turns.takeFiles(token, turn)
            if (files.isEmpty() || !enabled()) null else inspect(files)
        }
        if (report != null && enabled() && turns.awaitRepair(token, turn)) {
            repairing = true
            return report
        }
        return null
    } finally {
        if (!repairing) turns.finish(token, turn)
    }
}
