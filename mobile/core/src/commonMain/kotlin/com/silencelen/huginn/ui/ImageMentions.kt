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
 *
 * ⚠⚠ SCANNED, NOT MATCHED WITH A REGEX, AND THE DIFFERENCE IS THE POINT. This
 * runs inside `remember(text)` on the UI thread, on every streaming delta and
 * every time a chat is opened, over text the model wrote. The path used to be
 * one pattern — an anchor, a greedy run of path characters, then backtracking
 * for the extension — so every `/` that could start a path scanned to the end
 * of its run and back. A run of `:/:/:/…` (each `/` a fresh start, none ending
 * in an image) cost time in the square of its length: 50,000 characters held
 * the JVM for four to six seconds, an ANR on the phone and a frozen window on
 * the desktop, again on every reopen. The link-destination pattern did the
 * same on a run of `](` with no `)`. Both are one pass now, over at most
 * [MAX_SCAN_CHARS]. What is found is what those patterns found
 * (ImageMentionsTest holds the scan to them case by case and on generated
 * messages); the one deliberate difference is that ANY whitespace ends a path,
 * the same on both platforms.
 */
object ImageMentions {

    /** Past this many, a message is listing files rather than showing one. */
    const val MAX_PER_MESSAGE = 4

    /**
     * How much of a message is read for mentions: about 25,000 tokens of answer,
     * which is longer than nearly any answer Claude writes. Past it, a message is
     * a dump rather than prose, and a path it names further on draws no
     * thumbnail. The scan is linear, so this is a ceiling on the cost of one
     * render, not the thing that makes it cheap.
     */
    const val MAX_SCAN_CHARS = 100_000

    /**
     * Extensions a client can actually decode. `.svg` is deliberately absent: it
     * is an image to a browser and a script to a renderer we do not have.
     */
    private val EXTENSIONS = listOf("png", "jpeg", "jpg", "gif", "webp", "bmp")

    /** What ends a path besides whitespace: the punctuation prose wraps one in. */
    private const val NOT_PATH = "\"'`<>|*?()[]{},;!"

