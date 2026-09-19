package com.silencelen.huginn.ui

import com.silencelen.huginn.data.App
import com.silencelen.huginn.data.AppAddress
import com.silencelen.huginn.data.AppList
import com.silencelen.huginn.data.RouteGuard
import com.silencelen.huginn.data.RouteKind

/**
 * Whether an app is answering, whether the device in your hand can reach it, and
 * the lines that would fix it when it cannot.
 *
 * ⚠⚠ TWO TRI-STATES, AND NEITHER THIRD IS A MISSING BOOLEAN. `up` is the HOST's
 * opinion of a port on the host; `reachable.ok` is whether the app answered on
 * the addresses CLIENTS ARRIVE ON. They are different questions with different
 * answers — a page bound to 127.0.0.1 is genuinely up and genuinely unreachable
 * from the phone reading this row — and the daemon keeps probe state IN MEMORY,
 * so after every restart both are null. Folding either null to `false` draws
 * four outages on a host where nothing is wrong, and a mark that is wrong that
 * often is a mark nobody reads.
 *
 * ⚠ THE DAEMON DECIDES, THIS EXPLAINS. Every rule here mirrors `lib/apps.js` and
 * is asserted as a LITERAL rather than derived from anything, the
 * [ScratchpadRules] precedent: the writer is in another language, so a shared
 * helper would let both sides drift together and stay green.
 */
object AppRules {

    /**
     * The daemon's closed vocabulary for what an app is.
     *
     * ⚠ AND AN UNKNOWN WORD BECOMES `other`, NOT NULL AND NOT ITSELF. A newer
     * daemon inventing a sixth kind must land in a bucket that already has a
     * meaning rather than draw a chip nobody has designed for — and `other` is a
     * real member of the list, so nothing has to special-case it.
     */
    val KINDS: List<String> = listOf("dashboard", "tool", "docs", "lab", "other")

    const val OTHER_KIND: String = "other"

    /** The most rows the registry holds. The daemon's cap, mirrored for the editor. */
    const val MAX_APPS: Int = 32

    /** Where an app's liveness was seen from. */
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

    fun reach(app: App): Reach = reach(app.up)

