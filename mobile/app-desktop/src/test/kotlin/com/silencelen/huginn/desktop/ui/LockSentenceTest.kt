package com.silencelen.huginn.desktop.ui

import com.silencelen.huginn.desktop.ui.settings.lockSentence
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WHAT SETTINGS TELLS A READER ABOUT THE LOCK — and the one word it may not say
 * to a machine that has no lock.
 *
 * ⚠⚠ THE BRANCH WAS CHOSEN BY `os.name`. `LockProbe.supported()` answered "is
 * this Windows or Linux", which is not the question: a Linux box with no logind
 * session — an LXC, a container, `startx` without `pam_systemd`, a kiosk —
 * cannot answer the lock probe at all, fell through to *"reads as locked … until
 * someone unlocks it"*, and stayed on that sentence for the life of the process
 * while `/v1/devices` reported `locked: true, effectiveScope: look`. Nobody can
 * unlock it. There is no lock. The only sentence on screen described the one act
 * that cannot be performed, and the switch that WOULD change it was not
 * mentioned.
 *
 * So the choice is made on whether the probe ANSWERED, and these four cases are
 * the whole of it. The sentence is pure for exactly this reason: every branch
 * draws perfectly, and only its words are wrong.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class LockSentenceTest {

    private fun sentence(
        actWhileLocked: Boolean = false,
        locked: Boolean = false,
        lockKnown: Boolean = true,
        windows: Boolean = false,
    ) = lockSentence(actWhileLocked, locked, lockKnown, windows)

    @Test
    fun `an unanswerable probe never tells anyone to unlock anything`() {
        val unknown = sentence(locked = true, lockKnown = false)
        assertFalse(
            unknown.contains("unlocks it"),
            "there is no screen to unlock on a machine with no session: $unknown",
        )
        assertTrue(
            unknown.contains("could say whether it is locked"),
            "it must say the probe could not answer: $unknown",
        )
        assertTrue(
            unknown.contains("switch above"),
            "and it must name the one control that CAN change the outcome: $unknown",
        )
    }

    @Test
    fun `the unknown branch wins over the locked branch, because locked is its default`() {
        // LockProbe.Reading.UNKNOWN.locked is true by design — the fence stays
        // shut — so the two are seen together on every such machine and the order
        // of the `when` is the whole fix.
        assertEquals(
            sentence(locked = true, lockKnown = false),
            sentence(locked = false, lockKnown = false),
            "with no answer, what `locked` happens to hold must not change the words",
        )
    }

    @Test
    fun `a machine that really is locked still says so, and says who can undo it`() {
        val locked = sentence(locked = true, lockKnown = true)
        assertTrue(locked.contains("until someone unlocks it"), locked)
    }

    @Test
    fun `the waiver is said first, because it is the only line still true`() {
        // A reader who has turned the switch on must not be told the machine is
        // read-only — whatever the probe says, or fails to say.
        listOf(true to true, true to false, false to true, false to false).forEach { (locked, known) ->
            val s = sentence(actWhileLocked = true, locked = locked, lockKnown = known)
            assertTrue(s.startsWith("A lock screen changes nothing here."), s)
        }
    }

    /**
     * And Settings really asks THIS. A pure sentence chooser proves nothing if the
     * screen still asks `os.name` — which is exactly the shape the bug had, since
     * `LockProbe.supported()` compiles, draws, and is wrong.
     */
    @Test
    fun `settings chooses the sentence by the probe, not by the platform`() {
        val src = File(
            "src/main/kotlin/com/silencelen/huginn/desktop/ui/settings/ThisMachine.kt",
        ).readText()
        assertTrue(src.length > 5_000, "read as ${src.length} chars — wrong file")
        // To the function's OWN closing brace at column 0 — not to the next
        // declaration, whose KDoc names the bug and would satisfy the grep.
        val body = src.substringAfter("internal fun DeviceSection").substringBefore("\n}\n")
        assertTrue(body.length in 1..8_000, "DeviceSection was not found (${body.length} chars)")
        assertTrue("lockSentence(" in body, "the section must use the tested chooser")
        assertFalse(
            "LockProbe.supported()" in body,
            "the sentence must not be gated on os.name — that is the bug",
        )
    }

    @Test
    fun `the remote-desktop clause is said only on Windows, and only when the probe answered`() {
        assertTrue(sentence(windows = true).contains("remote-desktop"))
        assertFalse(sentence(windows = false).contains("remote-desktop"))
        assertFalse(
            sentence(lockKnown = false, windows = true).contains("remote-desktop"),
            "a box that cannot read its own lock has nothing to say about RDP sessions",
        )
    }
}
