package com.silencelen.huginn.ui

import com.silencelen.huginn.data.QuickActions

/**
 * What selected transcript text can be turned into, and the exact text each verb
 * stages in the composer.
 *
 * TWO OWNERS, deliberately split. The WORDING of Explain / Execute / Ask in a new
 * chat is the host's — `quick-actions.json`, edited from either client's Settings
 * and served on `/v1/status` — for the same reason the soft-end phrase is: a
 * phrase kept in two apps is a phrase that gets fixed in one. The FRAME is this
 * client's, because it is not a phrase at all: `> ` in front of every line is a
 * markdown fact, and the daemon has no opinion about it.
 *
 * That split is why a daemon with no templates still offers Quote and nothing
 * else. It is the one verb whose text this file writes.
 *
 * NOTHING HERE SENDS. Every entry point returns a string for the composer, and
 * the staging rule ([appendToDraft]) appends rather than replaces — a half-typed
 * message outranks anything arriving into it.
 */
enum class SelectionAction(val label: String) {
    EXPLAIN("Explain"),
    EXECUTE("Execute"),
    QUOTE("Quote"),
    ASK_IN_NEW_CHAT("Ask in new chat"),
}

object QuickActionRules {

    /**
     * How much selected text a quick action will carry.
     *
     * Not a display limit: a selection this size is already past what a person
     * meant to select (a drag that ran away down a long transcript), and the
     * composer it lands in is the one they then have to clear by hand.
     */
    const val SELECTION_MAX: Int = 20_000

    const val PLACEHOLDER: String = "{selection}"

    /**
     * What a cut selection ends with. Written as a literal here and asserted as a
     * literal in the suite: it is inside a quote block, so it has to carry the
     * marker itself or the block ends a line early.
     */
    const val TRUNCATED: String = "\n> …(truncated)"

    /** A blank line between what was already typed and what is being added. */
    private const val SEPARATOR: String = "\n\n"

    /**
     * The verbs this selection can be offered, in menu order.
     *
     * Empty for a blank selection (the toolkit's own Copy still shows, and an
     * empty list is what leaves it alone) and empty past [SELECTION_MAX]. When
     * [actions] is null the daemon predates 3.0.1 and owns no wording, so only
     * Quote — whose text this file writes — is offered.
     */
    fun offered(selection: String, actions: QuickActions?): List<SelectionAction> = when {
        selection.isBlank() -> emptyList()
        selection.length > SELECTION_MAX -> emptyList()
        actions == null -> listOf(SelectionAction.QUOTE)
        else -> SelectionAction.entries.toList()
    }

    /**
     * The quote frame. EXACTLY:
     *
     *  * every line prefixed `"> "`;
     *  * a blank source line becoming a bare `">"` — no trailing space, because
     *    `"> "` with nothing after it is invisible in a diff and is what makes a
     *    quote block noisy everywhere it is later pasted;
     *  * `\r\n` and `\r` normalised to `\n` first — a `\r` that survives into a
     *    session send corrupts the stored transcript while the pane still renders
     *    correctly, which is the paste-buffer trap seen from this end;
     *  * trailing blank lines dropped (they are an artefact of where the drag
     *    stopped; a LEADING blank line is inside what was selected and stays);
     *  * the whole cut to [SELECTION_MAX] with [TRUNCATED] appended when it was.
     *
     * ```
     * alpha                > alpha
     *                  →   >
     * beta                 > beta
     * ```
     *
     * Indentation inside a line is untouched: code is the likeliest thing to be
     * selected and trimming it would destroy the only part that matters.
     */
    fun quoteBlock(selection: String): String {
        val lines = normalise(selection).split("\n").dropLastWhile { it.isBlank() }
        val framed = lines.joinToString("\n") { if (it.isBlank()) ">" else "> $it" }
        return if (framed.length <= SELECTION_MAX) framed else framed.take(SELECTION_MAX) + TRUNCATED
    }

    /**
     * The Quote verb's whole output: the host's lead-in, if it wrote one, above
     * the block. `quote` is a lead-in rather than a template — the daemon refuses
     * a `{selection}` in it — and is empty by default, which is the bare block.
     */
    fun quote(leadIn: String, selection: String): String {
        val block = quoteBlock(selection)
        val lead = normalise(leadIn).trim()
        return if (lead.isEmpty() || block.isEmpty()) lead + block else lead + SEPARATOR + block
    }

    /**
     * A host template with the selection in it.
     *
     * ⚠ A LITERAL replace, never a regex one. `String.replace(String, String)`
     * treats both sides as literals; `Regex.replace` reads `$1` and `\1` in the
     * REPLACEMENT as group references and throws `No group 1` on the first sed
     * one-liner anybody selects. Selected text is arbitrary bytes.
     *
     * A template with no placeholder is a daemon that is already wrong (it 400s
     * those), and the answer is still never to drop the selection: it goes below
     * the template instead.
     */
    fun compose(template: String, selection: String): String {
        val text = normalise(selection)
        val t = normalise(template)
        return when {
            t.isBlank() -> text
            t.contains(PLACEHOLDER) -> t.replace(PLACEHOLDER, text)
            else -> t + SEPARATOR + text
        }
    }

    /** Which wording each verb takes, so both clients stage identical text. */
    fun textFor(action: SelectionAction, actions: QuickActions?, selection: String): String =
        when (action) {
            SelectionAction.QUOTE -> quote(actions?.quote.orEmpty(), selection)
            SelectionAction.EXPLAIN -> compose(actions?.explain.orEmpty(), selection)
            SelectionAction.EXECUTE -> compose(actions?.execute.orEmpty(), selection)
            SelectionAction.ASK_IN_NEW_CHAT -> compose(actions?.askInNewChat.orEmpty(), selection)
        }

    /**
     * The staging contract, lifted out of the desktop's page-into-composer path
     * so the phone cannot hold a second version of it: APPEND, never clobber,
     * never send, separated by a blank line when there is already something
     * there.
     */
    fun appendToDraft(current: String, addition: String): String = when {
        addition.isBlank() -> current
        current.isBlank() -> addition
        else -> current + SEPARATOR + addition
    }

    private fun normalise(s: String): String = s.replace("\r\n", "\n").replace("\r", "\n")
}

/**
 * The selecting state, hoisted.
 *
 * The phone enters this from a long-press and the desktop never needs it (its
 * right-click carries the selection with it), but the QUESTION both ask — is this
 * selection worth offering anything for — is the same one, and answering it
 * inside a gesture lambda is answering it where nothing can assert it.
 */
data class SelectionMode(val active: Boolean = false, val text: String = "") {

    fun actions(quickActions: QuickActions?): List<SelectionAction> =
        if (!active) emptyList() else QuickActionRules.offered(text, quickActions)

    /** The handles moved: same session, new text. */
    fun select(text: String): SelectionMode = SelectionMode(active = true, text = text)

    fun dismiss(): SelectionMode = NONE

    companion object {
        val NONE: SelectionMode = SelectionMode()

        fun begin(text: String): SelectionMode = SelectionMode(active = true, text = text)
    }
}
