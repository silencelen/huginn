package com.silencelen.huginn.ui

import com.silencelen.huginn.data.Console
import com.silencelen.huginn.data.ConsoleApproval
import com.silencelen.huginn.data.ConsoleApprovalStep
import com.silencelen.huginn.data.RouteGuard
import com.silencelen.huginn.data.RouteKind

/**
 * Whether a console's page is answering, what its address may be, and the card
 * that lists the commands nothing in this product will ever run.
 *
 * ⚠⚠ THE WHOLE FEATURE TURNS ON ONE TRI-STATE. `up` is `true`, `false` or
 * `null`, and the third is not a missing boolean: the daemon keeps probe state
 * IN MEMORY ONLY, so every restart starts every row at null. Folding that to
 * `false` draws four outages on a host where nothing is wrong, and a mark that
 * is wrong that often is a mark nobody reads.
 *
 * And one clause qualifies all three: the probe runs ON THE HOST. A page bound
 * to the host's own address is genuinely up and genuinely unreachable from the
 * phone in your hand — until the owner has applied the rebind, which is what
 * [ConsoleApproval.applied] records and why it changes the words.
 *
 * ⚠ THE DAEMON DECIDES, THIS EXPLAINS. Every rule here mirrors `lib/consoles.js`
 * and is asserted as a LITERAL rather than derived from anything, the
 * [ScratchpadRules] precedent: the writer is in another language, so a shared
 * helper would let both sides drift together and stay green.
 */
object ConsoleRules {

    /**
     * The daemon's closed vocabulary for what a console is.
     *
     * ⚠ AND AN UNKNOWN WORD BECOMES `other`, NOT NULL AND NOT ITSELF. A newer
     * daemon inventing a sixth kind must land in a bucket that already has a
     * meaning rather than draw a chip nobody has designed for — and `other` is a
     * real member of the list, so nothing has to special-case it.
     */
    val KINDS: List<String> = listOf("dashboard", "tool", "docs", "lab", "other")

    const val OTHER_KIND: String = "other"

    /** The most rows the registry holds. The daemon's cap, mirrored for the editor. */
    const val MAX_CONSOLES: Int = 32

    /** Where a console's liveness was seen from. */
    enum class Reach { UP, DOWN, UNKNOWN }

    /**
     * The tri-state, resolved once so every caller reads it the same way.
     *
     * ⚠ `null` ⇒ [Reach.UNKNOWN], never [Reach.DOWN]. This is the branch the
     * whole object exists to get right.
     */
    fun reach(up: Boolean?): Reach = when (up) {
        true -> Reach.UP
        false -> Reach.DOWN
        null -> Reach.UNKNOWN
    }

    fun reach(console: Console): Reach = reach(console.up)

    /**
     * The reachability line under a console's name.
     *
     * ⚠ THE UNKNOWN BRANCH MUST NOT CONTAIN "not answering", and there is a test
     * that greps for exactly that. "not checked yet" is what a daemon that has
     * just started honestly knows; "no verdict yet" is what it knows after a
     * probe that neither answered nor failed. Both are quiet; neither accuses the
     * page.
     *
     * @param applied whether the owner has run the rebind. While it is false the
     *   line says **from the host**, because that is the only place the page can
     *   be reached from and the person reading this is usually somewhere else.
     *   Once it is true the caveat has stopped being true, and repeating it would
     *   be the row arguing with itself.
     */
    fun reachabilityWords(up: Boolean?, lastProbeAt: Long, nowMs: Long, applied: Boolean = false): String {
        val where = if (applied) "" else " from the host"
        val checked = agoWords(lastProbeAt.takeIf { it > 0 }, nowMs)
            .takeIf { it.isNotBlank() }?.let { "checked $it" }
        val head = when (reach(up)) {
            Reach.UP -> "up$where"
            Reach.DOWN -> "not answering$where"
            // ⚠ NOT A VERDICT. Do not make this branch accuse anything.
            Reach.UNKNOWN -> if (checked == null) "not checked yet" else "no verdict yet"
        }
        return listOfNotNull(head, checked).joinToString(" · ")
    }