    /**
     * The reachability line under an app's name.
     *
     * ⚠ THE UNKNOWN BRANCH MUST NOT CONTAIN "not answering", and there is a test
     * that greps for exactly that. "not checked yet" is what a daemon that has
     * just started honestly knows; "no verdict yet" is what it knows after a
     * probe that neither answered nor failed. Both are quiet; neither accuses the
     * page.
     *
     * @param retrofitApplied whether the owner has run the retrofit. While it is
     *   false the line says **from the host**, because that is the only place the
     *   page can be reached from and the person reading this is usually somewhere
     *   else. Once it is true the caveat has stopped being true, and repeating it
     *   would be the row arguing with itself.
     */
    fun reachabilityWords(
        up: Boolean?,
        lastProbeAt: Long,
        nowMs: Long,
        retrofitApplied: Boolean = false,
    ): String {
        val where = if (retrofitApplied) "" else " from the host"
        val checked = agoWords(lastProbeAt.takeIf { it > 0 }, nowMs)
            .takeIf { it.isNotBlank() }?.let { "checked $it" }
        val head = when (reach(up)) {
            Reach.UP -> "up$where"
            Reach.DOWN -> "not answering$where"
            // ⚠ NOT A VERDICT. Do not make this branch accuse anything.
            Reach.UNKNOWN -> if (checked == null) DEVICE_UNCHECKED else "no verdict yet"
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
    fun probeDetail(app: App): String? {
        val bits = mutableListOf<String>()
        app.latencyMs?.takeIf { it >= 0 }?.let { bits += "$it ms" }
        app.httpStatus?.takeIf { it > 0 }?.let { bits += "HTTP $it" }
        return bits.joinToString(" · ").takeIf { it.isNotEmpty() }
    }

    // ----------------------------------------- reachable from your devices

    /** Whether the app answers where a phone or a laptop would ask for it. */
    enum class DeviceReach { REACHABLE, RETROFIT, UNCHECKED }

    const val DEVICE_REACHABLE: String = "reachable from your devices"
    const val DEVICE_RETROFIT: String = "needs retrofit"
    const val DEVICE_UNCHECKED: String = "not checked yet"

    /**
     * ⚠ `null` ⇒ [DeviceReach.UNCHECKED], never [DeviceReach.RETROFIT]. Same rule
     * as [reach] and for the same reason: "needs retrofit" about a row nobody has
     * checked is an accusation, and after a daemon restart that is every row.
     */
    fun deviceReach(ok: Boolean?): DeviceReach = when (ok) {
        true -> DeviceReach.REACHABLE
        false -> DeviceReach.RETROFIT
        null -> DeviceReach.UNCHECKED
    }

    fun deviceReach(app: App): DeviceReach = deviceReach(app.reachable.ok)

    fun deviceWords(ok: Boolean?): String = when (deviceReach(ok)) {
        DeviceReach.REACHABLE -> DEVICE_REACHABLE
        DeviceReach.RETROFIT -> DEVICE_RETROFIT
        DeviceReach.UNCHECKED -> DEVICE_UNCHECKED
    }

    /**
     * The row's whole status line: the verdict, what the probe measured, and the
     * device answer.
     *
     * ⚠ AND NEVER THE SAME WORDS TWICE. A daemon that has just started has no
     * verdict about either question, and both branches say "not checked yet" —
     * drawn together that reads like a stutter rather than like one fact.
     */
    fun rowWords(app: App, nowMs: Long, retrofitApplied: Boolean = false): String {
        val verdict = reachabilityWords(app.up, app.lastProbeAt, nowMs, retrofitApplied)
        val device = deviceWords(app.reachable.ok).takeIf { it !in verdict }
        return listOfNotNull(verdict, probeDetail(app), device).joinToString(" · ")
    }

    // ------------------------------------------------ the failing row's fix

    /**
     * Whether this row has something wrong worth disclosing.
     *
     * ⚠ ONLY A FAILING ROW OPENS (decision 55). The disclosure holds the
     * addresses that did not answer and the lines that would fix them; on a row
     * where everything answered it is an empty drawer with a chevron on it.
     */
    fun failing(app: App): Boolean =
        app.reachable.ok == false ||
            app.reachable.fix.any { it.isNotBlank() } ||
            app.reachable.addresses.any { !it.ok }

    /**
     * The daemon's own sentence about the check, or null.
     *
     * ⚠ IT IS WHY A VERDICT IS MISSING. `ok = null` on this daemon means the
     * PROBE SET WAS EMPTY — no usable bind address and no tailnet address to try
     * — and "not checked yet" alone leaves the reader waiting for a check that
     * is never going to run.
     */
    fun reachNote(app: App): String? = app.reachable.note.trim().takeIf { it.isNotEmpty() }

    /**
     * Whether the row has a disclosure worth opening at all.
     *
     * Wider than [failing] by exactly one case: a row that is not failing but
     * carries a [reachNote] still has something to say, and a note with nowhere
     * to be drawn is a note nobody reads. [failing] keeps its narrow meaning,
     * because that is what the dot and the counts are about.
     */
    fun expandable(app: App): Boolean = failing(app) || reachNote(app) != null

    /** What the disclosure's toggle says: an accusation, or an explanation. */
    fun expandVerb(app: App): String = if (failing(app)) "Why" else "Details"

    /** One probed address, said so a reader can tell which one and why. */
    fun addressWords(address: AppAddress): String {
        val why = if (address.ok) "answered"
        else address.error?.trim()?.takeIf { it.isNotEmpty() } ?: "no answer"
        return "${address.addr} — $why"
    }

    /**
     * Every fix line, VERBATIM and in order.
     *
     * Blank entries are dropped and nothing else is touched: no reformatting, no
     * shell quoting, no joining into a one-liner. A person is going to read these
     * and then run them, and every transformation between here and their terminal
     * is a chance to change what they run.
     */
    fun fixLines(app: App): List<String> = app.reachable.fix.filter { it.isNotBlank() }

    /**
     * ⚠⚠ THE SENTENCE THE DISCLOSURE EXISTS TO CARRY, and it is asserted as a
     * literal.
     *
     * The lines rebind a systemd unit on this host and sometimes add firewall
     * lines on a different machine entirely. No route applies them — by design,
     * on both sides — so the panel has one control, Copy, and without this
     * sentence a panel with one control reads as one somebody forgot to wire up.
     * Somebody would eventually wire it up.
     */
    const val FIX_NEVER_RUN: String =
        "huginn never runs these. Copy them into a session on the host and run them yourself."

    /**
     * The whole fix as one block of text, for the copy control.
     *
     * It leads with the app's own name because a clipboard full of `systemctl
     * edit` lines with no subject is four lines nobody can place — and the reader
     * has, by then, left the screen that said which row they came from.
     */
    fun fixText(app: App): String =
        fixTextOf(label(app), app.unit, app.reachable.addresses, fixLines(app))

    /**
     * The same payload, built from what the ADD FORM was handed by a 422.
     *
     * There is no [App] yet at that point — the add was refused, so nothing was
     * stored — and the person still has to run the same lines. One builder, so
     * the two paths cannot copy different text.
     */
    fun fixTextOf(
        title: String?,
        unit: String?,
        addresses: List<AppAddress>,
        fix: List<String>,
    ): String {
        val lines = fix.filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""
        val out = StringBuilder()
        title?.trim()?.takeIf { it.isNotEmpty() }?.let { out.append("# ").append(it) }
        unit?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (out.isNotEmpty()) out.append(" — ") else out.append("# ")
            out.append(it)
        }
        if (out.isNotEmpty()) out.append("\n")
        addresses.filterNot { it.ok }.forEach { out.append("# ").append(addressWords(it)).append("\n") }
        lines.forEach { out.append(it).append("\n") }
        return out.toString().trim()
    }

    // --------------------------------------------------------------- the icon

    /** Whether the daemon holds a favicon for this row at all. */
    fun hasIcon(app: App): Boolean = app.icon

    /**
     * The letter the tile draws when there is no icon.
     *
     * Taken from [label] rather than from `name`, so a row with no name shows the
     * first letter of whatever the row actually leads with. A label that starts
     * with something that is not a letter gets `#`, because a tile with a bracket
     * on it reads as a rendering bug.
     */
    fun iconInitial(app: App): String {
        val first = label(app).trimStart().firstOrNull() ?: return "#"
        return if (first.isLetter()) first.uppercaseChar().toString() else "#"
    }

    // ---------------------------------------------------------------- words

    /** The row's own sub-line: what kind of page it is, its unit, and the note. */
    fun subtitle(app: App): String {
        val bits = mutableListOf(kindWords(app.kind))
        app.unit?.trim()?.takeIf { it.isNotEmpty() }?.let { bits += it }
        app.notes?.trim()?.takeIf { it.isNotEmpty() }?.let { bits += it }
        return bits.joinToString(" · ")
    }

    /**
     * The app's kind, always one of [KINDS].
     *
     * ⚠ UNKNOWN ⇒ [OTHER_KIND]. Null, blank and a word from a newer daemon all
     * land in the same real bucket, so no caller has to hold a fourth case.
     */
    fun kindWords(raw: String?): String {
        val word = raw?.trim()?.lowercase().orEmpty()
        return if (word in KINDS) word else OTHER_KIND
    }

    /**
     * What the kind picker offers.
     *
     * ⚠ THE DAEMON'S LIST WINS. [KINDS] is the client's mirror for rendering a
     * row whose kind arrived from somewhere; the editor must offer what THIS
     * daemon will accept, which is `kinds` on the wire — otherwise a newer
     * daemon's sixth kind is one nobody can ever choose.
     */
    fun kindChoices(list: AppList): List<String> =
        list.kinds.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() } ?: KINDS

