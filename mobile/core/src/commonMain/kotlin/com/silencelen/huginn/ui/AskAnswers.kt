package com.silencelen.huginn.ui

import com.silencelen.huginn.data.AskQuestion

/**
 * What came back from an AskUserQuestion, read from the ANSWERS rather than
 * from the sentence Claude Code writes for the model.
 *
 * ⚠⚠ THE CARD PRINTED THAT SENTENCE (2026-11-03). The tool result is prose
 * addressed to Claude:
 *
 *     The user answered: "Which size?"="extra wide", "Which shape?"="hexagon".
 *     Read the answers carefully — they may request clarification, changes, or
 *     that you not proceed — and follow what they actually say.
 *
 * and the answered card drew "Answered · " and its first 120 characters, cut
 * mid-word: the reader got the model's instructions and lost the second answer.
 * The card's own parse looked for `"answer":"…"` JSON that Claude Code has
 * never written.
 *
 * The shapes, from the 2.1.316 binary (the AskUserQuestion result builder) and
 * 141 results on one host, 2.1.258 – 2.1.316:
 *
 *  - each answered question is `"<question>"="<answer>"`, or
 *    `"<question>"=(no option selected)` when only notes came back, then an
 *    optional ` selected preview:\n<preview>` and ` notes: <notes>`; the items
 *    are joined with `, `, and a question nobody answered is left out;
 *  - the list follows one of [LEADS]: "Your questions have been answered: …. You
 *    can now continue with these answers in mind." when every answer was an
 *    offered option, "The user answered: …. Read the answers carefully …" when
 *    one was typed, and two mid-sentence forms for an idle timeout and a request
 *    for more questions;
 *  - "The user responded: <text>" is a free-text reply to the whole dialog;
 *  - a refusal (Esc, a timeout, a hook) is an error result (`ok == false`).
 *
 * Questions and answers can hold `"` and `, `, so the list is not split blindly:
 * the card's own questions say where each item starts. The daemon clips a
 * question to 240 characters and an ellipsis in the card's data, and the result
 * to 600, and both are allowed for.
 */
object AskAnswers {

    data class Answer(val question: String, val answer: String)

    /** The card's account of the result: a heading, then one line per answer. */
    data class Outcome(val answered: Boolean, val lines: List<String>) {
        val heading: String get() = if (answered) "Answered" else "Not answered"
    }

    /** The phrases Claude Code puts in front of the `"question"="answer"` list. */
    val LEADS: List<String> = listOf(
        "Your questions have been answered: ",
        "The user answered: ",
        "So far they answered: ",
        "Before going idle the user had selected: ",
    )

    private const val RESPONDED = "The user responded: "
    private const val NONE = "(no option selected)"
    private const val PREVIEW = " selected preview:"
    private const val NOTES = " notes: "

    /** Where the list ends: the sentence after it, in each of the shapes above. */
    private val ENDS = listOf(". Read the answers", ". You can now continue", ". Call ", ". They also wrote: ")

    /** A label longer than this is cut, so the answer it introduces stays on the line. */
    const val LABEL_MAX: Int = 32

    /**
     * Whether a tool result is Claude Code's answer to an AskUserQuestion.
     *
     * ANCHORED, not "contains": an orphan result is handed to an open question
     * on this alone, and a Read of a file that quotes these sentences — this
     * one, its tests — must stay that Read's output. The two mid-sentence forms
     * are matched with the words Claude Code puts around them.
     */
    fun isAnswer(result: String?): Boolean {
        val r = result?.trimStart() ?: return false
        return r.startsWith("Your questions have been answered: \"") || r.startsWith("The user answered: \"") ||
            r.startsWith(RESPONDED) || r.startsWith("The user did not answer the questions") ||
            "\n\nBefore going idle the user had selected: \"" in r ||
            (" before you proceed. So far they answered: \"" in r && " again with follow-up questions" in r)
    }

