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
            assertTrue(SetupScaffoldRules.SKIP.isNotBlank())
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

/**
 * THE FINISH LINE, WHICH THE FLOW COUNTED AND NEVER SHOWED.
 *
 * ⚠ [SetupFlow.summary] IS THE POINT OF THE WHOLE MACHINE — "what works, what
 * you chose not to do, what is actually broken", three numbers said separately
 * because they mean three different things. It was drawn in the footer beside
 * the buttons, where it updates as you go, and then the last answer ADVANCED
 * THE FLOW and the controller closed the window: the reader's last sight of it
 * was the count before their final answer was in it. Pressing "Not now" on step
 * 7 dropped them straight into the app having never read the tally.
 *
 * So the flow has one more state — finished and still on screen — and its
 * primary button closes it.
 */
class SetupFinishTest {

    private fun everythingAnswered(): SetupState {
        var s = SetupState()
        for (step in SetupFlow.STEPS) {
            s = s.copy(current = step)
            s = if (step == SetupStep.LOCAL_AI) SetupFlow.fail(s, "this machine can't serve") else SetupFlow.pass(s, "ok")
        }
        return s.copy(finished = true)
    }

    @Test
    fun `a finished flow offers a way out rather than a way on`() {
        // ⚠ THE SAME WORDS AS THE FOOTER'S HATCH (D-14). It read "Close" beside a
        // "Close setup" 8 dp away, both doing the one thing; the footer's copy
        // now stands down here and the verb is spelled once.
        assertEquals(SetupScaffoldRules.CLOSE, SetupScaffoldRules.primaryLabel(everythingAnswered()))
    }

    @Test
    fun `nothing is skippable or continuable once it is over`() {
        val done = everythingAnswered()
        assertFalse(SetupScaffoldRules.canSkip(done), "there is no step left to decline")
        assertFalse(SetupScaffoldRules.canMoveOn(done), "nor anything to move on to")
    }

    @Test
    fun `the tally that gets shown counts the last answer too`() {
        val summary = SetupFlow.summary(everythingAnswered())
        assertEquals("6 of 7 set up · 1 could not be proven", summary)
    }

    @Test
    fun `the finish line has a heading of its own`() {
        assertTrue(SetupScaffoldRules.FINISHED_TITLE.isNotBlank())
        assertFalse(
            SetupScaffoldRules.FINISHED_TITLE.contains("complete", ignoreCase = true),
            "'setup complete' over two failures is the sentence this product does not write",
        )
    }
}

