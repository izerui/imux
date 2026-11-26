package com.github.izerui.imux.session

import com.github.izerui.imux.model.AgentType
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal data class SessionUsage(
    val total: Long? = null,
    val last: Long? = null,
    val window: Long? = null,
    val model: String? = null,
    val skills: List<String> = emptyList(),
    val skillLocations: Map<String, String> = emptyMap(),
    val skillsReported: Boolean = false,
    val reportedContextPercent: Int? = null,
    val skillDescriptions: Map<String, String> = emptyMap(),
)

internal fun readSessionUsage(agent: AgentType, transcript: Path, onLine: () -> Unit): SessionUsage {
    val usage = Files.newBufferedReader(transcript).useLines { sessionUsage(agent, it.onEach { onLine() }) }
    if (agent != AgentType.CLAUDE) return usage
    // Claude HUD keys its last status-line snapshot by the normalized absolute transcript path.
    val path = transcript.toAbsolutePath().normalize()
    val projects = path.parent?.parent?.takeIf { it.fileName?.toString() == "projects" } ?: return usage
    val hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.toString().toByteArray(Charsets.UTF_8)))
    val cache = projects.resolveSibling("plugins").resolve("claude-hud/context-cache").resolve("$hash.json")
    val snapshot = try {
        Files.newBufferedReader(cache).use { JsonParser.parseReader(it) }.takeIf { it.isJsonObject }?.asJsonObject
    } catch (_: java.io.IOException) { null } catch (_: com.google.gson.JsonParseException) { null }
    val percent = snapshot?.number("used_percentage")?.takeIf { it in 0..100 }?.toInt() ?: return usage
    return usage.copy(reportedContextPercent = percent, window = snapshot.number("context_window_size")?.takeIf { it > 0 })
}

internal fun sessionUsage(agent: AgentType, lines: Sequence<String>): SessionUsage {
    var total: Long? = null
    var last: Long? = null
    var window: Long? = null
    var model: String? = null
    var skills = emptyList<String>()
    var locations = emptyMap<String, String>()
    var skillsReported = false
    val descriptions = mutableMapOf<String, String>()
    val messages = mutableMapOf<String, Long>()
    for (line in lines) {
        if (line.none { it == '{' }) continue
        val row = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
        val payload = row.obj("payload")
        if (agent == AgentType.CODEX) {
            when (row.text("type")) {
                "turn_context" -> model = payload?.text("model") ?: model
                "world_state" -> payload?.obj("state")?.obj("host_skills")?.text("body")?.let { body ->
                    skillsReported = true
                    descriptions.clear()
                    val roots = body.lineSequence().mapNotNull { line ->
                        ROOT_ENTRY.matchEntire(line)?.destructured?.let { (key, path) -> key to path }
                    }.toMap()
                    locations = body.lineSequence().filter { it.startsWith("- ") && " (file: " in it }
                        .associate { line ->
                            val path = line.substringAfterLast(" (file: ").removeSuffix(")")
                            val base = roots[path.substringBefore('/')]
                            val location = if (base != null && '/' in path) "$base/${path.substringAfter('/')}" else path
                            val entry = line.removePrefix("- ").substringBeforeLast(" (file: ")
                            val name = entry.substringBefore(": ")
                            entry.substringAfter(": ", "").trim().takeIf(String::isNotEmpty)?.let { descriptions[name] = it }
                            name to location
                        }
                    skills = locations.keys.sorted()
                }
                "event_msg" -> if (payload?.text("type") == "token_count") {
                    val info = payload.obj("info") ?: continue
                    total = info.obj("total_token_usage")?.number("total_tokens") ?: total
                    last = info.obj("last_token_usage")?.number("total_tokens") ?: last
                    window = info.number("model_context_window") ?: window
                }
            }
            continue
        }
        if (agent == AgentType.CLAUDE && row.text("type") == "attachment") {
            val attachment = row.obj("attachment")
            if (attachment?.text("type") == "skill_listing" && row.get("isSidechain")?.toString() != "true") {
                val names = attachment.get("names")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                val listed = names.filter { it.isJsonPrimitive && it.asJsonPrimitive.isString }.map { it.asString }
                if (attachment.get("isInitial")?.toString() == "true") descriptions.clear()
                attachment.text("content")?.lineSequence()?.filter { it.startsWith("- ") }?.forEach { line ->
                    val entry = line.removePrefix("- ")
                    val name = entry.substringBefore(": ")
                    val description = entry.substringAfter(": ", "").trim()
                    if (name in listed && description.isNotEmpty()) descriptions[name] = description
                }
                skills = (if (attachment.get("isInitial")?.toString() == "true") listed else skills + listed).distinct().sorted()
                skillsReported = true
            }
        }
        val message = row.obj("message") ?: continue
        if (message.text("role") != "assistant" || row.get("isSidechain")?.toString() == "true") continue
        val usage = message.obj("usage") ?: continue
        val count = if (agent == AgentType.PI) usage.number("totalTokens") else {
            listOf("input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens")
                .mapNotNull(usage::number).takeIf { it.isNotEmpty() }?.sum()
        } ?: continue
        val id = message.text("id") ?: row.text("id") ?: row.text("uuid") ?: continue
        messages[id] = count
        last = count
        model = message.text("model") ?: model
    }
    return SessionUsage(total ?: messages.values.takeIf { it.isNotEmpty() }?.sum(), last, window, model,
        skills, locations, skillsReported, skillDescriptions = descriptions)
}

private fun JsonObject.obj(key: String) = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
private val ROOT_ENTRY = Regex("""- `([^`]+)` = `([^`]+)`""")
private fun JsonObject.text(key: String) = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
private fun JsonObject.number(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull()?.takeIf { it >= 0 }
