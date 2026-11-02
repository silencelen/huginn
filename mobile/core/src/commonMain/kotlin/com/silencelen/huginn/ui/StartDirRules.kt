package com.silencelen.huginn.ui

import com.silencelen.huginn.data.RecentDirs

/**
 * The New Session dialog's "Starting directory" field (appd 3.17: `POST
 * /v1/sessions` takes `cwd`).
 *
 * The field is OPTIONAL and the daemon decides what empty means (its default,
 * named by `GET /v1/sessions/recent-dirs`). What this rules out before the round
 * trip is only what cannot be a directory on the server at all; whether the path
 * EXISTS is the daemon's fact and comes back as its own 400, shown in the dialog
 * verbatim.
 */
object StartDirRules {
    const val LABEL: String = "Starting directory"

    /** The hint under an empty field, with the server default when it is known. */
    fun hint(recent: RecentDirs?): String {
        val d = recent?.default?.trim()?.takeIf { it.isNotEmpty() }
        return if (d == null) "Leave empty for the server's default directory."
        else "Leave empty for the server's default ($d)."
    }

    /**
     * Why the typed directory would be refused, as a sentence, or null when it
     * may be sent (blank included — blank is the default, not a problem).
     */
    fun cwdProblem(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if ('\n' in s || '\r' in s) return "a directory is one line"
        if (!s.startsWith("/")) return "an absolute path on the server, starting with /"
        return null
    }

    /** What goes on the wire: null for blank (so the key is omitted), else the trimmed path. */
    fun cwdOrNull(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }

    /**
     * The dropdown's rows: the recents, the default first when the daemon named
     * one, without duplicates and without the path already typed.
     */
    fun choices(recent: RecentDirs?, typed: String): List<String> {
        if (recent == null) return emptyList()
        val out = LinkedHashSet<String>()
        recent.default?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        recent.dirs.forEach { d -> d.trim().takeIf { it.isNotEmpty() }?.let { out += it } }
        out.remove(typed.trim())
        return out.toList()
    }
}
