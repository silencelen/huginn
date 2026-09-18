package com.silencelen.huginn.ui

import com.silencelen.huginn.data.Console
import com.silencelen.huginn.data.ConsoleApproval
import com.silencelen.huginn.data.ConsoleApprovalStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The consoles card, and the two things it must never get wrong: what it claims
 * about a page nobody has checked, and who runs the commands it lists.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class ConsolesViewTest {

    private fun console(id: String, up: Boolean?) =
        Console(id = id, name = id, url = "http://huginn:8088/", up = up, reachableFrom = "host")

    @Test
    fun `the unchecked ones are counted separately, never folded into down`() {
        // ⚠ "3 of 4 answering" with the fourth never probed is a number that sends
        // somebody to restart a healthy service — and after a daemon restart that
        // is every row, because probe state lives in memory.
        val rows = listOf(
            console("armap", true),
            console("jtyper", true),
            console("board", false),
            console("btc15m", null),
        )
        assertEquals("2 of 4 answering from the host · 1 not checked", consolesStatusWords(rows))
        assertEquals(
            "2 of 2 answering from the host",
            consolesStatusWords(listOf(console("a", true), console("b", true))),
        )
        assertEquals("none listed", consolesStatusWords(emptyList()))
    }

    @Test
    fun `an empty list explains what a console even is`() {
        assertTrue(CONSOLES_EMPTY.contains("internal page this host serves"), CONSOLES_EMPTY)
        assertTrue(
            CONSOLES_EMPTY.contains("probed from there"),
            "the caveat belongs in the empty state too: $CONSOLES_EMPTY",
        )
    }

    /**
     * ⚠⚠ THE APPROVAL CARD IS THE ONE PLACE IN THIS FEATURE WHERE BEING WRONG IS
     * DANGEROUS. It lists steps that rebind a systemd unit on this host and add
     * firewall lines on a different machine. No route applies them — the card's
     * only control is Copy — so its job is to show every one of them and say who
     * runs them. A card that showed three of four commands, or showed them
     * without the sentence, would be consent to something nobody agreed to.
     */
    @Test
    fun `the card lists every command and carries the never-run sentence`() {
        val a = ConsoleApproval(
            applied = false,
            runBy = "owner",
            title = "Reach these consoles from outside the host",
            steps = listOf(
                ConsoleApprovalStep(
                    id = "rebind", where = "huginn", summary = "Bind to 0.0.0.0",
                    commands = listOf("systemctl edit armap", "systemctl restart armap"),
                ),
                ConsoleApprovalStep(
                    id = "firewall", where = "heimdall", summary = "Open the ports",
                    file = "/etc/pve/firewall/117.fw",
                    commands = listOf("vi /etc/pve/firewall/117.fw", "pve-firewall restart"),
                ),
            ),
            note = "owner runs these",
        )
        assertTrue(ConsoleRules.hasApproval(a), "an approval with steps must draw the card")
        assertEquals(
            listOf(
                "systemctl edit armap",
                "systemctl restart armap",
                "vi /etc/pve/firewall/117.fw",
                "pve-firewall restart",
            ),
            ConsoleRules.approvalCommands(a),
            "all four, across both steps, in order, untouched",
        )
        assertTrue(
            ConsoleRules.APPROVAL_NEVER_RUN.contains("never runs these"),
            ConsoleRules.APPROVAL_NEVER_RUN,
        )
        assertEquals("Not applied — these consoles answer on the host only.", ConsoleRules.approvalWords(a))
        // The copy control's payload carries every command too — it is the only
        // control the card has.
        val text = ConsoleRules.approvalText(a)
        ConsoleRules.approvalCommands(a).forEach {
            assertTrue(text.contains(it), "copyable text is missing: $it")
        }
    }
}
