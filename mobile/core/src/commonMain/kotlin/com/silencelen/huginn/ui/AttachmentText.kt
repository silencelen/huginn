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
