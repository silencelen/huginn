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

    // Moved verbatim from the desktop's RecentDirsMenu.kt (2026-11-04): both apps
    // label their Recent rows with it — the desktop measured into its own menu,
    // the phone measured into the house menu's row (StartDirField).
    /**
     * A directory as one line can show it: whole when it fits, otherwise cut in the
     * MIDDLE, keeping its last segments.
     *
     * ⚠ THE BUG (desktop 2.10.0, 2026-11-03): the New session dialog's Recent list
     * drew `/root/projects/apv2/assets/src/voice/…` for two different directories.
     * The menu cut every path at its end, and two recents under one project differ
     * exactly there — the leaf is what tells them apart, and the prefix every row
     * shares is what can go. So the first segment stays (which tree it is in), then
     * "…", then as many of the LAST segments as there is room for.
     */
    object DirLabel {

        const val ELLIPSIS: String = "…"

        /**
         * Every way to write [path] on one line, longest first: the path itself; its
         * first segment, "…" and fewer and fewer of its last segments; "…" and the
         * last segment alone; then the end of that segment, for a name too long to
         * show whole. Lazy: the first one that fits is usually the first one.
         */
        fun forms(path: String): Sequence<String> = sequence {
            val p = path.trim()
            yield(p)
            val segs = p.split('/').filter { it.isNotEmpty() }
            // Only a path with a middle has a middle to cut.
            if (segs.size >= 3) {
                val head = (if (p.startsWith("/")) "/" else "") + segs.first()
                for (keep in segs.size - 2 downTo 1) {
                    yield("$head/$ELLIPSIS/" + segs.takeLast(keep).joinToString("/"))
                }
            }
            val leaf = segs.lastOrNull() ?: return@sequence
            if (segs.size >= 2) yield("$ELLIPSIS/$leaf")
            for (n in leaf.length - 1 downTo 1) yield(ELLIPSIS + leaf.takeLast(n))
        }

        /** The longest form that [fits]; the shortest there is when none does. */
        fun fit(path: String, fits: (String) -> Boolean): String {
            var last = path.trim()
            for (form in forms(path)) {
                if (fits(form)) return form
                last = form
            }
            return last
        }

        /** [fit] with a character budget, for when nothing can be measured. */
        fun fit(path: String, maxChars: Int): String = fit(path) { it.length <= maxChars }
    }
}
