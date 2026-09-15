package com.silencelen.huginn.ui

import com.silencelen.huginn.data.AccountSwitch
import com.silencelen.huginn.data.HeadroomSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The settings form's refusals.
 *
 * ⚠ THE DAEMON IS THE AUTHORITY. `validateSettings` decides and answers a broken
 * rule with a 400 naming it; every rule here MIRRORS one of those, and a
 * disagreement is a bug in [HeadroomForm] rather than a second opinion. What this
 * buys is that an edit which cannot succeed is never sent — a Save that
 * round-trips to a 400 the reader has to decode is a worse version of the same
 * answer, not a safer one.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class HeadroomFormTest {

    private val good = HeadroomSettings(
        headsUpPct = 85,
        ladderPct = 92,
        ladderUpBelowPct = 50,
        stopPct = 70,
        stopFablePct = 88,
        clearBelowPct = 50,
        ladder = listOf("fable", "opus", "sonnet"),
        defaultModel = "claude-fable-5-1",
        resumePhrase = "Your usage limit has reset. Continue.",
        headsUpText = "[huginn headroom] You are at {pct}% of this account's Fable weekly limit.",
    )

    private fun fieldsBrokenBy(s: HeadroomSettings): List<String> =
        HeadroomForm.problems(s).map { it.field }

    @Test
    fun `the daemon's own defaults pass`() {
        // The form must not refuse the settings the daemon ships with, which is
        // what a form drawn before the first answer lands is showing.
        assertTrue(HeadroomForm.valid(good), HeadroomForm.problems(good).toString())
    }

    @Test
    fun `a heads-up at or after the ladder move is refused`() {
        // THE RULE THIS SECTION EXISTS FOR. A warning that fires at the same
        // moment as the move it is warning about is not a warning — the session
        // gets told to write a handoff note in the same breath as being moved.
        assertTrue("headsUpPct" in fieldsBrokenBy(good.copy(headsUpPct = 92)))
        assertTrue("headsUpPct" in fieldsBrokenBy(good.copy(headsUpPct = 95)))
        assertFalse("headsUpPct" in fieldsBrokenBy(good.copy(headsUpPct = 91)))
    }

    @Test
    fun `the other two orderings are refused the same way`() {
        assertTrue("clearBelowPct" in fieldsBrokenBy(good.copy(clearBelowPct = 70)))
        // Going back up at or above the level that moved it down would ladder a
        // session down and up forever on one reading.
        assertTrue("ladderUpBelowPct" in fieldsBrokenBy(good.copy(ladderUpBelowPct = 92)))
    }

    @Test
    fun `a percentage outside 1 to 100 is refused wherever it appears`() {
        assertTrue("ladderPct" in fieldsBrokenBy(good.copy(ladderPct = 0)))
        assertTrue("stopFablePct" in fieldsBrokenBy(good.copy(stopFablePct = 101)))
    }

    @Test
    fun `the ladder has to be a distinct, non-empty list of families the host knows`() {
        assertTrue("ladder" in fieldsBrokenBy(good.copy(ladder = emptyList())))
        assertTrue("ladder" in fieldsBrokenBy(good.copy(ladder = listOf("fable", "fable"))))
        assertTrue("ladder" in fieldsBrokenBy(good.copy(ladder = listOf("fable", "gpt"))))
        assertTrue(HeadroomForm.valid(good.copy(ladder = listOf("opus", "haiku"))))
    }

    @Test
    fun `a resume phrase cannot be empty and cannot be a slash command`() {
        assertTrue("resumePhrase" in fieldsBrokenBy(good.copy(resumePhrase = "   ")))
        // Typing "/clear" into a session that was waiting to carry on working is
        // the opposite of resuming it.
        assertTrue("resumePhrase" in fieldsBrokenBy(good.copy(resumePhrase = "/resume")))
        assertTrue("resumePhrase" in fieldsBrokenBy(good.copy(resumePhrase = "x".repeat(301))))
    }

    @Test
    fun `the heads-up message must carry the number it is about`() {
        assertTrue("headsUpText" in fieldsBrokenBy(good.copy(headsUpText = "You are running low.")))
        assertTrue("headsUpText" in fieldsBrokenBy(good.copy(headsUpText = "{pct}" + "x".repeat(600))))
        // Empty is not broken: it means "say nothing", which is a setting.
        assertTrue(HeadroomForm.valid(good.copy(headsUpText = "")))
    }

    @Test
    fun `account switching bounds are checked even while it is off`() {
        // The form can be saved with switching disabled and a nonsense threshold
        // sitting underneath it, and the next person to turn it on inherits that.
        val s = good.copy(accountSwitch = AccountSwitch(enabled = false, threshold = 120, margin = 20))
        assertEquals(listOf("accountSwitch"), fieldsBrokenBy(s))
    }

    // ------------------------------------------------------------- slider ranges

    @Test
    fun `a threshold's slider and its number box share one range`() {
        // THE DEFECT. The slider was a flat 50..100 for every field and the box
        // clamped to 1..100, so a typed 20 in "Treat a window as cleared below"
        // was snapped back to 50 by the next touch of the slider beside it — a
        // form arguing with itself about a value it had just accepted.
        assertEquals(1..99, HeadroomForm.range("clearBelowPct"))
        assertEquals(1..99, HeadroomForm.range("ladderUpBelowPct"))
        assertEquals(50..100, HeadroomForm.range("headsUpPct"))
        assertEquals(50..100, HeadroomForm.range("ladderPct"))
        assertEquals(50..100, HeadroomForm.range("stopPct"))
        assertEquals(50..100, HeadroomForm.range("stopFablePct"))
        assertEquals(1..100, HeadroomForm.range("accountSwitch.threshold"))
        assertEquals(0..100, HeadroomForm.range("accountSwitch.margin"))
    }

    @Test
    fun `every setting the daemon would accept is reachable on its own slider`() {
        // A range that cannot express a valid setting is a control that lies about
        // what the host will take. Walked field by field against the SAME
        // `problems` the Save button is gated on.
        val cases = listOf(
            "clearBelowPct" to good.copy(clearBelowPct = 20),
            "ladderUpBelowPct" to good.copy(ladderUpBelowPct = 20),
            "headsUpPct" to good.copy(headsUpPct = 85),
            "ladderPct" to good.copy(ladderPct = 92),
            "stopPct" to good.copy(stopPct = 70),
            "stopFablePct" to good.copy(stopFablePct = 88),
        )
        for ((field, settings) in cases) {
            val value = when (field) {
                "clearBelowPct" -> settings.clearBelowPct
                "ladderUpBelowPct" -> settings.ladderUpBelowPct
                "headsUpPct" -> settings.headsUpPct
                "ladderPct" -> settings.ladderPct
                "stopPct" -> settings.stopPct
                else -> settings.stopFablePct
            }
            assertTrue(HeadroomForm.valid(settings), "$field=$value: " + HeadroomForm.problems(settings))
            assertTrue(value in HeadroomForm.range(field), "$field=$value is off its own slider")
        }
    }

    @Test
    fun `no range lets a field out of the one to a hundred the daemon enforces`() {
        // The daemon refuses anything outside 1..100 for a threshold; a slider
        // that can reach 0 is a control whose Save can only 400.
        for (field in listOf(
            "headsUpPct", "ladderPct", "ladderUpBelowPct", "stopPct", "stopFablePct", "clearBelowPct",
        )) {
            val r = HeadroomForm.range(field)
            assertTrue(r.first >= 1, "$field can reach ${r.first}")
            assertTrue(r.last <= 100, "$field can reach ${r.last}")
        }
    }

    @Test
    fun `a below field can never be set to a hundred, which no ordering rule allows`() {
        assertEquals(99, HeadroomForm.range("clearBelowPct").last)
        assertTrue("clearBelowPct" in fieldsBrokenBy(good.copy(clearBelowPct = 100)))
        assertTrue("ladderUpBelowPct" in fieldsBrokenBy(good.copy(ladderUpBelowPct = 100)))
    }
}