    /** The answers in [result], in the order given, or empty when it carries none. */
    fun parse(result: String?, questions: List<AskQuestion> = emptyList()): List<Answer> {
        val text = result ?: return emptyList()
        val lead = LEADS.mapNotNull { l -> text.indexOf(l).takeIf { it >= 0 }?.let { it + l.length } }.minOrNull()
            ?: return emptyList()
        val known = questions.map { it.question }.filter { it.isNotBlank() }
        val out = mutableListOf<Answer>()
        var at = lead
        while (at < text.length && text[at] == '"') {
            val qEnd = questionEnd(text, at + 1, known)
            if (qEnd < 0) break
            val question = text.substring(at + 1, qEnd)
            var p = qEnd + 2
            var answer: String
            when {
                text.startsWith(NONE, p) -> { answer = ""; p += NONE.length }
                p < text.length && text[p] == '"' -> {
                    val close = answerEnd(text, p + 1, known)
                    if (close < 0) {
                        // The daemon clipped the result inside this answer.
                        out += Answer(question, text.substring(p + 1).trimEnd().removeSuffix("…").trimEnd() + "…")
                        break
                    }
                    answer = text.substring(p + 1, close)
                    p = close + 1
                }
                else -> break
            }
            // The extras: a preview block and the person's notes, up to the next item.
            val next = itemBoundary(text, p, known)
            val extras = text.substring(p, if (next < 0) text.length else next)
            val notes = extras.indexOf(NOTES).takeIf { it >= 0 }?.let { extras.substring(it + NOTES.length).trim() }
            if (answer.isEmpty() && !notes.isNullOrEmpty()) answer = notes
            if (answer.isNotEmpty()) out += Answer(question, answer)
            if (next < 0 || !text.startsWith(", ", next)) break
            at = next + 2
        }
        return out
    }

    /**
     * The card's lines: `<header> → <answer>` for each answer, the question's
     * header when the card has one (Claude Code requires it), else the question,
     * cut at a word to [LABEL_MAX] so the answer is not pushed off the line.
     */
    fun outcome(result: String?, questions: List<AskQuestion>, ok: Boolean?): Outcome? {
        val r = result?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val answers = parse(r, questions)
        if (answers.isNotEmpty()) {
            return Outcome(true, answers.map { a -> "${label(a.question, questions)} → ${a.answer.trim()}" })
        }
        if (r.startsWith(RESPONDED)) return Outcome(true, listOf(flat(r.removePrefix(RESPONDED))))
        if (ok == false || r.startsWith("The user did not answer the questions")) {
            // A refusal's sentence is an instruction to the model ("STOP what you
            // are doing…"); the heading is the whole fact. Anything else that
            // failed — a timeout from a hook — says why, in its first sentence.
            val why = firstSentence(r)
            val toModel = why.startsWith("The user doesn't want to proceed") || why.startsWith("The user did not answer")
            return Outcome(false, if (toModel || why.isEmpty()) emptyList() else listOf(why))
        }
        return Outcome(true, listOf(firstSentence(r)))
    }

    /** The label for [question]: its header from the card, else the question, shortened at a word. */
    fun label(question: String, questions: List<AskQuestion>): String {
        val header = questions.firstOrNull { sameQuestion(it.question, question) }?.header?.trim()
        if (!header.isNullOrEmpty()) return header
        return WordFit.cut(flat(question), LABEL_MAX)
    }

    /** A question in the card's data (maybe clipped, with an ellipsis) against one in the result. */
    private fun sameQuestion(card: String, said: String): Boolean {
        if (card == said) return true
        val prefix = card.removeSuffix("…")
        return prefix.length < card.length && prefix.isNotEmpty() && said.startsWith(prefix)
    }

    private fun flat(s: String): String = s.replace(WHITESPACE, " ").trim()

    private fun firstSentence(s: String): String {
        val f = flat(s)
        val end = f.indexOf(". ")
        return if (end < 0) f else f.substring(0, end + 1)
    }

    private val WHITESPACE = Regex("\\s+")

