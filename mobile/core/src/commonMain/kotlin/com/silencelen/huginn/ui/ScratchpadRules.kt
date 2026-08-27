package com.silencelen.huginn.ui

/**
 * What a scratchpad may be called, and what a message looks like once one has
 * been attached to it.
 *
 * Both halves are here for the same reason [AttachmentText] exists: the rules are
 * enforced by the daemon and the FRAMES are written by it, so a client that
 * carried its own copy of either would drift from the thing actually holding the
 * pages. Validating in the editor is a courtesy — it turns a round trip into an
 * inline note — and the server still decides.
 */
object ScratchpadRules {

    /** The pad every install has, and the one a reference with no id resolves to. */
    const val MAIN_NAME: String = "Main"

    const val MAX_NAME: Int = 60
    const val MAX_CONTENT: Int = 100_000

    /** A name as the daemon will store it: one line, no controls, trimmed. */
    fun cleanName(raw: String): String =
        raw.map { if (it.isControlish()) ' ' else it }
            .joinToString("")
            .replace(WHITESPACE_RUN, " ")
            .trim()
            .take(MAX_NAME)

    private fun Char.isControlish(): Boolean = this.code < 0x20 || (this.code in 0x7f..0x9f)

    private val WHITESPACE_RUN = Regex("""\s+""")

    /**
     * Why this name cannot be used, or null — the same three answers the daemon
     * gives, in the same order, so the inline note and the eventual refusal say
     * the same thing.
     *
     * @param taken the names already in use. Compared case-INSENSITIVELY: two rows
     *   that read the same is how the wrong page gets attached to a message.
     */
    fun nameProblem(raw: String, taken: List<String> = emptyList()): String? {
        val name = cleanName(raw)
        if (name.isEmpty()) return "a page needs a name"
        // The double quote is the frame's own delimiter — see [chatFrame]. A name
        // carrying one would end the marker early and leave its tail sitting in
        // the user's own message.
        if ('"' in name) return "a page name cannot contain a double quote"
        if (raw.trim().length > MAX_NAME) return "a page name is at most $MAX_NAME characters"
        val lower = name.lowercase()
        if (taken.any { cleanName(it).lowercase() == lower }) return "there is already a page with that name"
        return null
    }

    /** Why this content cannot be saved, or null. */
    fun contentProblem(text: String): String? =
        if (text.length > MAX_CONTENT) "a page holds at most $MAX_CONTENT characters" else null

    // -------------------------------------------------------------- the frames
    //
    // ⚠ THESE ARE COPIES, and the originals are `chatFrame` / `sessionFrame` in
    // the daemon's lib/scratchpads.js. Nothing here writes a frame — the daemon
    // composes server-side — so these exist only to UNDO one for a reader. A
    // wording change made there and not here leaves the marker sitting raw in the
    // sender's own message, which is exactly what the attachment markers next
    // door are careful about for the same reason.

    /**
     * The chat frame, non-greedy on the closing line.
     *
     * ⚠ THE OPPOSITE CHOICE FROM rounds.js's report scanner, and right for the
     * opposite reason. That one had to find the true end of a JSON block, so
     * stopping at the first fence destroyed real reports. This is display only:
     * stopping at the first `[End scratchpad]` can at worst leave a tail of the
     * page visible, while running to the last one could swallow words the person
     * actually typed. Leaving too much is recoverable by reading; hiding somebody's
     * own sentence is not.
     */
    private val CHAT_RE = Regex(
        """\[Scratchpad "([^"\n]{1,60})"\]\n[\s\S]*?\n\[End scratchpad\]\n*""",
    )

    /** The session frame: a path, never the page. One line, so it needs no scanner. */
    private val SESSION_RE = Regex("""\[Scratchpad "([^"\n]{1,60})" at [^\]\n]*\]\n*""")

    /** True when this message carries a page — cheap enough for a render path. */
    fun hasReference(text: String): Boolean = MARKER in text

    /**
     * The message as its sender should read it back: the page becomes a pill and
     * their own words stay exactly as typed.
     *
     * Blank in, blank out — a message is never ONLY a reference (the daemon
     * requires text), so there is no empty case to invent a caption for.
     */
    fun collapse(text: String): String {
        if (!hasReference(text)) return text
        return text
            .replace(CHAT_RE) { m -> "$PILL ${m.groupValues[1]}\n" }
            .replace(SESSION_RE) { m -> "$PILL ${m.groupValues[1]}\n" }
            .trim()
    }

    /** The name of the page this message carries, or null. */
    fun referencedName(text: String): String? {
        if (!hasReference(text)) return null
        return (CHAT_RE.find(text) ?: SESSION_RE.find(text))?.groupValues?.get(1)
    }

    private const val MARKER = "[Scratchpad \""
    private const val PILL = "📝"

    // ---------------------------------------------------------------- keys
    //
    // Written here rather than in either shell for the same reason DraftBook's
    // are: the phone and the desktop hold the same kinds of per-target state, and
    // two spellings of one key is two states that look like one.

    /** Which page a chat's composer will attach, remembered per chat. */
    fun chatRefKey(chatId: String): String = "padref:chat:$chatId"

    /** The same, for a session's composer. */
    fun sessionRefKey(name: String): String = "padref:sess:$name"
}
