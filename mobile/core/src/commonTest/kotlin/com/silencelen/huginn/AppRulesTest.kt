package com.silencelen.huginn

import com.silencelen.huginn.data.App
import com.silencelen.huginn.data.AppAddress
import com.silencelen.huginn.data.AppCreate
import com.silencelen.huginn.data.AppForm
import com.silencelen.huginn.data.AppList
import com.silencelen.huginn.data.AppReachability
import com.silencelen.huginn.ui.AppRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whether an app is answering, whether the DEVICE IN YOUR HAND can reach it, and
 * the fix lines that ride on the row when it cannot.
 *
 * ⚠ THE RULES ARE ASSERTED AS LITERALS, spelled out rather than built from
 * anything the implementation also uses (the [ScratchpadRulesTest] precedent):
 * the authority is `lib/apps.js`, in another language, and a test that
 * round-tripped through a shared helper would pass happily while the two sides
 * had stopped agreeing about what an address may be.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class AppRulesTest {

    private val nowSec = 1_789_460_000L
    private val nowMs = nowSec * 1000

    private fun app(
        up: Boolean? = true,
        probedAgo: Long? = 7_200,
        latency: Int? = null,
        status: Int? = null,
        url: String = "http://huginn:8088/",
        kind: String? = "dashboard",
        icon: Boolean = true,
        reachable: AppReachability = AppReachability(ok = true, checkedAt = nowSec - 7_200),
    ) = App(
        id = "armap",
        name = "Architecture map",
        url = url,
        kind = kind,
        notes = "Static armap dashboard.",
        unit = "armap.service",
        addedAt = nowSec - 86_400,
        version = 3,
        up = up,
        lastProbeAt = probedAgo?.let { nowSec - it } ?: 0,
        latencyMs = latency,
        httpStatus = status,
        icon = icon,
        reachable = reachable,
    )

    // -------------------------------------------------------- the tri-state

    @Test
    fun `the third state is its own state`() {
        assertEquals(AppRules.Reach.UP, AppRules.reach(true))
        assertEquals(AppRules.Reach.DOWN, AppRules.reach(false))
        assertEquals(AppRules.Reach.UNKNOWN, AppRules.reach(null as Boolean?))
    }

    /**
     * ⚠⚠ THE ONE THING THIS OBJECT EXISTS TO GET RIGHT.
     *
     * `up = null` means no probe has produced a verdict — and the daemon keeps
     * probe state IN MEMORY, so after every restart that is EVERY row. Drawn as
     * "not answering" it puts a red mark on four healthy pages, and a mark that
     * is wrong that often is a mark nobody reads.
     */
    @Test
    fun `an unchecked app is never accused of not answering`() {
        assertEquals("not checked yet", AppRules.reachabilityWords(null, 0, nowMs))
        val undecided = AppRules.reachabilityWords(null, nowSec - 7_200, nowMs)
        assertFalse(
            undecided.contains("not answering"),
            "an unknown verdict must not accuse the page: $undecided",
        )
        assertEquals("no verdict yet · checked 2h ago", undecided)
    }

    @Test
    fun `an up row says where it was seen from, because that is the whole caveat`() {
        assertEquals(
            "up from the host · checked 2h ago",
            AppRules.reachabilityWords(true, nowSec - 7_200, nowMs),
        )
        assertEquals(
            "not answering from the host · checked 2h ago",
            AppRules.reachabilityWords(false, nowSec - 7_200, nowMs),
        )
    }

    @Test
    fun `an applied retrofit drops the caveat`() {
        assertEquals(
            "up · checked 2h ago",
            AppRules.reachabilityWords(true, nowSec - 7_200, nowMs, retrofitApplied = true),
        )
        assertEquals("not checked yet", AppRules.reachabilityWords(null, 0, nowMs, retrofitApplied = true))
    }

    @Test
    fun `a never-stamped probe leaves no dangling separator`() {
        assertEquals("up from the host", AppRules.reachabilityWords(true, 0, nowMs))
        assertEquals("not answering from the host", AppRules.reachabilityWords(false, 0, nowMs))
    }

    @Test
    fun `what the probe measured is kept out of the verdict line`() {
        // A 403 page is UP: something answered.
        assertEquals("12 ms · HTTP 200", AppRules.probeDetail(app(latency = 12, status = 200)))
        assertEquals("HTTP 403", AppRules.probeDetail(app(status = 403)))
        assertEquals(AppRules.Reach.UP, AppRules.reach(app(up = true, status = 403)))
        assertNull(AppRules.probeDetail(app()))
    }

    // ------------------------------------------- reachable from your devices

    /**
     * ⚠⚠ THE SECOND AXIS, AND THE ONE THE OWNER ASKED FOR (decision 54). "up" is
     * the host's own opinion of a port on the host. Whether the phone reading
     * this row can open the link is a DIFFERENT question, answered by probing the
     * addresses clients actually arrive on — and it is the question somebody has
     * when they tap.
     *
     * Three states again, and again the third is not a missing boolean: a daemon
     * that has not run the retrofit check yet knows nothing, and "needs retrofit"
     * about a row nobody has checked is an accusation.
     */
    @Test
    fun `the device verdict is its own tri-state with its own words`() {
        assertEquals("reachable from your devices", AppRules.deviceWords(true))
        assertEquals("needs retrofit", AppRules.deviceWords(false))
        assertEquals("not checked yet", AppRules.deviceWords(null))
        assertEquals(AppRules.DeviceReach.REACHABLE, AppRules.deviceReach(true))
        assertEquals(AppRules.DeviceReach.RETROFIT, AppRules.deviceReach(false))
        assertEquals(AppRules.DeviceReach.UNCHECKED, AppRules.deviceReach(null))
    }

    /**
     * The row's one status line: the verdict, what the probe measured, and the
     * device answer — with the device answer dropped when the verdict already
     * said the same words, so a fresh daemon does not draw "not checked yet ·
     * not checked yet".
     */
    @Test
    fun `the row line joins both verdicts and never says one of them twice`() {
        assertEquals(
            "up from the host · checked 2h ago · 12 ms · HTTP 200 · reachable from your devices",
            AppRules.rowWords(app(latency = 12, status = 200), nowMs),
        )
        assertEquals(
            "not answering from the host · checked 2h ago · needs retrofit",
            AppRules.rowWords(
                app(up = false, reachable = AppReachability(ok = false, checkedAt = nowSec)),
                nowMs,
            ),
        )
        // Nothing checked at all: said once.
        assertEquals(
            "not checked yet",
            AppRules.rowWords(app(up = null, probedAgo = null, reachable = AppReachability()), nowMs),
        )
    }

    /**
     * ⚠⚠ A JUST-ADDED APP READ "not checked yet · reachable from your devices"
     * (P-32/D-32) — two opposite tenses about two different questions, in one
     * line, on the row a person is looking at the second they add an app.
     *
     * Both clauses were true. The one a reader believes is the first, so the
     * PROVEN half leads and the pending half says what is pending.
     */
    @Test
    fun `a freshly added app leads with what was proven, not with what has not run`() {
        val added = app(up = null, probedAgo = null, reachable = AppReachability(ok = true, checkedAt = nowSec))
        val line = AppRules.rowWords(added, nowMs)
        assertEquals("reachable from your devices · waiting for the first check", line)
        assertTrue(
            line.indexOf(AppRules.DEVICE_REACHABLE) < line.indexOf(AppRules.FIRST_CHECK_PENDING),
            "the fact that was established leads: $line",
        )
        assertFalse(line.contains(AppRules.DEVICE_UNCHECKED), "nothing was checked is no longer true: $line")

        // The retrofit half of the same state: still one tense, still leading
        // with the answer somebody can act on.
        assertEquals(
            "needs retrofit · waiting for the first check",
            AppRules.rowWords(
                app(up = null, probedAgo = null, reachable = AppReachability(ok = false, checkedAt = nowSec)),
                nowMs,
            ),
        )
    }

    @Test
    fun `an unknown verdict that HAS been probed keeps the ordinary line`() {
        // "no verdict yet" is a different fact from "has not run yet" — the probe
        // neither answered nor failed — and it is not what this narrowing is for.
        assertEquals(
            "no verdict yet · checked 2h ago · reachable from your devices",
            AppRules.rowWords(app(up = null), nowMs),
        )
    }

    // ------------------------------------------------- the failing row's fix

    private val failing = app(
        up = true,
        status = 403,
    ).copy(
        id = "jtyper",
        name = "jtyper trainer",
        unit = "jtyper-trainer.service",
        reachable = AppReachability(
            ok = false,
            checkedAt = nowSec,
            addresses = listOf(
                AppAddress("192.168.7.31:8091", ok = false, error = "connection refused"),
                AppAddress("100.97.198.90:8091", ok = true),
            ),
            fix = listOf(
                "systemctl edit jtyper-trainer.service   # ExecStart: bind 0.0.0.0",
                "systemctl restart jtyper-trainer",
                "ss -ltnp | grep 8091",
            ),
        ),
    )

    /**
     * ⚠ ONLY A FAILING ROW OPENS. The disclosure holds the addresses that did not
     * answer and the lines that would fix them; on a row where everything
     * answered it would be an empty drawer with a chevron on it.
     */
    @Test
    fun `a row expands only when there is something wrong to show`() {
        assertTrue(AppRules.failing(failing))
        assertFalse(AppRules.failing(app()), "everything answered — nothing to disclose")
        assertFalse(
            AppRules.failing(app(reachable = AppReachability())),
            "no verdict is not a failure",
        )
        // A row with fix lines and no verdict yet still has something to show.
        assertTrue(
            AppRules.failing(app(reachable = AppReachability(fix = listOf("systemctl restart armap")))),
        )
    }

    /**
     * ⚠ A ROW CAN HAVE NOTHING WRONG AND STILL HAVE SOMETHING TO SAY. `ok = null`
     * on this daemon means the probe set was EMPTY — no usable bind address and
     * no tailnet address to try — and the note is the only thing that says so.
     * That row is not failing and must still open, under a verb that is not an
     * accusation.
     */
    @Test
    fun `a note opens the row too, under a different word`() {
        val quiet = app(
            up = null,
            probedAgo = null,
            reachable = AppReachability(ok = null, note = "no address to probe yet"),
        )
        assertFalse(AppRules.failing(quiet), "nothing did anything wrong")
        assertTrue(AppRules.expandable(quiet), "and there is still an answer to read")
        assertEquals("Details", AppRules.expandVerb(quiet))
        assertEquals("Why", AppRules.expandVerb(failing), "a failure is asked about, not explained")
        assertEquals("no address to probe yet", AppRules.reachNote(quiet))
        assertNull(AppRules.reachNote(app()), "a blank note is no note")
        assertNull(AppRules.reachNote(app(reachable = AppReachability(ok = true, note = "   "))))
        assertFalse(AppRules.expandable(app()), "nothing wrong and nothing to say")
    }

    @Test
    fun `each address says which one it was and why it did not answer`() {
        assertEquals(
            "192.168.7.31:8091 — connection refused",
            AppRules.addressWords(AppAddress("192.168.7.31:8091", ok = false, error = "connection refused")),
        )
        assertEquals(
            "192.168.7.31:8091 — no answer",
            AppRules.addressWords(AppAddress("192.168.7.31:8091", ok = false)),
            "a failure with no reason still has to read as a failure",
        )
        assertEquals(
            "100.97.198.90:8091 — answered",
            AppRules.addressWords(AppAddress("100.97.198.90:8091", ok = true)),
        )
    }

    /**
     * ⚠⚠ THE SENTENCE THE DISCLOSURE EXISTS TO CARRY, and it is asserted as a
     * literal. The lines rebind a systemd unit on this host and sometimes add
     * firewall lines on a different machine. No route applies them — by design,
     * on both sides — so the disclosure has one control, Copy, and without this
     * sentence a panel with one control reads as one somebody forgot to wire up.
     */
    @Test
    fun `nothing in this app runs the fix, and the row says so`() {
        assertTrue(AppRules.FIX_NEVER_RUN.contains("never runs these"), AppRules.FIX_NEVER_RUN)
        assertTrue(
            AppRules.FIX_NEVER_RUN.contains("run them yourself"),
            "it must also say who does: ${AppRules.FIX_NEVER_RUN}",
        )
    }

    /**
     * ⚠⚠ ONE COMMAND, ONE WHOLE LINE, VERBATIM AND IN ORDER. The copy payload is
     * not a preview — it is the thing a person pastes into a root shell. A
     * reformatted `grep 8091` is a check of the wrong port and a re-ordered pair
     * restarts a unit before it has been edited.
     */
    @Test
    fun `the copy payload is the fix lines, verbatim and in order`() {
        val text = AppRules.fixText(failing)
        val lines = text.lines()
        AppRules.fixLines(failing).forEach {
            assertTrue(lines.contains(it), "not on a line of its own: $it\n---\n$text")
        }
        val positions = AppRules.fixLines(failing).map { lines.indexOf(it) }
        assertEquals(positions.sorted(), positions, "the lines arrived out of order: $positions")
        assertEquals(
            listOf(
                "systemctl edit jtyper-trainer.service   # ExecStart: bind 0.0.0.0",
                "systemctl restart jtyper-trainer",
                "ss -ltnp | grep 8091",
            ),
            AppRules.fixLines(failing),
            "blanks dropped, everything else untouched",
        )
        assertTrue(text.contains("jtyper trainer"), "the payload names the app it belongs to: $text")
        assertEquals("", AppRules.fixText(app()), "nothing wrong, nothing to copy")
    }

    // --------------------------------------------------------------- the icon

    /**
     * ⚠ THE TILE IS THE FALLBACK, NOT THE DEFAULT. `icon:false` means the daemon
     * has no favicon for this row and the route would 404; drawing the letter
     * saves a request that can only fail. A row that CLAIMS an icon still falls
     * back to the tile when the bytes do not decode — that branch lives in the
     * loader and resolves to null, which is the same as not having one.
     */
    @Test
    fun `a row with no icon gets a letter, taken from what the row actually says`() {
        assertFalse(AppRules.hasIcon(app(icon = false)))
        assertTrue(AppRules.hasIcon(app(icon = true)))
        assertEquals("A", AppRules.iconInitial(app()))
        assertEquals("J", AppRules.iconInitial(app().copy(name = "jtyper trainer")))
        // The label's rule, not the name's: a row with no name leads with its id.
        assertEquals("B", AppRules.iconInitial(app().copy(name = "  ", id = "btc15m")))
        assertEquals("#", AppRules.iconInitial(app().copy(name = "", id = "", url = "9-lab")))
    }

    // -------------------------------------------------------------- the kind

    @Test
    fun `an unknown kind becomes other`() {
        assertEquals(
            listOf("dashboard", "tool", "docs", "lab", "other"),
            AppRules.KINDS,
            "the daemon's closed vocabulary, written out",
        )
        assertEquals("dashboard", AppRules.kindWords("dashboard"))
        assertEquals("lab", AppRules.kindWords(" LAB "))
        assertEquals("other", AppRules.kindWords("hardware"))
        assertEquals("other", AppRules.kindWords(null))
        assertEquals("other", AppRules.kindWords("   "))
    }

    /** The kind picker offers what the DAEMON said, and the five above when it said nothing. */
    @Test
    fun `the kind picker comes from the list, not from a second copy of the vocabulary`() {
        assertEquals(
            listOf("dashboard", "tool", "docs", "lab", "other"),
            AppRules.kindChoices(AppList()),
            "an empty list still has to offer something to pick",
        )
        assertEquals(
            listOf("dashboard", "tool", "docs", "lab", "other", "hardware"),
            AppRules.kindChoices(AppList(kinds = listOf("dashboard", "tool", "docs", "lab", "other", "hardware"))),
            "a newer daemon's sixth kind is offered as the daemon wrote it",
        )
    }

    @Test
    fun `the subtitle carries the kind, the unit and the note`() {
        assertEquals("dashboard · armap.service · Static armap dashboard.", AppRules.subtitle(app()))
        assertEquals("other · Static armap dashboard.", AppRules.subtitle(app(kind = "hardware").copy(unit = null)))
    }

    /**
     * ⚠ THE DAEMON SENDS `""` FOR "NO UNIT", NEVER NULL — so blank has to read as
     * absent everywhere, or every unit-less row grows a dangling separator.
     */
    @Test
    fun `an empty unit reads as no unit, not as an empty one`() {
        assertEquals(
            "dashboard · Static armap dashboard.",
            AppRules.subtitle(app().copy(unit = "")),
        )
        assertEquals(
            "dashboard · Static armap dashboard.",
            AppRules.subtitle(app().copy(unit = null)),
        )
        assertEquals(
            "# jtyper trainer\nsystemctl restart x",
            AppRules.fixTextOf("jtyper trainer", "", emptyList(), listOf("systemctl restart x")),
        )
    }

    @Test
    fun `a row with no name still has to be distinguishable`() {
        assertEquals("Architecture map", AppRules.label(app()))
        assertEquals("armap", AppRules.label(app().copy(name = "  ")))
        assertEquals("http://huginn:8088/", AppRules.label(app().copy(name = "", id = "")))
    }

    // ----------------------------------------------------------- the address

    @Test
    fun `the scheme is required, not inferred`() {
        assertNull(AppRules.urlProblem("http://huginn:8088/"))
        assertEquals("an app needs an address", AppRules.urlProblem("  "))
        val scheme = "an app address must start with http:// or https://"
        assertEquals(scheme, AppRules.urlProblem("huginn:8088"))
        assertEquals(scheme, AppRules.urlProblem("file:///etc/passwd"))
        assertEquals(scheme, AppRules.urlProblem("javascript:alert(1)"))
    }

    @Test
    fun `a single-label name is how this whole registry is written`() {
        assertNull(AppRules.urlProblem("http://huginn:8088/"))
        assertNull(AppRules.urlProblem("http://localhost:3000/"))
        assertNull(AppRules.urlProblem("http://127.0.0.1:8092/board"))
        assertNull(AppRules.urlProblem("http://192.168.7.54/status"))
        assertNull(AppRules.urlProblem("http://100.97.198.90:8787/"))
        assertNull(AppRules.urlProblem("https://huginn.ts.net/armap"))
    }

    /**
     * ⚠⚠ HTTPS IS NOT A FREE PASS HERE, and that is the deliberate divergence
     * from `RouteGuard.isAllowed`. An app carries no bearer — it is a link handed
     * to a browser — so TLS buys nothing, and what matters is that the registry
     * cannot become a list of links to anywhere.
     */
    @Test
    fun `a public host is refused even over https`() {
        val off = "an app address must be on this host, the LAN, the tailnet or the mesh"
        assertEquals(off, AppRules.urlProblem("https://example.com/dash"))
        assertEquals(off, AppRules.urlProblem("http://example.com/dash"))
        assertEquals(off, AppRules.urlProblem("https://8.8.8.8/"))
    }

    @Test
    fun `userinfo and dot-dot are refused rather than stripped`() {
        assertEquals(
            "an app address cannot carry a user name or password",
            AppRules.urlProblem("http://user:pw@huginn:8088/"),
        )
        assertEquals(
            "an app address cannot contain ..",
            AppRules.urlProblem("http://huginn:8088/board/../admin"),
        )
        assertEquals("an app address needs a host", AppRules.urlProblem("http:///v1/status"))
    }

    /**
     * ⚠⚠ THE ROW IS THE LINK NOW, so the http(s) gate moved onto the row itself.
     * The whole row is the control — a press anywhere on it hands the address to
     * a browser — and a row whose address the rules refuse must not be pressable
     * at all, rather than pressable and silently inert.
     */
    @Test
    fun `only an http or https row opens`() {
        assertTrue(AppRules.openable(app()))
        assertTrue(AppRules.openable(app(url = "https://huginn.ts.net/armap")))
        assertFalse(AppRules.openable(app(url = "ftp://huginn/")))
        assertFalse(AppRules.openable(app(url = "file:///etc/passwd")))
        assertFalse(AppRules.openable(app(url = "javascript:alert(1)")))
        assertFalse(AppRules.openable(app(url = "https://example.com/")))
        assertFalse(AppRules.openable(app(url = "  ")))
    }

    // ------------------------------------------------------- the retrofit note

    /**
     * The one-line note at the bottom of the page during the transition. It names
     * the addresses the daemon probes from, because "needs retrofit" without them
     * is a verdict with no way to check it.
     */
    @Test
    fun `the transition note says what has not been done and where from`() {
        val list = AppList(
            apps = listOf(failing, app()),
            retrofitApplied = false,
            clientAddresses = listOf("192.168.7.31", "100.97.198.90"),
        )
        assertEquals(
            "Retrofit not applied · 1 of 2 needs it · your devices arrive on 192.168.7.31, 100.97.198.90",
            AppRules.retrofitNote(list),
        )
        assertNull(
            AppRules.retrofitNote(list.copy(retrofitApplied = true)),
            "a finished transition has nothing to say",
        )
        assertNull(
            AppRules.retrofitNote(AppList(apps = listOf(app()), clientAddresses = listOf("192.168.7.31"))),
            "nothing needs it, so nothing is said",
        )
    }

    // ---------------------------------------------------------- the add form

    /**
     * ⚠⚠ A REFUSAL KEEPS WHAT WAS TYPED (decision 54). The 422 is the daemon
     * saying "this address is not reachable from your devices YET" — the person
     * is going to run the fix lines and press Add again, and a form that emptied
     * itself would make them retype the address they were just told was the
     * right one.
     */
    @Test
    fun `a refusal keeps the typed fields and carries the fix onto the form`() {
        val typed = AppForm(
            name = "Board view",
            url = "http://huginn:8092/",
            kind = "tool",
            notes = "KiCad",
            unit = "boardserver.service",
        )
        val refused = typed.refused(
            AppCreate(
                app = null,
                refusal = "boardserver answers here but not on the addresses your devices arrive from",
                reachable = AppReachability(
                    ok = false,
                    addresses = listOf(AppAddress("192.168.7.31:8092", ok = false, error = "connection refused")),
                    fix = listOf("systemctl edit boardserver.service", "systemctl restart boardserver"),
                ),
            ),
        )
        assertEquals("Board view", refused.name)
        assertEquals("http://huginn:8092/", refused.url, "the address it refused is the one it wants kept")
        assertEquals("tool", refused.kind)
        assertEquals("KiCad", refused.notes)
        assertEquals("boardserver.service", refused.unit)
        assertEquals(
            "boardserver answers here but not on the addresses your devices arrive from",
            refused.refusal,
        )
        assertEquals(
            listOf("systemctl edit boardserver.service", "systemctl restart boardserver"),
            refused.fix,
        )
        assertEquals(1, refused.addresses.size)
        assertEquals("", refused.note, "nothing to add on this one, and nothing invented")

        // And typing again clears the refusal: an error about text that has since
        // been changed is an error about nothing.
        val edited = refused.copy(url = "http://huginn:8094/").cleared()
        assertNull(edited.refusal)
        assertTrue(edited.fix.isEmpty())
        assertEquals("http://huginn:8094/", edited.url)
    }

    @Test
    fun `the form knows when it is complete enough to send`() {
        assertFalse(AppForm().sendable, "a name and an address at the very least")
        assertFalse(AppForm(name = "Board view").sendable)
        assertFalse(AppForm(name = "Board view", url = "example.com").sendable, "and a legal one")
        assertTrue(AppForm(name = "Board view", url = "http://huginn:8092/").sendable)
    }
}
