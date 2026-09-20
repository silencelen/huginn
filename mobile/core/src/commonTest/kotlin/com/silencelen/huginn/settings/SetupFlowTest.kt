package com.silencelen.huginn.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The first-run flow, asserted without a window.
 *
 * EVERY CASE HERE IS A WAY SETUP LIES RATHER THAN BREAKS. A flow that replays
 * the installer's "yes, enrol this machine" before the daemon has answered
 * enrols nothing and reports success. A skip recorded as a failure turns a
 * deliberate "not now" into a red finish line the reader then tries to fix. A
 * retry that clears the wrong result closes the daemon gate under somebody who
 * is halfway through. And progress that does not survive a round trip through
 * the settings file starts a person over at the address they typed on Tuesday —
 * this window hides to the tray rather than quitting, so halfway-through is a
 * state that lasts days.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class SetupFlowTest {

    private fun fresh() = SetupState()

    /** Drives the two gate steps to Passed, which is what unlocks the gated pair. */
    private fun connected(): SetupState {
        var s = fresh()
        s = SetupFlow.pass(s, "appd 3.1.0")
        s = SetupFlow.next(s)
        s = SetupFlow.pass(s, "token accepted")
        return s
    }

    // ----------------------------------------------------------- transitions

    @Test
    fun `a fresh flow starts on the route, with nothing answered`() {
        val s = fresh()
        assertEquals(SetupStep.ROUTE, s.current)
        assertEquals(StepStatus.Pending, s.statusOf(SetupStep.ROUTE))
        assertFalse(SetupFlow.canAdvance(s), "nothing is answered yet")
        assertFalse(SetupFlow.canGoBack(s), "the first step has no behind")
        assertFalse(s.finished)
    }

    @Test
    fun `the steps run in the only order they can honestly be attempted`() {
        assertEquals(
            listOf(
                SetupStep.ROUTE, SetupStep.TOKEN, SetupStep.CLAUDE, SetupStep.DEVICE,
                SetupStep.LOCAL_AI, SetupStep.NOTIFY, SetupStep.AUTOSTART,
            ),
            SetupFlow.STEPS,
        )
        // The marker the house draws as "1 · 2 · 3".
        assertEquals(1, SetupFlow.number(SetupStep.ROUTE))
        assertEquals(7, SetupFlow.number(SetupStep.AUTOSTART))
        assertEquals(7, SetupFlow.total())
    }

    @Test
    fun `passing a step records what proved it and unblocks advancing`() {
        var s = SetupFlow.pass(fresh(), "appd 3.1.0 answered at 100.97.198.90")
        assertEquals(
            StepStatus.Passed("appd 3.1.0 answered at 100.97.198.90"),
            s.statusOf(SetupStep.ROUTE),
        )
        assertTrue(SetupFlow.canAdvance(s))
        s = SetupFlow.next(s)
        assertEquals(SetupStep.TOKEN, s.current)
        // And the proof stays behind it rather than being consumed by the move.
        assertTrue(s.statusOf(SetupStep.ROUTE) is StepStatus.Passed)
    }

    @Test
    fun `a failure carries the daemon's own words, verbatim`() {
        // The recon's finding: the token field validates NOTHING today and says
        // "token saved" regardless. The only honest failure line is the one the
        // far end produced, so a paraphrase here would be a regression.
        val refusal = "401 unauthorized: bearer rejected"
        val s = SetupFlow.fail(fresh(), refusal)
        assertEquals(StepStatus.Failed(refusal), s.statusOf(SetupStep.ROUTE))
        // A failure is an ANSWER: the reader may move past it rather than being
        // trapped on a step a broken daemon will never let them finish.
        assertTrue(SetupFlow.canAdvance(s))
    }

    @Test
    fun `a skipped step is Skipped, never Failed`() {
        val s = SetupFlow.skip(fresh())
        assertEquals(StepStatus.Skipped(), s.statusOf(SetupStep.ROUTE))
        assertTrue(s.statusOf(SetupStep.ROUTE) !is StepStatus.Failed, "a decline is not a failure")
        // Skip is one gesture, so it moves as well as records.
        assertEquals(SetupStep.TOKEN, s.current)
    }

    @Test
    fun `retry clears only the step being retried`() {
        var s = connected()
        s = SetupFlow.next(s) // CLAUDE
        s = SetupFlow.fail(s, "claude --version did not answer")
        assertTrue(s.statusOf(SetupStep.CLAUDE) is StepStatus.Failed)

        s = SetupFlow.retry(s)
        assertEquals(StepStatus.Pending, s.statusOf(SetupStep.CLAUDE))
        assertEquals(SetupStep.CLAUDE, s.current, "retry stays on the step")
        // ⚠ THE LOAD-BEARING HALF. Clearing more than the current step would
        // reopen the gate under somebody halfway through, and DEVICE/LOCAL_AI
        // would silently stop being offerable.
        assertTrue(s.statusOf(SetupStep.ROUTE) is StepStatus.Passed)
        assertTrue(s.statusOf(SetupStep.TOKEN) is StepStatus.Passed)
        assertTrue(SetupFlow.gateSatisfied(s))
    }

    @Test
    fun `back steps to the previous step and never off the front`() {
        var s = SetupFlow.next(SetupFlow.pass(fresh(), "ok"))
        assertEquals(SetupStep.TOKEN, s.current)
        assertTrue(SetupFlow.canGoBack(s))
        s = SetupFlow.back(s)
        assertEquals(SetupStep.ROUTE, s.current)
        assertEquals(s, SetupFlow.back(s), "back on the first step is a no-op, not a throw")
    }

    @Test
    fun `the flow finishes when there is nothing left to answer`() {
        var s = fresh()
        repeat(SetupFlow.STEPS.size) { s = SetupFlow.skip(s) }
        assertTrue(s.finished)
        assertTrue(SetupFlow.allResolved(s))
    }

    // ------------------------------------------------------------- the gate

    @Test
    fun `the gated steps need the route AND the token proven, not merely answered`() {
        var s = fresh()
        assertFalse(SetupFlow.available(s, SetupStep.DEVICE))
        assertFalse(SetupFlow.available(s, SetupStep.LOCAL_AI))
        // The ungated ones are reachable from the start: they are facts about
        // this machine, and the moment they matter most is when the daemon is
        // the thing that is broken.
        assertTrue(SetupFlow.available(s, SetupStep.CLAUDE))
        assertTrue(SetupFlow.available(s, SetupStep.NOTIFY))
        assertTrue(SetupFlow.available(s, SetupStep.AUTOSTART))

        // SKIPPED is not PASSED. A reader who skipped the token has no bearer,
        // so enrolment cannot work — and the flow must not pretend otherwise.
        s = SetupFlow.pass(fresh(), "appd 3.1.0")
        s = SetupFlow.next(s)
        s = SetupFlow.skip(s)
        assertFalse(SetupFlow.gateSatisfied(s), "a skipped token is not a working token")
        assertFalse(SetupFlow.available(s, SetupStep.DEVICE))

        assertTrue(SetupFlow.gateSatisfied(connected()))
        assertTrue(SetupFlow.available(connected(), SetupStep.DEVICE))
    }

    @Test
    fun `a gated step the gate never let through is skipped with its reason, not offered`() {
        var s = fresh()
        s = SetupFlow.skip(s) // ROUTE
        s = SetupFlow.skip(s) // TOKEN
        s = SetupFlow.skip(s) // CLAUDE
        // Next would be DEVICE, which cannot work. It is answered on the way
        // past rather than drawn as a form whose only outcome is a 404.
        assertEquals(SetupStep.NOTIFY, s.current)
        assertEquals(StepStatus.Skipped(SetupFlow.GATE_SKIP), s.statusOf(SetupStep.DEVICE))
        assertEquals(StepStatus.Skipped(SetupFlow.GATE_SKIP), s.statusOf(SetupStep.LOCAL_AI))
    }

    // -------------------------------------------------------- pre-answers

    @Test
    fun `an installer answer file is read into wanted and declined`() {
        val pre = SetupFlow.parsePreAnswers(
            """{"version":1,"source":"windows-installer","features":
               {"claudePath":true,"device":true,"localAi":false,"autostart":true}}""",
        )
        assertEquals(setOf(SetupStep.CLAUDE, SetupStep.DEVICE, SetupStep.AUTOSTART), pre.wanted)
        assertEquals(setOf(SetupStep.LOCAL_AI), pre.declined)
        // TRI-STATE. A key the installer did not write is not a "no": the `.deb`
        // asks nothing at all, and reading its silence as seven declines would
        // skip the whole flow on Linux.
        assertNull(pre[SetupStep.NOTIFY], "an absent key is silence, not a decline")
        assertEquals(true, pre[SetupStep.CLAUDE])
        assertEquals(false, pre[SetupStep.LOCAL_AI])
    }

    @Test
    fun `junk, a newer schema and no file at all all mean no pre-answers`() {
        for (raw in listOf(null, "", "   ", "not json", "{", "[]", " ")) {
            assertTrue(
                SetupFlow.parsePreAnswers(raw).isEmpty,
                "unreadable input must mean no pre-answers, not a throw: $raw",
            )
        }
        // A file with no features block, and one with only keys this build has
        // never heard of, are both silence rather than answers.
        assertTrue(SetupFlow.parsePreAnswers("""{"version":2}""").isEmpty)
        assertTrue(SetupFlow.parsePreAnswers("""{"features":{"teleport":true}}""").isEmpty)
        // …but a newer file whose extra keys sit BESIDE known ones keeps the
        // known ones. Forward compatibility is the whole reason for ignoreUnknownKeys.
        val mixed = SetupFlow.parsePreAnswers("""{"features":{"teleport":true,"autostart":true}}""")
        assertEquals(setOf(SetupStep.AUTOSTART), mixed.wanted)
    }

    @Test
    fun `a declined component is skipped on the way past, without being drawn`() {
        val s0 = SetupFlow.fromPreAnswers("""{"features":{"claudePath":false}}""")
        var s = SetupFlow.pass(s0, "appd 3.1.0")
        s = SetupFlow.next(s)
        s = SetupFlow.pass(s, "token accepted")
        s = SetupFlow.next(s)
        assertEquals(SetupStep.DEVICE, s.current, "CLAUDE was unticked, so it is not asked")
        assertEquals(StepStatus.Skipped(), s.statusOf(SetupStep.CLAUDE))
    }

    @Test
    fun `a ticked component is armed, never pre-passed`() {
        val s = SetupFlow.fromPreAnswers("""{"features":{"device":true,"autostart":true}}""")
        // The installer installs nothing, by the owner's rule — so it cannot
        // have proven anything, and a "Passed" here would be a claim about a
        // machine nobody has looked at.
        assertEquals(StepStatus.Pending, s.statusOf(SetupStep.DEVICE))
        assertEquals(StepStatus.Pending, s.statusOf(SetupStep.AUTOSTART))
    }

    @Test
    fun `a wanted gated step is not replayable until the route and token pass`() {
        // ⚠ THE ORDERING RULE. Enrolment is a POST and local serving seeds the
        // daemon's token into a service; replaying either against a daemon that
        // has not answered does nothing and reports that it worked.
        val armed = SetupFlow.fromPreAnswers(
            """{"features":{"device":true,"localAi":true,"autostart":true}}""",
        )
        assertEquals(
            setOf(SetupStep.AUTOSTART),
            SetupFlow.replayable(armed),
            "only the local step may be acted on before the daemon answers",
        )

        var s = SetupFlow.pass(armed, "appd 3.1.0")
        assertEquals(
            setOf(SetupStep.AUTOSTART),
            SetupFlow.replayable(s),
            "a route alone is not a token — a ping proves a daemon, not a bearer",
        )

        s = SetupFlow.next(s)
        s = SetupFlow.pass(s, "token accepted")
        assertEquals(
            setOf(SetupStep.DEVICE, SetupStep.LOCAL_AI, SetupStep.AUTOSTART),
            SetupFlow.replayable(s),
        )
    }

    @Test
    fun `a step already answered stops being replayable`() {
        var s = connected()
        s = SetupFlow.next(s) // CLAUDE
        s = SetupFlow.skip(s) // -> DEVICE
        val armed = s.copy(pre = PreAnswers(mapOf(SetupStep.DEVICE to true)))
        assertEquals(setOf(SetupStep.DEVICE), SetupFlow.replayable(armed))
        val done = SetupFlow.pass(armed, "Enrolled, waiting for work")
        assertTrue(SetupFlow.replayable(done).isEmpty(), "a proven step is not replayed")
    }

    // ------------------------------------------------------- serialisation

    @Test
    fun `progress survives the round trip through the settings file`() {
        var s = SetupFlow.fromPreAnswers("""{"features":{"device":true,"localAi":false}}""")
        s = SetupFlow.pass(s, "appd 3.1.0 answered at 100.97.198.90:8787")
        s = SetupFlow.next(s)
        s = SetupFlow.fail(s, "401 unauthorized: bearer rejected")
        s = SetupFlow.next(s)
        s = SetupFlow.skip(s)

        val back = assertNotNull(SetupFlow.decode(SetupFlow.encode(s)))
        assertEquals(s.current, back.current)
        assertEquals(s.finished, back.finished)
        // Every status AND its words: a detail line that survives as an empty
        // string is a resumed flow that has forgotten why a step failed.
        for (step in SetupFlow.STEPS) {
            assertEquals(s.statusOf(step), back.statusOf(step), "status for $step")
        }
        assertEquals(s.pre.answers, back.pre.answers, "the installer's answers ride along")
    }

    @Test
    fun `unreadable progress reads as a fresh flow rather than a throw`() {
        for (raw in listOf(null, "", "   ", "{", "not json", "[1,2,3]")) {
            assertNull(SetupFlow.decode(raw), "unreadable progress must mean no progress: $raw")
        }
        // A state word written by a build this one does not know loses that one
        // step, never the rest of the file.
        val partial = assertNotNull(
            SetupFlow.decode(
                """{"current":"TOKEN","steps":[{"step":"ROUTE","state":"passed","detail":"appd 3.1.0"},
                   {"step":"CLAUDE","state":"teleported","detail":"?"}]}""",
            ),
        )
        assertEquals(StepStatus.Passed("appd 3.1.0"), partial.statusOf(SetupStep.ROUTE))
        assertEquals(StepStatus.Pending, partial.statusOf(SetupStep.CLAUDE))
        // A step name from the future is dropped the same way, and a current
        // that names one lands back on the first step rather than nowhere.
        val futureCurrent = assertNotNull(SetupFlow.decode("""{"current":"TELEPORT"}"""))
        assertEquals(SetupStep.ROUTE, futureCurrent.current)
    }

    // -------------------------------------------------------------- summary

    @Test
    fun `the finish line counts what worked, what was declined and what is broken`() {
        var s = fresh()
        s = SetupFlow.pass(s, "appd 3.1.0"); s = SetupFlow.next(s)
        s = SetupFlow.pass(s, "token accepted"); s = SetupFlow.next(s)
        s = SetupFlow.pass(s, "claude 2.1.4"); s = SetupFlow.next(s)
        s = SetupFlow.skip(s) // DEVICE
        s = SetupFlow.skip(s) // LOCAL_AI
        s = SetupFlow.fail(s, "nothing appeared"); s = SetupFlow.next(s)
        s = SetupFlow.pass(s, "starts with your session")

        assertEquals("4 of 7 set up · 2 skipped · 1 could not be proven", SetupFlow.summary(s))

        // A clean run says only the good number — no zero clauses invented to
        // fill the line out.
        var clean = fresh()
        repeat(SetupFlow.STEPS.size) {
            clean = SetupFlow.next(SetupFlow.pass(clean, "ok"))
        }
        assertEquals("7 of 7 set up", SetupFlow.summary(clean))
    }
}