    /**
     * What the probe MEASURED, as opposed to what it concluded — kept out of
     * [reachabilityWords] so the verdict line stays one short sentence.
     *
     * A 401 or a 403 is UP: something answered. That is the daemon's rule
     * (anything under 500 is up) and repeating it here would be a second
     * opinion; this only reports what was seen.
     */
    fun probeDetail(console: Console): String? {
        val bits = mutableListOf<String>()
        console.latencyMs?.takeIf { it >= 0 }?.let { bits += "$it ms" }
        console.httpStatus?.takeIf { it > 0 }?.let { bits += "HTTP $it" }
        return bits.joinToString(" · ").takeIf { it.isNotEmpty() }
    }

    /** The row's own sub-line: what kind of page it is, and the note under it. */
    fun subtitle(console: Console): String {
        val bits = mutableListOf(kindWords(console.kind))
        console.notes?.trim()?.takeIf { it.isNotEmpty() }?.let { bits += it }
        return bits.joinToString(" · ")
    }

    /**
     * The console's kind, always one of [KINDS].
     *
     * ⚠ UNKNOWN ⇒ [OTHER_KIND]. Null, blank and a word from a newer daemon all
     * land in the same real bucket, so no caller has to hold a fourth case.
     */
    fun kindWords(raw: String?): String {
        val word = raw?.trim()?.lowercase().orEmpty()
        return if (word in KINDS) word else OTHER_KIND
    }

    /** What a console row leads with. */
    fun label(console: Console): String =
        console.name.replace(WHITESPACE_RUN, " ").trim().takeIf { it.isNotEmpty() }
            ?: console.id.takeIf { it.isNotBlank() }
            ?: console.url

    private val WHITESPACE_RUN = Regex("""\s+""")

    // --------------------------------------------------------------- the url

    /**
     * Why this address cannot be used, or null. A mirror of the daemon's rules,
     * so a typo is answered in the field rather than by a round trip.
     *
     * ⚠⚠ HTTPS IS NOT A FREE PASS HERE, AND THAT IS THE ONE PLACE THIS RULE
     * DELIBERATELY DIVERGES FROM [RouteGuard.isAllowed]. A pinned route's guard
     * may stop caring about the host once TLS is present, because TLS is what
     * makes it safe to send the bearer. A console carries no bearer — it is a
     * link the shell hands to a browser — so TLS buys nothing, and what actually
     * matters is that the registry cannot become a list of links to anywhere.
     * So this reuses RouteGuard's HOST CLASSES (via [RouteGuard.kindOf], which
     * is `classify` with a public name) and never its scheme shortcut.
     *
     * One addition RouteGuard has no use for: a SINGLE-LABEL name is allowed.
     * `http://huginn:8088/` is how every row in this registry is written, and a
     * name with no dot in it cannot resolve past the local search domain.
     */
    fun urlProblem(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return "a console needs an address"
        val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
        // ⚠ THE SCHEME IS REQUIRED, unlike the client's convenience upgrade of a
        // bare address: `huginn:8088` reads as a scheme called "huginn", and a
        // guard that silently made it http would be judging something other than
        // what was typed.
        if (scheme !in SCHEMES) return "a console address must start with http:// or https://"
        val rest = url.substringAfter("://")
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return "a console address needs a host"
        if ('@' in authority) return "a console address cannot carry a user name or password"
        // Refused outright rather than normalised away: a path a client resolves
        // differently from the daemon is a link that goes somewhere else.
        if (".." in url) return "a console address cannot contain .."
        val host = (if (authority.startsWith("[")) authority.substringBefore(']').drop(1)
        else authority.substringBeforeLast(':', missingDelimiterValue = authority)).lowercase()
        if (host.isEmpty()) return "a console address needs a host"
        if (host.length > HOST_MAX) return "a console address's host is too long"
        // Single-label names first: `huginn` cannot leave the local search domain
        // and is what the whole seeded registry is written with.
        if (singleLabel(host)) return null
        // ⚠ kindOf, NOT isAllowed — see the KDoc. Judged as http on purpose, so
        // https cannot buy a public host a pass it has not earned.
        if (RouteGuard.kindOf("http://$authority") != RouteKind.CUSTOM) return null
        return "a console address must be on this host, the LAN, the tailnet or the mesh"
    }

