package com.silencelen.huginn.desktop.ui

import com.silencelen.huginn.data.ConsoleApproval
import com.silencelen.huginn.data.ConsoleApprovalStep
import com.silencelen.huginn.ui.ConsoleRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WHAT THE CLIPBOARD GETS when the approval card's one control is pressed.
 *
 * ⚠⚠ THIS IS THE PLACE IN THIS FEATURE WHERE BEING WRONG IS EXPENSIVE. The card's
 * Copy is the only control it has, deliberately: the steps rebind a systemd unit
 * on the huginn host and add four firewall lines on heimdall, and nothing in this
 * product applies either (decision 47). What the card hands over is therefore not
 * a preview — it is the thing a person pastes into a root shell on two machines.
 *
 * So the text is asserted VERBATIM and IN ORDER. Not "contains the ports", not
 * "mentions systemctl": every command, character for character, in the sequence
 * the daemon wrote them, because a reformatted `-dport 8093` is a firewall rule
 * for the wrong service and a re-ordered rebind restarts a unit before it has
 * been edited. `ConsoleRules.approvalText` is the builder — pure, shared with the
 * phone so both clients copy identical text — and the card's `onCopy` hands its
 * output straight to the desktop clipboard without touching it.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class ConsoleApprovalCopyTest {

    /** The live registry's own approval, as `consoles.json` carries it. */
    private val approval = ConsoleApproval(
        applied = false,
        runBy = "owner",
        markerPath = "/var/lib/huginn-appd/consoles-rebind-applied",
        title = "Open these from your phone or laptop",
        why = "Three of these pages are bound to this host only, so the probe below can reach " +
            "them and your devices cannot.",
        steps = listOf(
            ConsoleApprovalStep(
                id = "rebind",
                where = "huginn (this host)",
                summary = "Bind the three units to 0.0.0.0. btc15m-sim already does.",
                commands = listOf(
                    "systemctl edit armap.service",
                    "systemctl edit jtyper-trainer.service",
                    "systemctl edit boardserver.service",
                    "systemctl restart armap jtyper-trainer boardserver",
                    "ss -ltnp | grep -E '8088|8091|8092'",
                ),
            ),
            ConsoleApprovalStep(
                id = "firewall",
                where = "heimdall",
                summary = "Add four rules to /etc/pve/firewall/117.fw.",
                file = "/etc/pve/firewall/117.fw",
                commands = listOf(
                    "IN ACCEPT -source 192.168.2.131 -p tcp -dport 8088 -log nolog",
                    "IN ACCEPT -source 192.168.2.131 -p tcp -dport 8091 -log nolog",
                    "IN ACCEPT -source 192.168.2.131 -p tcp -dport 8092 -log nolog",
                    "IN ACCEPT -source 192.168.2.131 -p tcp -dport 8093 -log nolog",
                ),
            ),
        ),
        note = "Copy these into a netplan session and run them there.",
    )

    @Test
    fun `every command reaches the clipboard verbatim, in the daemon's order`() {
        val copied = ConsoleRules.approvalText(approval)
        val wanted = approval.steps.flatMap { it.commands }

        // ⚠⚠ ONE COMMAND, ONE WHOLE LINE, and the assertion is on the LINE rather
        // than on `contains` — which a `joinToString(" && ")` would sail straight
        // through, every substring still present and every command now chained to
        // the next. That is the exact shape of a "tidy-up" somebody would make:
        // five separate steps, one of which is a `systemctl edit` that opens an
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
        // check, the firewall lines in port order. Asserted as positions rather
        // than as membership, because a set does not have this property.
        val positions = wanted.map { lines.indexOf(it) }
        assertEquals(positions.sorted(), positions, "the steps arrived out of order: $positions")
        assertEquals(wanted, ConsoleRules.approvalCommands(approval), "the command list itself is the order")
    }

    @Test
    fun `the file a step edits travels with it, because it is on another machine`() {
        val copied = ConsoleRules.approvalText(approval)
        assertTrue(
            copied.contains("/etc/pve/firewall/117.fw"),
            "four ACCEPT lines with no file named are four lines nobody can place: $copied",
        )
        assertTrue(copied.contains("heimdall"), "and the machine they belong to: $copied")
    }

    @Test
    fun `nothing in this app runs them, and the card says so`() {
        // The sentence is what keeps a button-less card from reading as an
        // unfinished one — and without it somebody would eventually finish it.
        assertTrue(
            ConsoleRules.APPROVAL_NEVER_RUN.contains("huginn never runs these"),
            ConsoleRules.APPROVAL_NEVER_RUN,
        )
        assertEquals("owner", ConsoleRules.approvalRunBy(approval))
        assertTrue(ConsoleRules.hasApproval(approval), "a card with steps in it is worth drawing")
    }

    @Test
    fun `an approval that has been applied changes what the rows claim, not what they copy`() {
        val applied = approval.copy(applied = true)
        assertTrue(ConsoleRules.approvalApplied(applied))
        assertEquals(
            ConsoleRules.approvalCommands(approval),
            ConsoleRules.approvalCommands(applied),
            "the commands are a record of the job, not a to-do list that empties",
        )
        // "up from the host" stops being the necessary caveat once the rebind is in.
        assertTrue(ConsoleRules.reachabilityWords(true, 0, 0, applied = false).contains("from the host"))
        assertTrue(!ConsoleRules.reachabilityWords(true, 0, 0, applied = true).contains("from the host"))
    }
}
