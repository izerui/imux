package com.github.izerui.imux.project

import java.nio.file.Files
import java.nio.file.Path

/**
 * 确保工程里的 `.imux/` 不进 Git：目录下放一个内容为 `*` 的 `.gitignore`。
 *
 * 不依赖 `.git` 在哪一层：IDE 只打开了大仓库里的一个子目录时，工程根下没有 `.git`，
 * 写 `.git/info/exclude` 防不住，外层仓库仍会看到 `.imux/` 下的文件。已有 `.gitignore` 时不动。
 */
fun ensureImuxIgnored(projectRoot: Path) {
    val ignore = projectRoot.resolve(".imux").resolve(".gitignore")
    if (Files.exists(ignore)) return
    Files.createDirectories(ignore.parent)
    Files.writeString(ignore, "*\n")
}