    /** A name with no dot and no colon: it cannot resolve past the search domain. */
    private fun singleLabel(host: String): Boolean =
        '.' !in host && ':' !in host && host.toIntOrNull() == null

    private val SCHEMES = setOf("http", "https")
    private const val HOST_MAX: Int = 253

    /** Whether an Open control should be offered at all. */
    fun openable(console: Console): Boolean = urlProblem(console.url) == null

    // ---------------------------------------------------------- the approval

    /**
     * ⚠⚠ THE SENTENCE THE WHOLE APPROVAL CARD EXISTS TO CARRY, and it is
     * asserted as a literal.
     *
     * The steps rebind a systemd unit on this host and add firewall lines on a
     * different machine entirely. No route applies them — by design, on both
     * sides — so the card has no button, and without this sentence a card with
     * no button reads as one somebody forgot to wire up. Somebody would
     * eventually wire it up.
     */
    const val APPROVAL_NEVER_RUN: String =
        "huginn never runs these. Copy them into a session on the host and run them yourself."

    /** The card's own heading, said in terms of what is true right now. */
    fun approvalWords(approval: ConsoleApproval?): String =
        if (approvalApplied(approval)) {
            "Applied — these consoles answer from beyond the host."
        } else {
            "Not applied — these consoles answer on the host only."
        }

    fun approvalApplied(approval: ConsoleApproval?): Boolean = approval?.applied == true

    /** The steps, in the daemon's order, with the empty ones dropped. */
    fun approvalSteps(approval: ConsoleApproval?): List<ConsoleApprovalStep> =
        approval?.steps.orEmpty().filter {
            it.summary.isNotBlank() || it.commands.any { c -> c.isNotBlank() }
        }

    /**
     * Every command in every step, VERBATIM and in order.
     *
     * Blank entries are dropped and nothing else is touched: no reformatting, no
     * shell quoting, no joining into a one-liner. A person is going to read these
     * and then run them, and every transformation between here and their terminal
     * is a chance to change what they run. A card that showed three of four would
     * be consent to a job half done.
     */
    fun approvalCommands(approval: ConsoleApproval?): List<String> =
        approvalSteps(approval).flatMap { it.commands }.filter { it.isNotBlank() }

    /**
     * The whole approval as one block of text, for the copy control.
     *
     * Copyable text rather than a button is the entire posture: the product's
     * part ends at showing what to run and who runs it.
     */
    fun approvalText(approval: ConsoleApproval?): String {
        if (approval == null) return ""
        val out = StringBuilder()
        approval.title?.trim()?.takeIf { it.isNotEmpty() }?.let { out.append(it).append("\n\n") }
        approval.why?.trim()?.takeIf { it.isNotEmpty() }?.let { out.append(it).append("\n\n") }
        approvalSteps(approval).forEach { step ->
            val head = listOfNotNull(
                step.where.trim().takeIf { it.isNotEmpty() },
                step.summary.trim().takeIf { it.isNotEmpty() },
            ).joinToString(" — ")
            if (head.isNotEmpty()) out.append("# ").append(head).append("\n")
            step.file?.trim()?.takeIf { it.isNotEmpty() }?.let { out.append("# file: ").append(it).append("\n") }
            step.commands.filter { it.isNotBlank() }.forEach { out.append(it).append("\n") }
            out.append("\n")
        }
        approval.note?.trim()?.takeIf { it.isNotEmpty() }?.let { out.append(it).append("\n") }
        return out.toString().trim()
    }

    /** Who runs them. The daemon says `owner`; the card never implies anyone else. */
    fun approvalRunBy(approval: ConsoleApproval?): String =
        approval?.runBy?.trim()?.takeIf { it.isNotEmpty() } ?: "owner"

    /** The daemon's note about the approval, shown as written, or null. */
    fun approvalNote(approval: ConsoleApproval?): String? =
        approval?.note?.trim()?.takeIf { it.isNotEmpty() }

    /** True when there is an approval card worth drawing at all. */
    fun hasApproval(approval: ConsoleApproval?): Boolean =
        approval != null && (approvalSteps(approval).isNotEmpty() || approvalNote(approval) != null)
}
