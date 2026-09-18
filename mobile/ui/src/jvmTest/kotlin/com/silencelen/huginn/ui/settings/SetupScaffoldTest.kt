package com.silencelen.huginn.ui.settings

import com.silencelen.huginn.settings.SetupFlow
import com.silencelen.huginn.settings.SetupState
import com.silencelen.huginn.settings.SetupStep
import com.silencelen.huginn.settings.StepStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What the first-run frame SAYS, asserted without rendering it.
 *
 * There is no compose-ui-test in these modules (see `CapBeforeFillTest`'s
 * header), so the decisions live in [SetupScaffoldRules] and the composable is a
 * thin drawing of them. All three failures covered here are invisible rather
 * than loud: a primary button reading "Next" over a step nobody has checked
 * invites somebody to walk past the one thing that was going to break; a
 * declined step drawn in the failure tone turns "not now" into a red mark
 * somebody then tries to fix; and a Passed step that stops showing WHAT PROVED
 * IT gives back exactly the screen this flow exists to replace — all green, and
 * possibly connected to nothing.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class SetupScaffoldTest {

    @Test
    fun `the primary button tells apart untried, failed and answered`() {
        val fresh = SetupState()
        assertEquals("Try the address", SetupScaffoldRules.primaryLabel(fresh))

        val failed = SetupFlow.fail(fresh, "connection refused")
        assertEquals("Try again", SetupScaffoldRules.primaryLabel(failed))

        val passed = SetupFlow.pass(fresh, "appd 3.1.0")
        assertEquals("Next", SetupScaffoldRules.primaryLabel(passed))
    }

    @Test
    fun `the last step says Done rather than pointing at nothing`() {
        val last = SetupFlow.STEPS.last()
        val onLast = SetupState(current = last, results = mapOf(last to StepStatus.Passed("on")))
        assertEquals("Done", SetupScaffoldRules.primaryLabel(onLast))
        // …and it still offers its own verb before it has been run.
        assertEquals(
            SetupScaffoldRules.verb(last),
            SetupScaffoldRules.primaryLabel(SetupState(current = last)),
        )
    }

    @Test
    fun `every step is skippable until it has been answered`() {
        for (step in SetupFlow.STEPS) {
            assertTrue(
                SetupScaffoldRules.canSkip(SetupState(current = step)),
                "$step must be declinable — the owner's rule is that every step is skippable",
            )
            assertTrue(SetupScaffoldRules.skipLabel(step).isNotBlank())
        }
        // Once it is answered there is nothing left to decline, so the button
        // goes rather than sitting there greyed.
        val answered = SetupFlow.pass(SetupState(), "appd 3.1.0")
        assertFalse(SetupScaffoldRules.canSkip(answered))
    }

    @Test
    fun `a failed step still has a way forward, and it is not the skip button`() {
        // ⚠ FOUND BY WALKING THE REAL FLOW. A failed step is RESOLVED, so the
        // decline button goes; its primary is a retry. That left "Try again" and
        // "Back" as the only two things on screen, and a reader whose daemon is
        // down was trapped on step one of seven — while SetupFlow.canAdvance had
        // said all along that they could move on.
        val failed = SetupFlow.fail(SetupState(), "connection refused")
        assertTrue(SetupFlow.canAdvance(failed), "the machine already allows it")
        assertFalse(SetupScaffoldRules.canSkip(failed), "there is nothing left to decline")
        assertTrue(SetupScaffoldRules.canMoveOn(failed), "…so something must still move forward")
        assertEquals("Continue anyway", SetupScaffoldRules.MOVE_ON)

        // And ONLY there: a step nobody has tried is declined, not continued
        // past, and an answered one already has "Next".
        assertFalse(SetupScaffoldRules.canMoveOn(SetupState()))
        assertFalse(SetupScaffoldRules.canMoveOn(SetupFlow.pass(SetupState(), "ok")))
        assertFalse(SetupScaffoldRules.canMoveOn(SetupFlow.skip(SetupState()).copy(current = SetupStep.ROUTE)))
    }

    @Test
    fun `a decline is toned differently from a failure`() {
        assertEquals(SetupTone.WAITING, SetupScaffoldRules.toneOf(StepStatus.Pending))
        assertEquals(SetupTone.PROVEN, SetupScaffoldRules.toneOf(StepStatus.Passed("appd 3.1.0")))
        assertEquals(SetupTone.REFUSED, SetupScaffoldRules.toneOf(StepStatus.Failed("401")))
        assertEquals(SetupTone.DECLINED, SetupScaffoldRules.toneOf(StepStatus.Skipped()))
        assertNotEquals(
            SetupScaffoldRules.toneOf(StepStatus.Failed("401")),
            SetupScaffoldRules.toneOf(StepStatus.Skipped()),
            "a skipped step must not be drawn as a broken one",
        )
    }

    @Test
    fun `a proven step shows what proved it, never just a tick`() {
        assertEquals(
            "appd 3.1.0 answered at 100.97.198.90:8787",
            SetupScaffoldRules.stateWords(StepStatus.Passed("appd 3.1.0 answered at 100.97.198.90:8787")),
        )
        // The daemon's own refusal, verbatim — a paraphrase here would undo the
        // whole point of running the call.
        assertEquals(
            "401 unauthorized: bearer rejected",
            SetupScaffoldRules.stateWords(StepStatus.Failed("401 unauthorized: bearer rejected")),
        )
        assertEquals("not checked yet", SetupScaffoldRules.stateWords(StepStatus.Pending))
        assertEquals("skipped", SetupScaffoldRules.stateWords(StepStatus.Skipped()))
        assertEquals(
            "skipped — ${SetupFlow.GATE_SKIP}",
            SetupScaffoldRules.stateWords(StepStatus.Skipped(SetupFlow.GATE_SKIP)),
            "a step that never got its chance says why, rather than reading as a decline",
        )
        // A pass with nothing to show still says SOMETHING rather than drawing
        // an empty line under the control.
        assertEquals("checked", SetupScaffoldRules.stateWords(StepStatus.Passed("")))
    }

    @Test
    fun `every step has words, and no two share a rail label`() {
        val labels = SetupFlow.STEPS.map { SetupScaffoldRules.railLabel(it) }
        assertEquals(labels.size, labels.toSet().size, "duplicate rail labels in $labels")
        for (step in SetupFlow.STEPS) {
            assertTrue(SetupScaffoldRules.title(step).isNotBlank(), "no title for $step")
            assertTrue(SetupScaffoldRules.blurb(step).isNotBlank(), "no blurb for $step")
            assertTrue(SetupScaffoldRules.verb(step).isNotBlank(), "no verb for $step")
        }
    }

    @Test
    fun `the copy never promises a step is required`() {
        // The owner's rule, and the sentence the screen opens with: a skipped
        // step is an answer. Copy that said "must" or "required" would teach
        // people to force their way through a step that cannot work on their
        // machine — which on Linux is at least one of them.
        val all = buildList {
            add(SetupScaffoldRules.TITLE)
            add(SetupScaffoldRules.BLURB)
            SetupFlow.STEPS.forEach { add(SetupScaffoldRules.title(it)); add(SetupScaffoldRules.blurb(it)) }
        }.joinToString(" ").lowercase()
        for (word in listOf("required", "you must", "mandatory")) {
            assertFalse(all.contains(word), "setup copy claims a step is compulsory: '$word'")
        }
    }
}
