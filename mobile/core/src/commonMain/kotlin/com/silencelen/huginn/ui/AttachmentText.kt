package com.silencelen.huginn.ui

/**
 * The text half of an attachment: the marker written INTO the message so Claude
 * knows there is a file, and the inverse that turns that marker back into
 * something a person wants to read.
 *
 * Shared rather than Android-only because the two halves must agree exactly — a
 * marker whose regex no longer matches it leaves a raw daemon path sitting in the
 * user's own message — and because every client that can attach a file needs
 * both. The Android-only half (HEIC decode, EXIF, downscale) stays in
 * `app/ui/Attachments.kt`, where it can see a Bitmap.
 */
object AttachmentText {

    /**
     * The line appended to the message so Claude knows there is something to look
     * at and how. Phrased as a bracketed system-ish note rather than prose in the
     * user's voice: the path is plumbing, not something the owner "said".
     */
    fun marker(path: String): String =
        "[Attached image at $path — view it with the Read tool.]"

    /** The same, for a non-image file; the name travels for context. */
    fun fileMarker(path: String, name: String?, readable: Boolean = true): String {
        val where = "$path${if (name.isNullOrBlank()) "" else " ($name)"}"
        // Telling Claude to Read a binary is exactly how the old upload refusal
        // justified itself: it comes back as mojibake and the answer is a shrug.
        // Naming the right tool instead is what makes accepting the file safe.
        return if (readable) "[Attached file at $where — view it with the Read tool.]"
        else "[Attached file at $where — a binary; inspect it with shell tools " +
            "(file, unzip, strings, sqlite3) rather than Read. Requires act mode.]"
    }

    // MARKER_RE must stay byte-identical to what marker() writes: the Electron
    // client (attachmentMarker.ts) and appd (humanizeUserText) carry copies of
    // this exact wording, and the marker is the ONLY link between a message and
    // its stored file. MARKER_PATH_RE is the same pattern with the path captured
    // (lazily — server-named files never contain " — ").
    private val MARKER_RE = Regex("""\[Attached image at [^\]]+ — view it with the Read tool\.\]""")
    private val MARKER_PATH_RE = Regex("""\[Attached image at ([^\]]+?) — view it with the Read tool\.\]""")
    private val FILE_RE = Regex("""\[Attached file at \S+( \(([^)]{1,80})\))? — [^\]]*\]""")

    /**
     * The same marker, made fit for human eyes. The bracketed path is plumbing
     * for Claude; a person reading their own message back should see that they
     * sent a photo, not where the daemon happened to store it.
     */
    fun displayText(text: String): String {
        if ('[' !in text) return text
        val cleaned = ScratchpadRules.collapse(text)
            .replace(MARKER_RE, "📷 Photo attached")
            .replace(FILE_RE) { m -> "📎 " + (m.groupValues[2].ifBlank { "File attached" }) }
            .trim()
        return cleaned.ifBlank { "📷 Photo attached" }
    }

    /** Every image path the message's markers name, in order. */
    fun imagePaths(text: String): List<String> {
        if ('[' !in text) return emptyList()
        return MARKER_PATH_RE.findAll(text).map { it.groupValues[1].trim() }.toList()
    }

    /**
     * The server-assigned basename an upload is fetched back by. The GET is by
     * NAME, not path, so a relocated data dir (HUGINN_APPD_DATA) does not orphan
     * old messages. Null for a path with no filename.
     */
    fun uploadName(path: String): String? =
        path.substringAfterLast('/').ifBlank { null }

    /**
     * The message text with its image markers removed — what renders BESIDE a
     * thumbnail (the thumbnail already says "photo"). File markers stay; they
     * have no thumbnail to speak for them.
     */
    fun stripImageMarkers(text: String): String =
        text.replace(MARKER_RE, "").trim()
}

