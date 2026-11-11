package com.silencelen.huginn.ui

import com.silencelen.huginn.data.AgentRequest
import kotlinx.serialization.json.Json
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

    /** Shown when the daemon answers 412: what the card drew is not what is waiting. */
    const val CHANGED: String = "This request changed since the card was drawn: read it again"

    /** Why Allow is still disabled: the request has not been scrolled to its end. */
    const val READ_FIRST: String = "Scroll to the end of the request first."

    /** Input keys that say most about a request, in the order they are worth showing. */
    private val TELLING_KEYS = listOf(
        "command", "file_path", "path", "notebook_path", "url", "pattern", "query", "description", "prompt",
    )

    /** Keys whose value is a filesystem path, which gets its resolved form beside it. */
    private val PATH_KEYS = setOf("file_path", "path", "notebook_path")

    private val pretty = Json { prettyPrint = true }

    /** "general-purpose wants to run" / "An agent wants to use WebFetch". */
    fun headline(r: AgentRequest): String {
        val who = r.agentType?.trim()?.takeIf { it.isNotEmpty() } ?: "An agent"
        return if (isBash(r)) "$who wants to run" else "$who wants to use ${toolName(r)}"
    }

    /**
     * Everything the person is approving, never cut: a Bash request's whole
     * command, otherwise every key of the input ([detail]). Null when there is
     * nothing to show. The card draws it through [PromptDisplay.visible], so no
     * whitespace run, control or bidi character can hide part of it, and Allow
     * waits until it has been scrolled to its end.
     *
     * ⚠ NEVER A SUMMARY (security scan 2026-11-11, F1). The first card cut a
     * non-Bash input to 120 characters in two ellipsized lines while Allow
     * approved all of it: a `file_path` padded with no-break spaces hid
     * `/../../.ssh/authorized_keys` past the cut. A background agent has no
     * dialog anywhere, so this card is the only gate.
     */
    fun body(r: AgentRequest): String? {
        if (isBash(r)) {
            return (r.command ?: (r.input?.get("command") as? JsonPrimitive)?.contentOrNullSafe())
                ?.takeIf { it.isNotBlank() }
        }
        return detail(r.input)
    }

    /** Whether [body] is a shell command rather than a tool input. */
    fun isBash(r: AgentRequest): Boolean = r.tool == "Bash"

    /** Whether the card may offer Allow: the daemon gave the digest an Allow is bound to. */
    fun canAllow(r: AgentRequest): Boolean = !r.digest.isNullOrBlank()

    /** An MCP tool reads as `server · tool`, everything else as its own name. */
    fun toolName(r: AgentRequest): String {
        val t = r.tool.ifBlank { "a tool" }
        val m = Regex("^mcp__([^_]+(?:_[^_]+)*?)__(.+)$").find(t) ?: return t
        return "${m.groupValues[1]} · ${m.groupValues[2]}"
    }

    /**
     * A tool input in full, one key after another, the telling keys first:
     * `key: value` for a one-line string or a number, `key:` and the value on
     * the lines below for a multi-line string, pretty JSON for an object or
     * array. A path that `.`, `..` or `//` would move is followed by the path
     * it resolves to. Null for an empty input.
     */
    fun detail(input: JsonObject?): String? {
        if (input == null || input.isEmpty()) return null
        val ordered = TELLING_KEYS.filter { it in input } + input.keys.filter { it !in TELLING_KEYS }
        return ordered.joinToString("\n") { k ->
            val e = input.getValue(k)
            val v = when {
                e is JsonPrimitive && e.isString -> e.content
                e is JsonNull -> "null"
                e is JsonPrimitive -> e.content
                else -> pretty.encodeToString(JsonElement.serializer(), e)
            }
            val line = if ('\n' in v) "$k:\n$v" else "$k: $v"
            val resolved = (e as? JsonPrimitive)?.takeIf { k in PATH_KEYS && it.isString }?.content
                ?.let { resolvedPath(it) }
            if (resolved != null) "$line\n  → resolves to $resolved" else line
        }
    }

    /**
     * [path] with `.`, `..` and repeated slashes resolved, lexically, or null
     * when that changes nothing. `..` above the root stays at the root.
     */
    fun resolvedPath(path: String): String? {
        if (path.isEmpty()) return null
        val abs = path.startsWith('/')
        val out = ArrayList<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeAt(out.size - 1) else if (!abs) out.add("..")
                else -> out.add(seg)
            }
        }
        val norm = (if (abs) "/" else "") + out.joinToString("/")
        val same = norm == path || (path.endsWith('/') && norm == path.trimEnd('/'))
        return if (same || norm.isEmpty()) null else norm
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

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content
}