    /**
     * The `"` that closes the question opened at [from]: right after one of the
     * card's own questions when one is there (clipped ones by their prefix),
     * else the first `"=` — a question with `"=` in it is the price of a card
     * whose question scrolled out of the window.
     */
    private fun questionEnd(text: String, from: Int, known: List<String>): Int {
        for (q in known) {
            if (text.startsWith(q, from) && text.startsWith("\"=", from + q.length)) return from + q.length
        }
        for (q in known) {
            val prefix = q.removeSuffix("…")
            if (prefix.length < q.length && prefix.isNotEmpty() && text.startsWith(prefix, from)) {
                val e = text.indexOf("\"=", from + prefix.length)
                if (e >= 0) return e
            }
        }
        return text.indexOf("\"=", from)
    }

    /** The `"` closing the answer that opened before [from], or -1 when the text ends first. */
    private fun answerEnd(text: String, from: Int, known: List<String>): Int {
        var j = text.indexOf('"', from)
        while (j >= 0) {
            val k = j + 1
            if (k == text.length || text.startsWith(PREVIEW, k) || text.startsWith(NOTES, k) ||
                endsListAt(text, k) || (text.startsWith(", ", k) && itemStartsAt(text, k + 2, known))
            ) return j
            j = text.indexOf('"', k)
        }
        return -1
    }

    /** Where the item that began before [from] ends: at `, "<next question>"=`, or the list's end; -1 if neither. */
    private fun itemBoundary(text: String, from: Int, known: List<String>): Int {
        var k = from
        while (k < text.length) {
            if (text.startsWith(", ", k) && itemStartsAt(text, k + 2, known)) return k
            if (endsListAt(text, k)) return k
            k++
        }
        return -1
    }

    private fun endsListAt(text: String, k: Int): Boolean =
        ENDS.any { text.startsWith(it, k) } || (k == text.length - 1 && text[k] == '.')

    private fun itemStartsAt(text: String, k: Int, known: List<String>): Boolean {
        if (k >= text.length || text[k] != '"') return false
        if (known.isNotEmpty()) {
            return known.any { q ->
                val prefix = q.removeSuffix("…")
                (text.startsWith(q, k + 1) && text.startsWith("\"=", k + 1 + q.length)) ||
                    (prefix.length < q.length && prefix.isNotEmpty() && text.startsWith(prefix, k + 1))
            }
        }
        val close = text.indexOf("\"=", k + 1)
        if (close < 0 || text.indexOf('"', k + 1) != close) return false
        return text.startsWith("\"=\"", close) || text.startsWith("\"=(", close)
    }
}

/**
 * Cutting a line at a WORD, never inside one.
 *
 * [fit] is the one rule — the longest run of whole words that fits, with an
 * ellipsis — and the caller says what "fits" means: a character budget here in
 * `:core`, a measured width in the row that draws it. Compose's own ellipsis
 * cuts at a character, which is how "they may request c" reached the screen.
 */
object WordFit {

    private const val ELLIPSIS = "…"

    /** [text] as it is when [fits] takes it whole; else the longest word prefix plus an ellipsis that it takes. */
    fun fit(text: String, fits: (String) -> Boolean): String {
        if (text.isEmpty() || fits(text)) return text
        val ends = wordEnds(text)
        // Longest first is the answer, so search: `fits` is monotone in length.
        var lo = 0
        var hi = ends.size - 1
        var best: String? = null
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val candidate = text.substring(0, ends[mid]).trimEnd().trimEnd(',', ';', ':', '.', '-', '→').trimEnd() + ELLIPSIS
            if (fits(candidate)) { best = candidate; lo = mid + 1 } else hi = mid - 1
        }
        // Not even the first word fits: the caller's own ellipsis is the honest answer.
        return best ?: text
    }

    /** [text] cut at a word to at most [max] characters, ellipsis included; mid-word only for one word longer than that. */
    fun cut(text: String, max: Int): String {
        val fitted = fit(text) { it.length <= max }
        return if (fitted.length <= max || max < 2) fitted else text.take(max - 1).trimEnd() + ELLIPSIS
    }

    /** Where each word ends, in order: the index of the whitespace after it. */
    private fun wordEnds(text: String): List<Int> {
        val out = mutableListOf<Int>()
        for (i in 1 until text.length) if (text[i].isWhitespace() && !text[i - 1].isWhitespace()) out += i
        return out
    }
}