/**
 * ⚠ D-14, D-15, D-16. THE THREE THINGS THE FIRST-RUN FRAME SAID BADLY.
 *
 * All three are the same class of defect as the ones above: nothing throws, and
 * the only place any of them was ever visible is a walk through the real flow.
 *
 * **D-14 — two verbs for one action, twice.** The finish bar carried "Close
 * setup" AND "Close", both of which closed setup, 8 dp apart; and the decline
 * button said "Skip for now" on the first two steps and "Not now" on the next
 * three. A reader has to learn a verb once. Two words for one action is the same
 * defect as the add-route form's two Cancels (D-12) — the fix there was to have
 * one control, and it is the fix here.
 *
 * **D-15 — the missing state, as the screen draws it.** See [StepStatus.Checked]
 * in `:core`. This half is the rail line, the dot's tone and the primary button:
 * a step whose probe has run must not read "not checked yet", must not be drawn
 * in the proven tone, and must not still offer the probe that already ran.
 *
 * **D-16 — the reason, cut.** The rail drew every state line at `maxLines = 1`
 * inside `widthIn(max = 320.dp)`, so the notification step's failure read
 * "nothing on this computer can show a notification: …" and stopped. The reason
 * is the whole value of a [StepStatus.Failed] — the flow's argument for itself is
 * that it repeats what the far end said — and there was no tooltip, no expand and
 * no way to read it short of navigating back to the step.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class SetupRoundThreeTest {

    private fun root(): java.io.File =
        generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${java.io.File("").absolutePath}")

    private fun scaffoldSource(): String {
        val f = java.io.File(
            root(),
            "ui/src/commonMain/kotlin/com/silencelen/huginn/ui/settings/SetupScaffold.kt",
        )
        assertTrue(f.isFile, "not scanned: ${f.absolutePath}")
        val text = f.readText()
        assertTrue(text.length > 5_000, "read as ${text.length} chars — wrong file")
        return text
    }

    // ------------------------------------------------------------ D-14

    @Test
    fun `there is one way to leave, and it is called the same thing everywhere`() {
        val done = SetupState(finished = true)
        assertEquals(SetupScaffoldRules.CLOSE, SetupScaffoldRules.primaryLabel(done))
        // …and the footer's own escape hatch stands down once the primary IS it,
        // so the bar never carries two controls that do one thing.
        assertFalse(SetupScaffoldRules.showsCloseButton(done), "two closes, 8dp apart")
        assertTrue(
            SetupScaffoldRules.showsCloseButton(SetupState()),
            "a flow you cannot leave is one people force-quit rather than finish",
        )
    }

    @Test
    fun `the decline verb is one verb, on every step`() {
        // It said "Skip for now" on the address and the token and "Not now" on
        // claude, this computer and local AI. The word the flow RECORDS is
        // "skipped" — the rail says it and the tally counts it — so that is the
        // word the button says.
        assertEquals("Skip for now", SetupScaffoldRules.SKIP)
        assertTrue(
            SetupScaffoldRules.stateWords(StepStatus.Skipped()).contains("skip", ignoreCase = true),
            "the button and the record must use one word",
        )
    }

    // ------------------------------------------------------------ D-15

    @Test
    fun `a step whose probe has run never reads as unchecked`() {
        val checked = StepStatus.Checked("This machine can serve (class C), 2548 MB to download.")
        val words = SetupScaffoldRules.stateWords(checked)
        assertNotEquals(
            SetupScaffoldRules.stateWords(StepStatus.Pending),
            words,
            "the probe ran and printed its answer — the row cannot say 'not checked yet'",
        )
        assertTrue(words.contains("2548 MB"), "the answer itself is kept: $words")
        assertTrue(
            words.contains("waiting on you", ignoreCase = true),
            "…and it says whose move it is: $words",
        )
        // A check with nothing to show still says which state it is in.
        assertTrue(SetupScaffoldRules.stateWords(StepStatus.Checked("")).isNotBlank())
    }

    @Test
    fun `waiting on a person is toned as neither proven nor broken nor declined`() {
        val tone = SetupScaffoldRules.toneOf(StepStatus.Checked("can serve"))
        assertEquals(SetupTone.WAITING_ON_YOU, tone)
        for (other in listOf(
            SetupScaffoldRules.toneOf(StepStatus.Passed("ok")),
            SetupScaffoldRules.toneOf(StepStatus.Failed("no")),
            SetupScaffoldRules.toneOf(StepStatus.Skipped()),
            SetupScaffoldRules.toneOf(StepStatus.Pending),
        )) {
            assertNotEquals(other, tone, "a step waiting on a person has its own mark")
        }
    }

    @Test
    fun `the button stops offering the probe that already ran`() {
        val s = SetupFlow.checked(SetupState(current = SetupStep.LOCAL_AI), "can serve, 2548 MB")
        assertNotEquals(
            SetupScaffoldRules.verb(SetupStep.LOCAL_AI),
            SetupScaffoldRules.primaryLabel(s),
            "pressing it again ran the same check and changed nothing on screen",
        )
        assertEquals("Next", SetupScaffoldRules.primaryLabel(s))
        // And the decline is still offered: the choice is the reader's, and one
        // of the two answers is "no".
        assertTrue(SetupScaffoldRules.canSkip(s), "saying no is still an answer they can give")
    }

    // ------------------------------------------------------------ D-16

    @Test
    fun `the rail lets a reason use more than one line`() {
        // A SOURCE GATE: there is no compose-ui-test in these modules and the
        // failure is a measured ellipsis. The subject is the state line in
        // SetupRail — `maxLines = 1` there is what cut "nothing on this computer
        // can show a notification: …" off at its colon.
        val text = scaffoldSource()
        val at = text.indexOf("private fun SetupRail(")
        assertTrue(at > 0, "SetupRail has moved — this gate lost its subject")
        val rail = text.substring(at)
        assertTrue(
            "maxLines = SetupScaffoldRules.RAIL_REASON_LINES" in rail,
            "the rail's reason is being ellipsised away again",
        )
        assertTrue(SetupScaffoldRules.RAIL_REASON_LINES >= 3, "one clause is not a reason")
        assertTrue("maxLines = 1" !in rail, "a one-line reason is a reason that was not given")
    }
}
