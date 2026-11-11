package com.silencelen.huginn.ui

import com.silencelen.huginn.data.AgentRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How a background agent's permission request is written for a person, in both
 * shells — the same reason [ModelLabels] exists: the words are the half that
 * must never disagree between the phone and the desktop.
 *
 * A background agent's request has NO dialog on the session's own pane, so it
 * is not the session "needing you" in the yellow sense: the row says it quietly
 * ([rowMark]) and the card says what the agent wants to do and when it lapses.
 */
object AgentRequestLabels {

    /** The card's header line. */
    const val HEADER: String = "Background agent asks"

    const val ALLOW: String = "Allow"
    const val DENY: String = "Deny"

    /** Shown when the daemon answers 409: the request's dialog is on the pane. */
    const val ON_PANE: String = "Answer it on the Screen tab"

    /** Input keys that say most about a request, in the order they are worth showing. */
    private val TELLING_KEYS = listOf(
        "command", "file_path", "path", "notebook_path", "url", "pattern", "query", "description", "prompt",
    )

    /** How long the one-line summary of a non-Bash request may run. */
    const val SUMMARY_MAX: Int = 120

    /** "general-purpose wants to run" / "An agent wants to use WebFetch". */
    fun headline(r: AgentRequest): String {
        val who = r.agentType?.trim()?.takeIf { it.isNotEmpty() } ?: "An agent"
        return if (isBash(r)) "$who wants to run" else "$who wants to use ${toolName(r)}"
    }

    /**
     * The monospace body: a Bash request's whole command (never summarised — the
     * person is approving exactly these words), otherwise a one-line summary of
     * the input. Null when there is nothing to show.
     */
    fun body(r: AgentRequest): String? {
        if (isBash(r)) {
            return (r.command ?: (r.input?.get("command") as? JsonPrimitive)?.contentOrNullSafe())
                ?.takeIf { it.isNotBlank() }
        }
        return summary(r.input)
    }

    /** Whether [body] is a command (drawn whole, wrapped) rather than a one-line summary. */
    fun isBash(r: AgentRequest): Boolean = r.tool == "Bash"

    /** An MCP tool reads as `server · tool`, everything else as its own name. */
    fun toolName(r: AgentRequest): String {
        val t = r.tool.ifBlank { "a tool" }
        val m = Regex("^mcp__([^_]+(?:_[^_]+)*?)__(.+)$").find(t) ?: return t
        return "${m.groupValues[1]} · ${m.groupValues[2]}"
    }

    /**
     * A compact one-line view of a tool input: the telling keys first, then the
     * rest, `key: value` joined by `  ·  `, cut at [SUMMARY_MAX]. Null for an
     * empty input.
     */
    fun summary(input: JsonObject?): String? {
        if (input == null || input.isEmpty()) return null
        val ordered = TELLING_KEYS.filter { it in input } + input.keys.filter { it !in TELLING_KEYS }
        val parts = ordered.mapNotNull { k -> flat(input[k])?.let { v -> "$k: $v" } }
        if (parts.isEmpty()) return null
        val line = parts.joinToString("  ·  ")
        return if (line.length <= SUMMARY_MAX) line else line.take(SUMMARY_MAX - 1).trimEnd() + "…"
    }

    /**
     * "Declined automatically in 7 min", "… in under a minute", or null when the
     * daemon gave no deadline or the clock is unknown. Past the deadline the card
     * is about to go; it says so rather than counting negative minutes.
     */
    fun expiry(r: AgentRequest, nowMs: Long): String? {
        if (r.expiresAt <= 0 || nowMs <= 0) return null
        val left = r.expiresAt - nowMs
        if (left <= 0) return "Being declined now"
        if (left < 60_000) return "Declined automatically in under a minute"
        val min = (left + 59_999) / 60_000
        return "Declined automatically in $min min"
    }

    /** The list row's quiet mark, or null with nothing waiting. */
    fun rowMark(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "agent asks"
        else -> "$count agent requests"
    }

    private fun flat(e: JsonElement?): String? = when (e) {
        null, JsonNull -> null
        is JsonPrimitive -> e.contentOrNullSafe()?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
        is JsonArray -> if (e.isEmpty()) null else "[${e.size}]"
        is JsonObject -> if (e.isEmpty()) null else "{…}"
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content
}
