package com.silencelen.huginn.ui

/**
 * Image file paths an answer MENTIONS, so the reader sees the picture instead of
 * a path they would have to go and open somewhere else. "I wrote the chart to
 * /tmp/claude-0/plot.png" is how Claude refers to a rendered image nearly every
 * time; nothing in the transcript drew it until now.
 *
 * ⚠ NOT [AttachmentText.imagePaths], and the two must not be merged. That one
 * reads attachment MARKERS out of a message the person sent — a fixed syntax,
 * written by this client, in a place it controls. This one reads assistant
 * PROSE, where a path is a word in a sentence surrounded by other people's
 * punctuation. Same word "path", different problems: one is parsing, this is
 * guessing, and guessing is why every rule below is narrow.
 *
 * Three rules carry the weight:
 *
 *  * **Anchored paths only.** `/…`, `~/…`, `./…`, `../…` and a Windows drive.
 *    A bare `shot.png` is unresolvable from a client that is not on that host
 *    and is far more likely to be a filename in prose than a picture.
 *  * **Nothing inside code.** Half an answer is commands, and commands name
 *    files constantly (`rm /tmp/old.png`, an `ls`, a diff). Drawing every one of
 *    them turns an answer into a contact sheet.
 *  * **Capped and deduped.** A directory listing is not a gallery.
 *
 * The client does NOT decide whether a path may be read — the daemon's
 * `GET /v1/files/image` owns containment and answers 403 for anything outside
 * its roots. A refused path lands in the loader's negative cache and draws a
 * placeholder. Do not grow a second copy of the allowlist here; two opinions
 * about what is readable is how one of them gets it wrong.
 */
object ImageMentions {

    /** Past this many, a message is listing files rather than showing one. */
    const val MAX_PER_MESSAGE = 4

    /**
     * Extensions a client can actually decode. `.svg` is deliberately absent: it
     * is an image to a browser and a script to a renderer we do not have.
     */
    private const val EXT = "(?:png|jpe?g|gif|webp|bmp)"

    /**
     * An anchor, a body, an image extension — with a boundary at each end.
     *
     * The LOOKBEHIND is what keeps `out/shot.png` from matching as `/shot.png`:
     * the `/` anchor may not be preceded by a word character. The trailing
     * lookahead is what keeps `/tmp/a.png.` from including the full stop while
     * still refusing `/tmp/a.pngx`.
     */
    private val PATH = Regex(
        "(?<![A-Za-z0-9_.~/\\\\-])" +
            "((?:[A-Za-z]:[\\\\/]|~/|\\.\\.?/|/)[^\\s\"'`<>|*?()\\[\\]{},;!]*\\.$EXT)" +
            "(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE,
    )

    /** A markdown link or image destination — already the renderer's job. */
    private val DESTINATION = Regex("\\]\\([^)]*\\)")

    /**
     * Paths [text] mentions, in the order written, deduped, at most [max].
     *
     * @param max hard cap; [MAX_PER_MESSAGE] by default.
     */
    fun paths(text: String, max: Int = MAX_PER_MESSAGE): List<String> {
        if (max <= 0 || text.isEmpty()) return emptyList()
        val scannable = blank(DESTINATION, maskCode(text))
        if (scannable.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        for (m in PATH.findAll(scannable)) {
            out.add(m.groupValues[1])
            if (out.size >= max) break
        }
        return out.toList()
    }

    /** Replaces every match with the same number of spaces, so offsets stay put. */
    private fun blank(re: Regex, text: String): String =
        re.replace(text) { " ".repeat(it.value.length) }

    /** Fenced blocks and inline code spans, blanked out. */
    private fun maskCode(text: String): String {
        val lines = text.replace("\r\n", "\n").split("\n")
        val sb = StringBuilder(text.length)
        var inFence = false
        for ((idx, line) in lines.withIndex()) {
            val t = line.trimStart()
            val fence = t.startsWith("```") || t.startsWith("~~~")
            when {
                // An UNCLOSED fence masks to the end of the message, deliberately:
                // the safe failure is a missing thumbnail, not a picture of
                // whatever a half-written command happened to name.
                fence -> { inFence = !inFence; sb.append(" ".repeat(line.length)) }
                inFence -> sb.append(" ".repeat(line.length))
                else -> sb.append(maskSpans(line))
            }
            if (idx < lines.size - 1) sb.append('\n')
        }
        return sb.toString()
    }

    private fun maskSpans(line: String): String {
        val chars = line.toCharArray()
        var i = 0
        while (i < chars.size) {
            if (chars[i] == '`') {
                var end = -1
                for (k in i + 1 until chars.size) if (chars[k] == '`') { end = k; break }
                if (end < 0) break          // a dangling tick opens nothing
                for (k in i..end) chars[k] = ' '
                i = end + 1
            } else i++
        }
        return chars.concatToString()
    }
}