    /**
     * Paths [text] mentions, in the order written, deduped, at most [max].
     *
     * A path is a word: a run of characters that are not whitespace and not
     * [NOT_PATH]. A run holds at most one mention, which the scan finds from
     * the two ends of the run — see [mention] — so every character is looked
     * at a fixed number of times however the run is built.
     *
     * @param max hard cap; [MAX_PER_MESSAGE] by default.
     */
    fun paths(text: String, max: Int = MAX_PER_MESSAGE): List<String> {
        if (max <= 0 || text.isEmpty()) return emptyList()
        val scannable = blankDestinations(maskCode(readable(text)))
        if (scannable.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        var i = 0
        while (i < scannable.length && out.size < max) {
            if (!isPathChar(scannable[i])) {
                i++
                continue
            }
            val run = i
            while (i < scannable.length && isPathChar(scannable[i])) i++
            mention(scannable, run, i)?.let { out.add(it) }
        }
        return out.toList()
    }

    /**
     * The first [MAX_SCAN_CHARS] of [text], cut back to the last line break
     * inside them. A code span never crosses a line and a fence is whole lines,
     * so cutting between lines leaves [maskCode] the same answer for every line
     * it reads: a span cut in half would leave a dangling tick, and a dangling
     * tick masks nothing.
     */
    private fun readable(text: String): String {
        if (text.length <= MAX_SCAN_CHARS) return text
        val cut = text.lastIndexOf('\n', MAX_SCAN_CHARS - 1)
        return text.substring(0, if (cut > 0) cut else MAX_SCAN_CHARS)
    }

    /**
     * The one mention the run `text[from, to)` holds, or null.
     *
     * An anchor, a body, an image extension — with a boundary at each end:
     *
     *  * The anchor is `/`, `~/`, `./`, `../` or a drive (`C:\`, `C:/`), and the
     *    character before it may not be a letter, a digit or one of `_.~/\-`.
     *    That boundary is what keeps `out/shot.png` from being `/shot.png`.
     *  * The extension is the LAST one in the run that no letter or digit
     *    follows, which keeps `/tmp/a.png.` from including the full stop while
     *    still refusing `/tmp/a.pngx`; the longest path is the one a greedy
     *    pattern took.
     *
     * ⚠ ONLY THE FIRST ANCHOR CAN START IT. A later anchor never ends sooner
     * than the first one does: one that starts right after an anchor has that
     * anchor's character before it, which is a boundary failure, and any later
     * one starts past the longest anchor's end. So when the first anchor's end
     * runs past the last extension, nothing in the run is a mention — which is
     * what makes this one pass instead of one pass per `/`.
     */
    private fun mention(text: String, from: Int, to: Int): String? {
        var dot = to - 1
        var end = -1
        while (dot >= from) {
            if (text[dot] == '.') {
                end = extensionEnd(text, dot + 1, to)
                if (end >= 0) break
            }
            dot--
        }
        if (end < 0) return null
        for (start in from until dot) {
            val body = anchorEnd(text, start, to)
            if (body < 0 || (start > 0 && isWordish(text[start - 1]))) continue
            return if (body <= dot) text.substring(start, end) else null
        }
        return null
    }

    /**
     * Where an image extension starting at [at] ends, or -1: one of [EXTENSIONS]
     * in any case, with no letter or digit after it.
     */
    private fun extensionEnd(text: String, at: Int, to: Int): Int {
        for (ext in EXTENSIONS) {
            val end = at + ext.length
            if (end > to) continue
            var same = true
            for (k in ext.indices) {
                if (asciiLower(text[at + k]) != ext[k]) {
                    same = false
                    break
                }
            }
            if (!same) continue
            return if (end < text.length && isAsciiAlnum(text[end])) -1 else end
        }
        return -1
    }

    /** Where the anchor starting at [at] ends, or -1 when none starts there. */
    private fun anchorEnd(text: String, at: Int, to: Int): Int {
        fun char(i: Int): Char = if (i < to) text[i] else ' '
        val c = text[at]
        return when {
            c == '/' -> at + 1
            c == '~' && char(at + 1) == '/' -> at + 2
            c == '.' && char(at + 1) == '/' -> at + 2
            c == '.' && char(at + 1) == '.' && char(at + 2) == '/' -> at + 3
            isAsciiLetter(c) && char(at + 1) == ':' && (char(at + 2) == '/' || char(at + 2) == '\\') -> at + 3
            else -> -1
        }
    }

    private fun isPathChar(c: Char): Boolean = !c.isWhitespace() && NOT_PATH.indexOf(c) < 0

    /** What may not stand right before an anchor. */
    private fun isWordish(c: Char): Boolean = isAsciiAlnum(c) || c == '_' || c == '.' || c == '~' || c == '/' ||
        c == '\\' || c == '-'

    private fun isAsciiLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    private fun isAsciiAlnum(c: Char): Boolean = isAsciiLetter(c) || c in '0'..'9'

    /** ASCII only, as the pattern's case folding was: no other alphabet's letter is a `p` or an `i`. */
    private fun asciiLower(c: Char): Char = if (c in 'A'..'Z') c + ('a' - 'A') else c

    /**
     * Every markdown link or image destination, `](…)`, blanked with spaces so
     * offsets stay put: drawing those is already the renderer's job.
     *
     * One pass. A `](` with no `)` after it means no later `](` has one either,
     * so the scan stops there instead of trying each of them to the end.
     */
    private fun blankDestinations(text: String): String {
        var open = text.indexOf("](")
        if (open < 0) return text
        val chars = text.toCharArray()
        while (open >= 0) {
            val close = text.indexOf(')', open + 2)
            if (close < 0) break
            chars.fill(' ', open, close + 1)
            open = text.indexOf("](", close + 1)
        }
        return chars.concatToString()
    }

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
