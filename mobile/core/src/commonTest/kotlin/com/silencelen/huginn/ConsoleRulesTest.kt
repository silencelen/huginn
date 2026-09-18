package com.silencelen.huginn

import com.silencelen.huginn.data.Console
import com.silencelen.huginn.data.ConsoleApproval
import com.silencelen.huginn.data.ConsoleApprovalStep
import com.silencelen.huginn.ui.ConsoleRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whether a console's page is answering, said in a way that cannot accuse a page
 * nobody has checked — and the sentence that keeps the approval card honest.
 *
 * ⚠ THE RULES ARE ASSERTED AS LITERALS, spelled out rather than built from
 * anything the implementation also uses (the [ScratchpadRulesTest] precedent):
 * the authority is `lib/consoles.js`, in another language, and a test that
 * round-tripped through a shared helper would pass happily while the two sides
 * had stopped agreeing about what an address may be.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class ConsoleRulesTest {

    private val nowSec = 1_789_460_000L
    private val nowMs = nowSec * 1000

    private fun console(
        up: Boolean? = true,
        probedAgo: Long? = 7_200,
        latency: Int? = null,
        status: Int? = null,
        url: String = "http://huginn:8088/",
        kind: String? = "dashboard",
    ) = Console(
        id = "armap",
        name = "Architecture map",
        url = url,
        kind = kind,
        notes = "Static armap dashboard.",
        addedAt = nowSec - 86_400,
        version = 3,
        up = up,
        lastProbeAt = probedAgo?.let { nowSec - it } ?: 0,
        latencyMs = latency,
        httpStatus = status,
        reachableFrom = "host",
    )

    // -------------------------------------------------------- the tri-state

    @Test
    fun `the third state is its own state`() {
        assertEquals(ConsoleRules.Reach.UP, ConsoleRules.reach(true))
        assertEquals(ConsoleRules.Reach.DOWN, ConsoleRules.reach(false))
        assertEquals(ConsoleRules.Reach.UNKNOWN, ConsoleRules.reach(null as Boolean?))
    }

    /**
     * ⚠⚠ THE ONE THING THIS OBJECT EXISTS TO GET RIGHT.
     *
     * `up = null` means no probe has produced a verdict — and the daemon keeps
     * probe state IN MEMORY, so after every restart that is EVERY row. Drawn as
     * "not answering" it puts a red mark on four healthy pages, and a mark that
     * is wrong that often is a mark nobody reads. Greps for the phrase rather
     * than comparing a whole string, so a reworded UNKNOWN branch still cannot
     * reach for it.
     */
    @Test
    fun `an unchecked console is never accused of not answering`() {
        assertEquals("not checked yet", ConsoleRules.reachabilityWords(null, 0, nowMs))
        val undecided = ConsoleRules.reachabilityWords(null, nowSec - 7_200, nowMs)
        assertFalse(
            undecided.contains("not answering"),
            "an unknown verdict must not accuse the page: $undecided",
        )
        assertEquals("no verdict yet · checked 2h ago", undecided)
    }

    @Test
    fun `an up row says where it was seen from, because that is the whole caveat`() {
        // A page bound to the host's own address is genuinely up and genuinely
        // unreachable from the phone in your hand.
        assertEquals(
            "up from the host · checked 2h ago",
            ConsoleRules.reachabilityWords(true, nowSec - 7_200, nowMs),
        )
        assertEquals(
            "not answering from the host · checked 2h ago",
            ConsoleRules.reachabilityWords(false, nowSec - 7_200, nowMs),
        )
    }

    /**
     * ⚠ AND IT STOPS SAYING IT ONCE THE REBIND IS IN. "from the host" is a
     * caveat, not a label: after the owner has applied the steps it is no longer
     * true, and a row that kept repeating it would be arguing with itself.
     */
    @Test
    fun `an applied approval drops the caveat`() {
        assertEquals(
            "up · checked 2h ago",
            ConsoleRules.reachabilityWords(true, nowSec - 7_200, nowMs, applied = true),
        )
        assertEquals(
            "not answering · checked 2h ago",
            ConsoleRules.reachabilityWords(false, nowSec - 7_200, nowMs, applied = true),
        )
        // The unknown branch never named a place to begin with.
        assertEquals("not checked yet", ConsoleRules.reachabilityWords(null, 0, nowMs, applied = true))
    }

    @Test
    fun `a never-stamped probe leaves no dangling separator`() {
        // An absent fact is absent, not an empty slot between two dots — and
        // lastProbeAt is 0 rather than null, which must render as nothing and
        // never as 1970.
        assertEquals("up from the host", ConsoleRules.reachabilityWords(true, 0, nowMs))
        assertEquals("not answering from the host", ConsoleRules.reachabilityWords(false, 0, nowMs))
    }

    @Test
    fun `what the probe measured is kept out of the verdict line`() {
        // A 403 page is UP: something answered. The status rides beside the
        // verdict rather than changing it.
        assertEquals("12 ms · HTTP 200", ConsoleRules.probeDetail(console(latency = 12, status = 200)))
        assertEquals("HTTP 403", ConsoleRules.probeDetail(console(status = 403)))
        assertEquals(ConsoleRules.Reach.UP, ConsoleRules.reach(console(up = true, status = 403)))
        assertNull(ConsoleRules.probeDetail(console()))
    }

    // -------------------------------------------------------------- the kind

    /**
     * ⚠ UNKNOWN ⇒ `other`, NOT NULL AND NOT ITSELF. A newer daemon inventing a
     * sixth kind must land in a bucket that already has a meaning rather than
     * draw a chip nobody has designed for.
     */
    @Test
    fun `an unknown kind becomes other`() {
        assertEquals(
            listOf("dashboard", "tool", "docs", "lab", "other"),
            ConsoleRules.KINDS,
            "the daemon's closed vocabulary, written out",
        )
        assertEquals("dashboard", ConsoleRules.kindWords("dashboard"))
        assertEquals("lab", ConsoleRules.kindWords(" LAB "))
        assertEquals("other", ConsoleRules.kindWords("hardware"))
        assertEquals("other", ConsoleRules.kindWords(null))
        assertEquals("other", ConsoleRules.kindWords("   "))
        assertEquals("dashboard · Static armap dashboard.", ConsoleRules.subtitle(console()))
        assertEquals("other · Static armap dashboard.", ConsoleRules.subtitle(console(kind = "hardware")))
    }

    @Test
    fun `a row with no name still has to be distinguishable`() {
        assertEquals("Architecture map", ConsoleRules.label(console()))
        assertEquals("armap", ConsoleRules.label(console().copy(name = "  ")))
        assertEquals("http://huginn:8088/", ConsoleRules.label(console().copy(name = "", id = "")))
    }

    // ----------------------------------------------------------- the address

    @Test
    fun `the scheme is required, not inferred`() {
        // ⚠ Unlike the client's convenience upgrade of a bare address:
        // `huginn:8088` reads as a scheme called "huginn", and a guard that
        // silently made it http would be judging something other than what was
        // typed.
        assertNull(ConsoleRules.urlProblem("http://huginn:8088/"))
        assertEquals("a console needs an address", ConsoleRules.urlProblem("  "))
        val scheme = "a console address must start with http:// or https://"
        assertEquals(scheme, ConsoleRules.urlProblem("huginn:8088"))
        assertEquals(scheme, ConsoleRules.urlProblem("file:///etc/passwd"))
        assertEquals(scheme, ConsoleRules.urlProblem("javascript:alert(1)"))
    }

    @Test
    fun `a single-label name is how this whole registry is written`() {
        assertNull(ConsoleRules.urlProblem("http://huginn:8088/"))
        assertNull(ConsoleRules.urlProblem("http://localhost:3000/"))
        assertNull(ConsoleRules.urlProblem("http://127.0.0.1:8092/board"))
        assertNull(ConsoleRules.urlProblem("http://192.168.7.54/status"))
        assertNull(ConsoleRules.urlProblem("http://100.97.198.90:8787/"))
        assertNull(ConsoleRules.urlProblem("https://huginn.ts.net/armap"))
    }

    /**
     * ⚠⚠ HTTPS IS NOT A FREE PASS HERE, and that is the deliberate divergence
     * from `RouteGuard.isAllowed`. A pinned route's guard may stop caring about
     * the host once TLS is present, because TLS is what makes it safe to send the
     * bearer. A console carries no bearer — it is a link handed to a browser — so
     * TLS buys nothing, and what matters is that the registry cannot become a
     * list of links to anywhere.
     */
    @Test
    fun `a public host is refused even over https`() {
        val off = "a console address must be on this host, the LAN, the tailnet or the mesh"
        assertEquals(off, ConsoleRules.urlProblem("https://example.com/dash"))
        assertEquals(off, ConsoleRules.urlProblem("http://example.com/dash"))
        assertEquals(off, ConsoleRules.urlProblem("https://8.8.8.8/"))
    }

    @Test
    fun `userinfo and dot-dot are refused rather than stripped`() {
        // A credential in a registry anybody who can open the app can read. And
        // quietly deleting half of somebody's URL is not a fix either.
        assertEquals(
            "a console address cannot carry a user name or password",
            ConsoleRules.urlProblem("http://user:pw@huginn:8088/"),
        )
        // A path a client resolves differently from the daemon is a link that
        // goes somewhere else.
        assertEquals(
            "a console address cannot contain ..",
            ConsoleRules.urlProblem("http://huginn:8088/board/../admin"),
        )
        assertEquals("a console address needs a host", ConsoleRules.urlProblem("http:///v1/status"))
        assertFalse(ConsoleRules.openable(console(url = "ftp://huginn/")))
        assertTrue(ConsoleRules.openable(console()))
    }

    // ---------------------------------------------------------- the approval

    private fun approval(applied: Boolean = false) = ConsoleApproval(
        applied = applied,
        runBy = "owner",
        markerPath = "/var/lib/huginn-appd/consoles-rebind.applied",
        title = "Reach these consoles from outside the host",
        why = "Every page is bound to this host's own address.",
        steps = listOf(
            ConsoleApprovalStep(
                id = "rebind", where = "huginn",
                summary = "Bind the four services to 0.0.0.0",
                commands = listOf("systemctl edit armap", "   ", "systemctl restart armap"),
            ),
            ConsoleApprovalStep(
                id = "firewall", where = "heimdall",
                summary = "Allow the four ports",
                file = "/etc/pve/firewall/117.fw",
                commands = listOf("IN ACCEPT -source 192.168.2.131 -p tcp -dport 8088 -log nolog"),
            ),
        ),
        note = "Run these in a netplan session.",
    )

    /**
     * ⚠⚠ THE SENTENCE THE WHOLE CARD EXISTS TO CARRY. No route applies these
     * steps, by design on both sides, so the card has no button — and a card with
     * no button and no sentence reads as one somebody forgot to wire up.
     */
    @Test
    fun `the card says, in words, that the app never runs these`() {
        assertTrue(
            ConsoleRules.APPROVAL_NEVER_RUN.contains("never runs these"),
            ConsoleRules.APPROVAL_NEVER_RUN,
        )
        assertTrue(
            ConsoleRules.APPROVAL_NEVER_RUN.contains("run them yourself"),
            "it must also say who does: ${ConsoleRules.APPROVAL_NEVER_RUN}",
        )
        assertEquals("owner", ConsoleRules.approvalRunBy(approval()))
        assertEquals("owner", ConsoleRules.approvalRunBy(null), "never anyone else by default")
    }

    @Test
    fun `every command in every step is listed, verbatim and in order`() {
        val a = approval()
        assertEquals(
            listOf(
                "systemctl edit armap",
                "systemctl restart armap",
                "IN ACCEPT -source 192.168.2.131 -p tcp -dport 8088 -log nolog",
            ),
            ConsoleRules.approvalCommands(a),
            "both steps, blanks dropped, everything else untouched",
        )
        assertEquals(2, ConsoleRules.approvalSteps(a).size)
        assertTrue(ConsoleRules.hasApproval(a))
        assertFalse(ConsoleRules.approvalApplied(a))
        assertEquals("Not applied — these consoles answer on the host only.", ConsoleRules.approvalWords(a))
        assertEquals(
            "Applied — these consoles answer from beyond the host.",
            ConsoleRules.approvalWords(approval(applied = true)),
        )
    }

    @Test
    fun `the copyable text carries the steps, their files and the note`() {
        // Copyable text rather than a button is the entire posture: the product's
        // part ends at showing what to run and who runs it.
        val t = ConsoleRules.approvalText(approval())
        assertTrue(t.startsWith("Reach these consoles from outside the host"), t.take(60))
        assertTrue(t.contains("# huginn — Bind the four services to 0.0.0.0"), t)
        assertTrue(t.contains("# heimdall — Allow the four ports"), t)
        assertTrue(t.contains("# file: /etc/pve/firewall/117.fw"), t)
        assertTrue(t.contains("systemctl restart armap"), t)
        assertTrue(t.trimEnd().endsWith("Run these in a netplan session."), t.takeLast(60))
        assertEquals("", ConsoleRules.approvalText(null))
    }

    @Test
    fun `no approval means no card`() {
        assertFalse(ConsoleRules.hasApproval(null))
        assertFalse(ConsoleRules.hasApproval(ConsoleApproval()))
        assertEquals(emptyList(), ConsoleRules.approvalCommands(null))
        assertNull(ConsoleRules.approvalNote(null))
    }
}