    /** What an app row leads with. */
    fun label(app: App): String =
        app.name.replace(WHITESPACE_RUN, " ").trim().takeIf { it.isNotEmpty() }
            ?: app.id.takeIf { it.isNotBlank() }
            ?: app.url

    private val WHITESPACE_RUN = Regex("""\s+""")

    // --------------------------------------------------------------- the url

    /**
     * Why this address cannot be used, or null. A mirror of the daemon's rules,
     * so a typo is answered in the field rather than by a round trip.
     *
     * ⚠⚠ HTTPS IS NOT A FREE PASS HERE, AND THAT IS THE ONE PLACE THIS RULE
     * DELIBERATELY DIVERGES FROM [RouteGuard.isAllowed]. A pinned route's guard
     * may stop caring about the host once TLS is present, because TLS is what
     * makes it safe to send the bearer. An app carries no bearer — it is a link
     * the shell hands to a browser — so TLS buys nothing, and what actually
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
        if (url.isEmpty()) return "an app needs an address"
        val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
        // ⚠ THE SCHEME IS REQUIRED, unlike the client's convenience upgrade of a
        // bare address: `huginn:8088` reads as a scheme called "huginn", and a
        // guard that silently made it http would be judging something other than
        // what was typed.
        if (scheme !in SCHEMES) return "an app address must start with http:// or https://"
        val rest = url.substringAfter("://")
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return "an app address needs a host"
        if ('@' in authority) return "an app address cannot carry a user name or password"
        // Refused outright rather than normalised away: a path a client resolves
        // differently from the daemon is a link that goes somewhere else.
        if (".." in url) return "an app address cannot contain .."
        val host = (if (authority.startsWith("[")) authority.substringBefore(']').drop(1)
        else authority.substringBeforeLast(':', missingDelimiterValue = authority)).lowercase()
        if (host.isEmpty()) return "an app address needs a host"
        if (host.length > HOST_MAX) return "an app address's host is too long"
        // Single-label names first: `huginn` cannot leave the local search domain
        // and is what the whole seeded registry is written with.
        if (singleLabel(host)) return null
        // ⚠ kindOf, NOT isAllowed — see the KDoc. Judged as http on purpose, so
        // https cannot buy a public host a pass it has not earned.
        if (RouteGuard.kindOf("http://$authority") != RouteKind.CUSTOM) return null
        return "an app address must be on this host, the LAN, the tailnet or the mesh"
    }

    /** A name with no dot and no colon: it cannot resolve past the search domain. */
    private fun singleLabel(host: String): Boolean =
        '.' !in host && ':' !in host && host.toIntOrNull() == null

