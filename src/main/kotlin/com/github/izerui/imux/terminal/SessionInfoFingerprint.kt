package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.claudeContextCachePath
import com.github.izerui.imux.session.projectGitBranch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal data class SessionInfoFingerprint(
    val transcript: TranscriptFingerprint?,
    val contextCache: TranscriptFingerprint?,
    val branch: String?,
)

internal data class TranscriptFingerprint(
    val path: String,
    val size: Long,
    val modifiedAt: Long,
)

internal fun sessionInfoFingerprint(
    transcript: Path?,
    projectPath: Path?,
    agentType: AgentType,
): SessionInfoFingerprint =
    SessionInfoFingerprint(
        transcript = transcript?.let(::fileFingerprint),
        contextCache = transcript
            ?.takeIf { agentType == AgentType.CLAUDE }
            ?.let(::claudeContextCachePath)
            ?.let(::fileFingerprint),
        branch = projectPath?.let(::projectGitBranch),
    )

private fun fileFingerprint(path: Path): TranscriptFingerprint {
    val normalized = path.toAbsolutePath().normalize()
    val attributes = try {
        Files.readAttributes(normalized, BasicFileAttributes::class.java)
    } catch (_: java.io.IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }
    return TranscriptFingerprint(
        normalized.toString(),
        attributes?.size() ?: -1,
        attributes?.lastModifiedTime()?.toMillis() ?: -1,
    )
}
