package com.silencelen.huginn.desktop.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Who counts as sitting at a Windows machine.
 *
 * ⚠ THIS IS THE FENCE, not a label. The answer decides whether a remote request
 * is allowed to become Bash on somebody's PC, so it is asserted against real
 * `quser` output rather than against the parser's own idea of what quser looks
 * like. Every fixture below is the literal shape the command prints — column
 * alignment, the `>` on the caller's own row, the shifted columns a
 * disconnected session produces, and the error text it writes when nobody is
 * signed in at all.
 *
 * The rule under test:
 *
 *   an RDP session in state Active, whose session has no lock screen up  →  somebody is here
 *   anything else                                                       →  is LogonUI running (today's rule)
 *   the probe could not answer                                          →  locked, as it always was
 */
class LockProbeTest {

    // The caller's own session carries `>`; STATE is the fourth column.
    private val consoleActive = """
        | USERNAME              SESSIONNAME        ID  STATE   IDLE TIME  LOGON TIME
        |>silencelen            console             1  Active      none   9/14/2026 8:02 AM
    """.trimMargin().lowercase()

    // What the owner's box looks like while they are working over RDP: the
    // console session it was left in sits locked as session 1 — LogonUI and all
    // — and the remote session is a second one, which is exactly why asking
    // "is A lock screen up" gets this machine wrong.
    private val rdpActive = """
        | USERNAME              SESSIONNAME        ID  STATE   IDLE TIME  LOGON TIME
        |>silencelen            rdp-tcp#7           2  Active          .  9/14/2026 8:02 AM
    """.trimMargin().lowercase()

    // ⚠ THE COLUMNS SHIFT. A disconnected session prints NO session name, so
    // splitting on whitespace gives one field fewer and a parser that counted
    // columns reads the ID as the state and the state as the idle time.
    private val rdpDisconnected = """
        | USERNAME              SESSIONNAME        ID  STATE   IDLE TIME  LOGON TIME
        | silencelen                                1  Disc         1:20  9/14/2026 8:02 AM
    """.trimMargin().lowercase()

    // quser writes this to stderr and exits non-zero; the probe folds stderr in,
    // so the parser sees it and must not read it as a row.
    private val noUser = "No User exists for *".lowercase()

    // A machine somebody is at over RDP while a second, older session sits
    // disconnected — the reason rows are filtered rather than counted.
    private val both = """
        | USERNAME              SESSIONNAME        ID  STATE   IDLE TIME  LOGON TIME
        |>silencelen            rdp-tcp#7           2  Active          .  9/14/2026 8:02 AM
        | someone                                   3  Disc         1:20  9/13/2026 1:00 PM
    """.trimMargin().lowercase()

    @Test
    fun theParserReadsBothColumnShapes() {
        val console = LockProbe.parseQuser(consoleActive)
        assertEquals(1, console.size, "one row: the header is not a session")
        assertEquals("console", console[0].session)
        assertEquals(1, console[0].id)
        assertEquals("active", console[0].state)
        assertTrue(console[0].current, "the > marks the session the probe itself ran in")

        val disc = LockProbe.parseQuser(rdpDisconnected)
        assertEquals(1, disc.size)
        assertEquals("", disc[0].session, "a disconnected row prints no session name")
        assertEquals(1, disc[0].id, "and the ID must still be the ID")
        assertEquals("disc", disc[0].state, "not the idle time")

        assertEquals(emptyList(), LockProbe.parseQuser(noUser), "an error line is not a row")
        assertEquals(emptyList(), LockProbe.parseQuser(""), "and neither is nothing")
        assertEquals(2, LockProbe.parseQuser(both).size)
    }

    @Test
    fun anActiveRdpSessionIsSomebodyBeingThere() {
        // The bug the owner reported: a box reached over RDP reads as locked,
        // because the console session it left behind has a lock screen up.
        assertEquals(false, LockProbe.verdict(rdpActive, setOf(1)),
            "an RDP session in Active must count as somebody being here")
        assertEquals(false, LockProbe.verdict(both, setOf(1)),
            "...even beside a stale disconnected session")
    }

    @Test
    fun aDisconnectedRdpSessionIsNobody() {
        assertEquals(true, LockProbe.verdict(rdpDisconnected, setOf(1)),
            "Disc is the opposite of being there")
        assertEquals(false, LockProbe.verdict(rdpDisconnected, emptySet()),
            "though with no lock screen anywhere it is not LOCKED either — that is today's rule")
    }

    @Test
    fun theConsoleRuleIsUnchanged() {
        assertEquals(false, LockProbe.verdict(consoleActive, emptySet()), "console, nothing locked")
        assertEquals(true, LockProbe.verdict(consoleActive, setOf(1)),
            "a console session reads Active even when it is locked — LogonUI is what tells them apart")
        assertEquals(true, LockProbe.verdict(noUser, setOf(1)),
            "the sign-in screen IS LogonUI: nobody is here")
        assertEquals(false, LockProbe.verdict(noUser, emptySet()))
    }

    @Test
    fun theRemoteScreenCanBeLockedToo() {
        // ⚠ THE NARROW PART OF THE WIDENING. "RDP is active" would otherwise
        // grant act to a remote session sitting on its own lock screen, which is
        // the same unwatched machine the rule exists for. The session id on the
        // row and the session id LogonUI runs in are what separate them.
        assertEquals(true, LockProbe.verdict(rdpActive, setOf(1, 2)),
            "a lock screen IN the RDP session is still a lock screen")
        assertEquals(true, LockProbe.verdict(both, setOf(2)),
            "the id must match the row, not merely exist")
    }

    @Test
    fun anUnanswerableProbeStaysLocked() {
        assertEquals(true, LockProbe.verdict(null, null), "no answer at all")
        assertEquals(true, LockProbe.verdict(rdpActive, null),
            "half an answer is not an answer: without the lock screens nothing can be ruled out")
        assertEquals(false, LockProbe.verdict(null, emptySet()),
            "no quser (it is absent on some editions) falls back to today's rule, not to a refusal")
        assertEquals(true, LockProbe.verdict("nonsense that is not a table", setOf(1)))
    }
}