/**
 * The outgoing message text: what was typed, then the attachment markers.
 *
 * ONE rule, in the lowest module that can hold it. It lived in the desktop module
 * and the phone hand-rolled `"\n\n"` at its two send sites, which is three copies
 * of a join whose exact shape the marker regex in [AttachmentText.displayText] has
 * to match — and the pane variant, where a stray newline is a submit, existed in
 * only one of the three. A blank draft sends the markers alone, which is the
 * "here, look at this" case and is deliberately allowed.
 *
 * MARKERS ARE SEPARATED BY [separator] FROM EACH OTHER TOO, not only from the
 * text. That is the load-bearing half for a pane: `AttachTest` asserts a session
 * line contains no `\n`, and joining N markers with a newline would slip one
 * through the gap between them while the text-to-marker join still looked right.
 *
 * @param separator a blank line for a chat, and [PANE_SEPARATOR] for a tmux pane —
 *   where the text is TYPED and a newline is the submit key, so a paragraph break
 *   would send half the message and leave the markers on the next prompt.
 */
fun composeMessage(text: String, markers: List<String>, separator: String = "\n\n"): String {
    val t = text.trim()
    val real = markers.filter { it.isNotBlank() }
    if (real.isEmpty()) return t
    val tail = real.joinToString(separator)
    return if (t.isEmpty()) tail else "$t$separator$tail"
}

/** The one-marker shape, kept so a single attachment reads as one at the call site. */
fun composeMessage(text: String, marker: String?, separator: String = "\n\n"): String =
    composeMessage(text, listOfNotNull(marker), separator)

/** See [composeMessage]: a pane cannot take a newline that is not a submit. */
const val PANE_SEPARATOR: String = " "

/**
 * What a composer got when it consumed its pending attachments.
 *
 * Both shells return this from their own `take`, because the rule the composer
 * then applies — send what landed, name what did not — must not be two rules.
 *
 * @param markers every READY item's marker, IN ATTACH ORDER. Order is preserved
 *   end to end: intake order → chip order → marker order in the message.
 * @param failed the labels of everything else.
 */
data class TakeResult(val markers: List<String>, val failed: List<String> = emptyList())

/**
 * The rules a batch of attachments obeys on both shells: how many, and what the
 * composer says when only some of them landed.
 *
 * Pure and shared for the same reason the marker is: a cap the phone enforces at
 * 10 and the desktop at "however many the OS handed over" is not a cap, and a
 * partial-failure line written twice is a line that gets fixed once.
 */
object AttachBatch {

    /**
     * How many things one message may carry.
     *
     * Ten because that is what the photo picker can be told to allow and what a
     * FlowRow of chips can show without becoming the composer. The number is not
     * a safety limit — the daemon has its own — it is a limit on how much one
     * message can plausibly be ABOUT.
     */
    const val MAX_ITEMS: Int = 10

    /** How many more this composer can still take. */
    fun room(pending: Int): Int = (MAX_ITEMS - pending).coerceAtLeast(0)

    /** The prefix of [incoming] that fits, in the order it arrived. */
    fun <T> accept(pending: Int, incoming: List<T>): List<T> = incoming.take(room(pending))

    /**
     * What to say when a drop or a pick was trimmed. Null when all of it fit —
     * silence is right there, and a note for every attach would be noise.
     */
    fun refusedNote(pending: Int, offered: Int): String? {
        val refused = offered - room(pending)
        if (refused <= 0) return null
        return "$MAX_ITEMS attachments at a time — $refused left off"
    }

    /**
     * The composer's line after a batch where some uploads failed.
     *
     * Load-bearing wording: it names WHICH ones, and says the rest went. The
     * alternative the old single-slot path took — refusing to send at all — costs
     * the typed message to save an attachment nobody can retry from.
     */
    fun failureLine(failed: List<String>, total: Int): String? {
        if (failed.isEmpty()) return null
        val them = if (failed.size == 1) "it" else "them"
        return "${failed.size} of $total attachments did not upload: " +
            failed.joinToString(", ") + " — sent without $them"
    }
}