    private val SCHEMES = setOf("http", "https")
    private const val HOST_MAX: Int = 253

    /**
     * Whether pressing this row may hand its address to a browser.
     *
     * ⚠⚠ THE ROW IS THE LINK NOW. The whole row is the control — there is no
     * separate Open verb to disable — so a row whose address the rules refuse
     * must not be pressable at all, rather than pressable and silently inert.
     */
    fun openable(app: App): Boolean = urlProblem(app.url) == null

    // ------------------------------------------------------ the transition

    /**
     * The one line at the bottom of the page while the retrofit is outstanding,
     * or null.
     *
     * It names the addresses the daemon probes from, because "needs retrofit"
     * with no addresses beside it is a verdict with no way to check it — and
     * those addresses are the whole content of the rule the owner set: if a
     * device can reach huginn to read this page, it must be able to reach the
     * app.
     */
    fun retrofitNote(list: AppList): String? {
        if (list.retrofitApplied) return null
        val needs = list.apps.count { deviceReach(it.reachable.ok) == DeviceReach.RETROFIT }
        if (needs == 0) return null
        val parts = mutableListOf("Retrofit not applied", "$needs of ${list.apps.size} needs it")
        list.clientAddresses.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
            ?.let { parts += "your devices arrive on ${it.joinToString(", ")}" }
        return parts.joinToString(" · ")
    }
}
