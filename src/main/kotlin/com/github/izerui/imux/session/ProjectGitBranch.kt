package com.github.izerui.imux.session

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

// Only HEAD is needed. Reading Git's metadata avoids adding a Git plugin dependency
// to every supported IDE or requiring a git executable on the IDE's PATH.
internal fun projectGitBranch(directory: Path): String? = try {
    var root: Path? = directory.toRealPath()
    var branch: String? = null
    while (root != null) {
        val marker = root.resolve(".git")
        if (Files.exists(marker)) {
            val gitDir = if (Files.isDirectory(marker)) marker else {
                val pointer = Files.readString(marker).trim()
                if (!pointer.startsWith("gitdir: ")) break
                root.resolve(pointer.removePrefix("gitdir: ")).normalize()
            }
            val head = Files.readString(gitDir.resolve("HEAD")).trim()
            // Reftable stores the actual HEAD elsewhere and leaves this placeholder.
            // Without a reftable reader, omit the row instead of reporting a fake branch.
            if (head == "ref: refs/heads/.invalid") break
            branch = head.takeIf { it.startsWith("ref: refs/heads/") }
                ?.removePrefix("ref: refs/heads/")?.takeIf { it.isNotBlank() }
            break
        }
        root = root.parent
    }
    branch
} catch (_: IOException) {
    null
} catch (_: IllegalArgumentException) {
    null
} catch (_: SecurityException) {
    null
}
