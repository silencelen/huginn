package com.silencelen.huginn.ui

import com.silencelen.huginn.data.Scratchpad

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
    fun cleanName(raw: String): String = normalized(raw).take(MAX_NAME)

    /**
     * The same normalisation WITHOUT the truncation, which is what the length
     * rule is actually measured against.
     *
     * ⚠ THE DAEMON MEASURES THIS, not the raw string — `lib/scratchpads.js`
     * applies the control-to-space and whitespace-collapse passes before it
     * compares. Measuring `raw.trim().length` here refused names the server would
     * have accepted (a name padded with tabs, or carrying a newline from a paste),
     * which is the worst way for a courtesy check to be wrong: the editor says no
     * to something that is allowed, and there is no way to argue with it.
     */
    private fun normalized(raw: String): String =
        raw.map { if (it.isControlish()) ' ' else it }
            .joinToString("")
            .replace(WHITESPACE_RUN, " ")
            .trim()

    private fun Char.isControlish(): Boolean = this.code < 0x20 || (this.code in 0x7f..0x9f)

    private val WHITESPACE_RUN = Regex("""\s+""")

    /**
     * The pages in the order every surface lists them: Main first, then by name.
     *
     * ⚠ NOT by "recently edited", which is what the list arrives in. A list that
     * re-sorts while somebody is typing moves the row under the cursor — during
     * testing that put two paragraphs into the wrong page, because the page you
     * clicked is not the page that is there a second later. Name order is boring
     * and boring is the point: the row a person reaches for is where it was last
     * time. Case-insensitive, for the same reason the uniqueness check is.
     *
     * Sorted HERE rather than trusted from the daemon: an older daemon sends
     * whatever it sends, and one client silently ordering pages differently from
     * the other is the same page in two places.
     */
    fun ordered(pads: List<Scratchpad>): List<Scratchpad> =
        pads.sortedWith(
            compareBy<Scratchpad> { if (it.main) 0 else 1 }
                .thenBy { cleanName(it.name).lowercase() }
                // A stable last word, so two pages that read the same never swap
                // places between polls.
                .thenBy { it.id },
        )

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
        // Measured the way the daemon measures it — see [normalized].
        if (normalized(raw).length > MAX_NAME) return "a page name is at most $MAX_NAME characters"
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
     * The chat frame, non-greedy on the closing line, in both of the shapes the
     * daemon writes: plain, and TAGGED with a six-hex marker when the page's own
     * text contains a line starting `[End scratchpad`.
     *
     * ⚠ THE OPPOSITE CHOICE FROM rounds.js's report scanner, and right for the
     * opposite reason. That one had to find the true end of a JSON block, so
     * stopping at the first fence destroyed real reports. This is display only:
     * stopping at the first `[End scratchpad]` can at worst leave a tail of the
     * page visible, while running to the last one could swallow words the person
     * actually typed. Leaving too much is recoverable by reading; hiding somebody's
     * own sentence is not. The tag is what removes the choice when it matters: a
     * page that writes about this very marker gets a closer only its own opener
     * can name.
     *
     * ⚠⚠ TWO BRANCHES, NOT ONE OPTIONAL GROUP, AND THIS IS A LANGUAGE DIFFERENCE
     * WITH TEETH. The daemon's copy is one pattern with `( #[0-9a-f]{6})?` and a
     * `\2` backreference on the closer, which is correct in JavaScript: a
     * backreference to a group that did not participate matches the EMPTY string
     * there. In Java — which is what Kotlin's Regex is — the same backreference
     * FAILS to match, so a literal port stops collapsing every untagged frame,
     * which is every frame written before the tag existed. The symptom would be a
     * raw `[Scratchpad "…"]` marker sitting in the sender's own message, in the
     * one client the daemon's tests cannot see. Verified against java.util.regex
     * rather than reasoned about: untagged=false with the ported pattern.
     *
     * So: the tagged branch keeps the backreference (a closer must carry its
     * OPENER's tag — a mismatched one is not a frame and must not collapse), and
     * the untagged branch is spelled out beside it. The name is group 1 in the
     * first branch and group 3 in the second; [frameName] answers whichever
     * matched.
     */
    private val CHAT_RE = Regex(
        """\[Scratchpad "([^"\n]{1,60})"( #[0-9a-f]{6})\]\n[\s\S]*?\n\[End scratchpad\2\]\n*""" +
            """|\[Scratchpad "([^"\n]{1,60})"\]\n[\s\S]*?\n\[End scratchpad\]\n*""",
    )

    /** The page's name out of whichever branch of [CHAT_RE] matched. */
    private fun frameName(m: MatchResult): String =
        m.groupValues[1].ifEmpty { m.groupValues.getOrElse(3) { "" } }

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
            .replace(CHAT_RE) { m -> "$PILL ${frameName(m)}\n" }
            .replace(SESSION_RE) { m -> "$PILL ${m.groupValues[1]}\n" }
            .trim()
    }

    /** The name of the page this message carries, or null. */
    fun referencedName(text: String): String? {
        if (!hasReference(text)) return null
        CHAT_RE.find(text)?.let { return frameName(it) }
        return SESSION_RE.find(text)?.groupValues?.get(1)
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
