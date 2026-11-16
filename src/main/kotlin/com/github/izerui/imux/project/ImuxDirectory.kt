package com.github.izerui.imux.project

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * 确保工程里的 `.imux/` 不进 Git：目录下放一个内容为 `*` 的 `.gitignore`。
 *
 * 不依赖 `.git` 在哪一层：IDE 只打开了大仓库里的一个子目录时，工程根下没有 `.git`，
 * 写 `.git/info/exclude` 防不住，外层仓库仍会看到 `.imux/` 下的文件。已有 `.gitignore` 时不动。
 * 这只是附带的保护：写不进去（权限、文件被锁）只记日志，不让调用方保存附件、写报告跟着失败。
 */
fun ensureImuxIgnored(projectRoot: Path) {
    val ignore = projectRoot.resolve(".imux").resolve(".gitignore")
    if (Files.exists(ignore)) return
    try {
        Files.createDirectories(ignore.parent)
        Files.writeString(ignore, "*\n")
    } catch (e: Exception) {
        Logger.getInstance("#com.github.izerui.imux.project.ImuxDirectory").warn("写入 .imux/.gitignore 失败：${e.javaClass.simpleName}")
    }
}