/**
 * ⚠ D-15. THE PROBE RAN, PRINTED ITS ANSWER, AND THE ROW SAID "NOT CHECKED YET".
 *
 * Pressing "Check what this computer can serve" on step 5 runs a real check
 * against the local-AI manager and prints its verdict — *"This machine can serve
 * (class C), 2548 MB to download."* — and every other thing on the screen carried
 * on as though nothing had happened: the rail row read "not checked yet", the
 * tally read "4 of 7", and the primary button still offered the same probe.
 *
 * The flow had nowhere to put the answer. [StepStatus.Passed] would claim this
 * machine serves models it has not downloaded, and [StepStatus.Skipped] would
 * record a decision nobody made — so the controller wrote the plan to a NOTE,
 * which is not part of the step's state, and left the step [StepStatus.Pending].
 *
 * [StepStatus.Checked] is the missing fifth state: the machine has answered and
 * the choice is still the reader's. It is deliberately NOT [resolved] — a step
 * waiting on a person has not been answered, whatever the machine now knows.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class SetupCheckedTest {

    private val plan = "This machine can serve (class C), 2548 MB to download."

    private fun fresh() = SetupState()

    private fun atLocalAi(): SetupState = SetupState(current = SetupStep.LOCAL_AI)

    @Test
    fun `a checked step is neither a pass nor a decline`() {
        val s = SetupFlow.checked(atLocalAi(), plan)
        val status = s.statusOf(SetupStep.LOCAL_AI)
        assertEquals(StepStatus.Checked(plan), status)
        assertFalse(status is StepStatus.Passed, "it would claim the machine serves")
        assertFalse(status is StepStatus.Skipped, "it would record a choice nobody made")
        assertFalse(status is StepStatus.Failed, "the check worked — it is the answer that is open")
    }

    @Test
    fun `waiting on a person is not an answer, so the step is not resolved`() {
        val s = SetupFlow.checked(atLocalAi(), plan)
        assertFalse(s.statusOf(SetupStep.LOCAL_AI).resolved, "nobody has chosen yet")
        assertFalse(SetupFlow.canAdvance(s), "the machine cannot answer this one for them")
        assertFalse(SetupFlow.allResolved(s))
        // …and the other four are unchanged in that regard.
        assertTrue(StepStatus.Passed("ok").resolved)
        assertTrue(StepStatus.Failed("no").resolved)
        assertTrue(StepStatus.Skipped().resolved)
        assertFalse(StepStatus.Pending.resolved)
    }

    /** Everything up to and including the enrolment proven, standing on step 5. */
    private fun atOpenChoice(): SetupState {
        var s = fresh()
        s = SetupFlow.pass(s, "appd 3.1.0"); s = SetupFlow.next(s)
        s = SetupFlow.pass(s, "token accepted"); s = SetupFlow.next(s)
        s = SetupFlow.pass(s, "claude 2.1.4"); s = SetupFlow.next(s)
        s = SetupFlow.pass(s, "enrolled"); s = SetupFlow.next(s)
        assertEquals(SetupStep.LOCAL_AI, s.current)
        return SetupFlow.checked(s, plan)
    }

    @Test
    fun `the step is still there to answer when the flow is walked again`() {
        // A resolved step is stepped over by `next`. A checked one must not be:
        // the reader's choice is the only thing that finishes it.
        var s = atOpenChoice()
        s = SetupFlow.back(s)
        assertEquals(SetupStep.DEVICE, s.current)
        s = SetupFlow.next(s)
        assertEquals(SetupStep.LOCAL_AI, s.current, "a checked step is not walked past")
    }

    @Test
    fun `re-checking the address does not relabel the open choice as skipped`() {
        // ⚠ THE GATE CLOSING UNDER A CHECKED STEP. `next` skips a GATED step it
        // cannot offer, with "huginn has to answer first" — right for a step
        // nobody has reached, and a lie about one whose probe has already run.
        // Reachable: pass the address, check local AI, go back and press "Try
        // again" on the address. The reader's open choice must not come back as
        // a decline they never made.
        var s = atOpenChoice()
        s = s.copy(current = SetupStep.ROUTE)
        s = SetupFlow.retry(s)
        assertFalse(SetupFlow.gateSatisfied(s), "the gate really is open again")
        s = SetupFlow.next(s)
        assertEquals(StepStatus.Checked(plan), s.statusOf(SetupStep.LOCAL_AI))
        assertEquals(SetupStep.LOCAL_AI, s.current)
    }

    @Test
    fun `the tally says a step is waiting on you`() {
        // ⚠ THE COUNT WAS THE OTHER HALF OF THE LIE. "4 of 7 set up" over a
        // machine that had just been checked reads as a flow that lost the
        // answer — and the reader has no way to tell that from a probe that
        // never ran.
        var s = fresh()
        s = SetupFlow.pass(s, "appd 3.1.0"); s = SetupFlow.next(s)
        s = SetupFlow.pass(s, "token accepted"); s = SetupFlow.next(s)
        s = SetupFlow.pass(s, "claude 2.1.4"); s = SetupFlow.next(s)
        s = SetupFlow.pass(s, "enrolled"); s = SetupFlow.next(s)
        assertEquals(SetupStep.LOCAL_AI, s.current)
        s = SetupFlow.checked(s, plan)
        assertEquals("4 of 7 set up · 1 waiting on you", SetupFlow.summary(s))
    }

    @Test
    fun `a checked step survives the round trip through the settings file`() {
        // This window hides to the tray rather than quitting, so "halfway
        // through" lasts days — and a checked step that came back as Pending
        // would ask the reader to run the probe they already ran.
        val s = SetupFlow.checked(atLocalAi(), plan)
        val back = assertNotNull(SetupFlow.decode(SetupFlow.encode(s)))
        assertEquals(StepStatus.Checked(plan), back.statusOf(SetupStep.LOCAL_AI))
        assertEquals(SetupStep.LOCAL_AI, back.current)
    }

    @Test
    fun `an older build reads it as unanswered rather than as a pass`() {
        // The forward half of `decode`'s tolerance rule. A build that predates
        // this state drops the row and asks again, which is the old behaviour —
        // what it must never do is round it up to "set up".
        val encoded = SetupFlow.encode(SetupFlow.checked(atLocalAi(), plan))
        assertTrue(encoded.contains("\"checked\""), "the stored word: $encoded")
        assertFalse(encoded.contains("\"passed\""), "it must not be written as a pass: $encoded")
    }

    @Test
    fun `a retry takes it back to unattempted`() {
        val s = SetupFlow.retry(SetupFlow.checked(atLocalAi(), plan))
        assertEquals(StepStatus.Pending, s.statusOf(SetupStep.LOCAL_AI))
    }
}
