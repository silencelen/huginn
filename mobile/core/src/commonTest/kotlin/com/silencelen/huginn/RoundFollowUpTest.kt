package com.silencelen.huginn

import com.silencelen.huginn.data.Round
import com.silencelen.huginn.data.RoundItem
import com.silencelen.huginn.data.RoundRun
import com.silencelen.huginn.ui.followUpDraft
import com.silencelen.huginn.ui.worthContinuing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The door out of a finished Round.
 *
 * Reported from real use: a Round showed red "Needs you", and tapping it landed
 * in a sealed run that said only "kept here for review". The status was right and
 * the seal was right; there was simply nowhere to go.
 */
class RoundFollowUpTest {

    private fun round(
        status: String = "action",
        items: List<RoundItem> = listOf(RoundItem("Bitcoin alert is unguarded", "117 messages", "Apply the swap-gate pattern")),
        goalMet: Boolean? = null,
        malformed: Boolean = false,
    ) = Round(
        id = "r1", title = "Telegram health check", cadence = "Sundays at 7:00 PM", mode = "act",
        lastRun = RoundRun(at = 1787600000, status = status, headline = "Quiet week, one thing armed",
            items = items, goalMet = goalMet, malformed = malformed, chatId = "c1"),
    )

    @Test
    fun aRoundThatNeedsYouOffersTheDoor() {
        assertTrue(worthContinuing(round(status = "action")))
        assertTrue(worthContinuing(round(status = "attention")))
    }

    @Test
    fun anAllClearRoundDoesNot() {
        // An offer on every finished run is an offer that means nothing on the
        // ones that matter.
        assertFalse(worthContinuing(round(status = "ok", items = emptyList())))
        assertFalse(worthContinuing(null))
        assertFalse(worthContinuing(Round(id = "r", lastRun = null)))
    }

    @Test
    fun anUnmetGoalOrABrokenReportIsAlsoWorthCarryingOn() {
        // Both are "the job did not land", however cheerful the headline was.
        assertTrue(worthContinuing(round(status = "ok", items = emptyList(), goalMet = false)))
        assertTrue(worthContinuing(round(status = "ok", items = emptyList(), malformed = true)))
    }

    @Test
    fun theDraftCarriesTheReportSoTheNextChatIsNotBlank() {
        val d = followUpDraft(round())
        assertTrue(d.contains("Telegram health check"), d)
        assertTrue(d.contains("Sundays at 7:00 PM"), d)
        assertTrue(d.contains("Quiet week, one thing armed"), d)
        assertTrue(d.contains("1. Bitcoin alert is unguarded"), d)
        // The whole reason RoundItem.suggest exists, and the first thing to consume it.
        assertTrue(d.contains("suggested: Apply the swap-gate pattern"), d)
    }

    @Test
    fun everyItemIsNumberedAndCarried() {
        val many = (1..3).map { RoundItem("item $it", "detail $it", "do $it") }
        val d = followUpDraft(round(items = many))
        for (i in 1..3) {
            assertTrue(d.contains("$i. item $i"), d)
            assertTrue(d.contains("suggested: do $i"), d)
        }
    }

    @Test
    fun aRunWithNoItemsStillNamesWhatItIsAboutRatherThanBeingEmpty() {
        val d = followUpDraft(round(status = "ok", items = emptyList()))
        assertTrue(d.contains("Telegram health check"), d)
        assertTrue(d.isNotBlank())
    }

    @Test
    fun aRoundThatNeverRanProducesNothingRatherThanAStub() {
        assertEquals("", followUpDraft(Round(id = "r", title = "t", lastRun = null)))
    }

    @Test
    fun anItemMissingItsPartsDoesNotPrintBlanksOrNulls() {
        val d = followUpDraft(round(items = listOf(RoundItem("", "", ""))))
        assertTrue(d.contains("1. (untitled)"), d)
        assertFalse(d.contains("suggested:"), d)
        assertFalse(d.contains("null"), d)
    }
}
