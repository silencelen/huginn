package com.silencelen.huginn.desktop.ui

import com.silencelen.huginn.data.App
import com.silencelen.huginn.data.AppAddress
import com.silencelen.huginn.data.AppCreate
import com.silencelen.huginn.data.AppForm
import com.silencelen.huginn.data.AppReachability
import com.silencelen.huginn.ui.AppRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WHAT THE CLIPBOARD GETS when a failing app row's one control is pressed.
 *
 * ⚠⚠ THIS IS THE PLACE IN THIS FEATURE WHERE BEING WRONG IS EXPENSIVE. The
 * disclosure's Copy is the only control it has, deliberately: the lines rebind a
 * systemd unit on the huginn host and sometimes add firewall lines on heimdall,
 * and nothing in this product applies either (decisions 47 and 55). What the row
 * hands over is therefore not a preview — it is the thing a person pastes into a
 * root shell on two machines.
 *
 * So the text is asserted VERBATIM and IN ORDER. Not "contains the ports", not
 * "mentions systemctl": every line, character for character, in the sequence the
 * daemon wrote them, because a reformatted `-dport 8093` is a firewall rule for
 * the wrong service and a re-ordered rebind restarts a unit before it has been
 * edited. `AppRules.fixText` is the builder — pure, shared with the phone so
 * both clients copy identical text — and the panel's `onCopy` hands its output
 * straight to the desktop clipboard without touching it.
 *
 * ⚠ THIS REPLACES `ConsoleApprovalCopyTest`. The list-level approval card is
 * gone: the same four firewall lines used to be drawn once for a whole registry,
 * leaving the reader to work out which two were about the row they were looking
 * at. The discipline it carried did not go anywhere — it moved onto the row.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class AppFixCopyTest {

    /** A row as the live registry would hand it over once the retrofit check ran. */
    private val row = App(
        id = "jtyper",
        name = "jtyper trainer",
        url = "http://huginn:8091/",
        kind = "lab",
        notes = "Blind test + chat + hole-fill flywheel.",
        unit = "jtyper-trainer.service",
        addedAt = 1_789_000_000,
        version = 2,
        up = true,
        lastProbeAt = 1_789_459_940,
        latencyMs = 48,
        httpStatus = 403,
        icon = false,
        reachable = AppReachability(
            ok = false,
            checkedAt = 1_789_459_940,
            addresses = listOf(
                AppAddress("127.0.0.1:8091", ok = true),
                AppAddress("192.168.7.31:8091", ok = false, error = "connection refused"),
                AppAddress("100.97.198.90:8091", ok = false, error = "connection refused"),
            ),
            fix = listOf(
                "systemctl edit jtyper-trainer.service   # ExecStart: bind 0.0.0.0 instead of 127.0.0.1",
                "systemctl restart jtyper-trainer",
                "ss -ltnp | grep 8091",
                "IN ACCEPT -source 192.168.2.131 -p tcp -dport 8091 -log nolog   # /etc/pve/firewall/117.fw on heimdall",
            ),
        ),
    )

    @Test
    fun `every line reaches the clipboard verbatim, in the daemon's order`() {
        val copied = AppRules.fixText(row)
        val wanted = row.reachable.fix

        // ⚠⚠ ONE LINE, ONE WHOLE LINE, and the assertion is on the LINE rather
        // than on `contains` — which a `joinToString(" && ")` would sail straight
        // through, every substring still present and every command now chained to
        // the next. That is the exact shape of a "tidy-up" somebody would make:
        // four separate steps, one of which is a `systemctl edit` that opens an
        // editor, welded into a single unrunnable line.
        val lines = copied.lines()
        wanted.forEach { command ->
            assertTrue(
                lines.contains(command),
                "not on a line of its own — rewritten, joined or wrapped on the way to the clipboard: " +
                    "$command\n---\n$copied",
            )
        }
        // AND IN ORDER: the rebind before the restart, the restart before the
        // check. Asserted as positions rather than as membership, because a set
        // does not have this property.
        val positions = wanted.map { lines.indexOf(it) }
        assertEquals(positions.sorted(), positions, "the steps arrived out of order: $positions")
        assertEquals(wanted, AppRules.fixLines(row), "the line list itself is the order")
    }

    /**
     * ⚠ THE PAYLOAD NAMES ITS SUBJECT, which the registry-wide card never had to.
     * A clipboard holding four `systemctl` lines with no app and no unit on it is
     * four lines nobody can place — and by the time they are pasted the reader
     * has left the screen that said which row they came from.
     */
    @Test
    fun `the app and its unit travel with the lines`() {
        val copied = AppRules.fixText(row)
        assertTrue(copied.lines().first().contains("jtyper trainer"), copied)
        assertTrue(copied.lines().first().contains("jtyper-trainer.service"), copied)
    }

    /**
     * ⚠ AND THE EVIDENCE TRAVELS TOO — but only the addresses that FAILED. The
     * one that answered is not part of the job; carrying it would make the reader
     * check three things when two are wrong.
     */
    @Test
    fun `the addresses that failed are named, and the one that answered is not`() {
        val copied = AppRules.fixText(row)
        assertTrue(copied.contains("192.168.7.31:8091 — connection refused"), copied)
        assertTrue(copied.contains("100.97.198.90:8091 — connection refused"), copied)
        assertFalse(
            copied.contains("127.0.0.1:8091"),
            "the address that answered is not part of the job: $copied",
        )
    }

    @Test
    fun `nothing in this app runs them, and the row says so`() {
        // The sentence is what keeps a one-control panel from reading as an
        // unfinished one — and without it somebody would eventually finish it.
        assertTrue(
            AppRules.FIX_NEVER_RUN.contains("huginn never runs these"),
            AppRules.FIX_NEVER_RUN,
        )
        assertTrue(AppRules.failing(row), "a row with lines on it is worth opening")
    }

    /**
     * ⚠ A ROW THAT ANSWERS EVERYWHERE COPIES NOTHING, and does not open at all.
     * An empty drawer with a Copy control that yields an empty clipboard is worse
     * than no drawer.
     */
    @Test
    fun `a healthy row has no disclosure and no payload`() {
        val fine = row.copy(
            reachable = AppReachability(
                ok = true,
                checkedAt = 1_789_459_940,
                addresses = listOf(AppAddress("192.168.7.31:8091", ok = true)),
            ),
        )
        assertFalse(AppRules.failing(fine))
        assertEquals("", AppRules.fixText(fine))
    }

    /**
     * ⚠⚠ AND THE REFUSED ADD COPIES THE SAME TEXT. A 422 hands over the identical
     * lines before there is any row to hang them on (decision 54), and the person
     * is going to run exactly the same commands — so one builder, or the two
     * paths eventually disagree about what the fix is.
     */
    @Test
    fun `the add form's refusal copies what the row would have copied`() {
        val form = AppForm(
            name = row.name,
            url = row.url,
            kind = row.kind,
            unit = row.unit.orEmpty(),
        ).refused(AppCreate(app = null, refusal = "not reachable yet", reachable = row.reachable))

        assertEquals(
            AppRules.fixText(row),
            AppRules.fixTextOf(form.name, form.unit, form.addresses, form.fix),
            "one payload, whether the row exists yet or not",
        )
        assertEquals(row.url, form.url, "and the form still holds the address it was refused for")
    }
}
