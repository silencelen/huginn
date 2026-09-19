package com.silencelen.huginn.ui

/**
 * What a chat is CALLED, wherever one is listed.
 *
 * Both clients draw chat rows in four places each — the list, the header, the
 * palette, the send-to sheet — and every one of them used `title ?: "Untitled"`,
 * which repeats the daemon's answer without reading it. One thing is wrong
 * with that answer, and it is not the daemon's fault:
 *
 *  - a title is `humanizeUserText(text).slice(0, 60)`, cut at sixty characters
 *    with nothing said about it, so a long first message arrives already
 *    truncated and the header drew "…the latest huginn windows deskto" with
 *    clear space beside it. A hard cut with no mark reads as a rendering fault.
 */
object ChatRules {

    /**
     * Where the daemon cuts a title — `huginn-appd.js`, `slice(0, 60)`.
     *
     * Mirrored rather than negotiated: nothing here changes the title, it only
     * notices that a title of exactly this length probably lost something.
     */
    const val DAEMON_TITLE_MAX: Int = 60

    /** Characters that end a sentence, i.e. evidence the title was NOT cut. */
    private const val ENDINGS: String = ".!?…"

    /**
     * A chat's title, with the ellipsis the daemon's slice did not add.
     *
     * Exactly [DAEMON_TITLE_MAX] characters AND not ending on a sentence is the
     * whole test. Sixty characters is a length rather than a proof, so a message
     * that genuinely finished on a full stop is left alone — marking that would
     * invent a truncation, which is the same class of error in the other
     * direction.
     */
    fun title(raw: String?): String? {
        val t = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (t.length != DAEMON_TITLE_MAX) return t
        if (t.last() in ENDINGS) return t
        return "$t…"
    }

}
