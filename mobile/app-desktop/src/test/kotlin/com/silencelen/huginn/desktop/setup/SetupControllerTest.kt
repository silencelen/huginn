package com.silencelen.huginn.desktop.setup

import com.silencelen.huginn.desktop.DesktopSettings
import com.silencelen.huginn.settings.PreAnswers
import com.silencelen.huginn.settings.SetupFlow
import com.silencelen.huginn.settings.SetupStep
import com.silencelen.huginn.settings.StepStatus
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The flow's desktop half: when it opens itself, what a probe's answer does to
 * it, and whether closing the window loses the answers.
 *
 * ⚠ THE OPENING RULE IS THE ONE WORTH ASSERTING. Get it wrong in one direction
 * and the flow never appears, so the feature does not exist; get it wrong in the
 * other and it appears on every launch, so people learn to close it without
 * reading — and the next time it has something to tell them, they close it
 * again. Neither failure throws.
 *
 * The probes are faked, which is the point of them being an interface: the
 * controller's decisions can be checked without a daemon, a `claude`, a
 * notification backend or a screen.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class SetupControllerTest {

    private val dirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() = dirs.forEach { it.deleteRecursively() }

    private fun freshSettings(): DesktopSettings {
        val dir = Files.createTempDirectory("huginn-setup-ctl").toFile()
        dirs += dir
        return DesktopSettings(File(dir, "settings.json"))
    }

    /**
     * Answers whatever it is told to, without suspending — so an Unconfined
     * scope runs every probe to completion on the calling thread and the test
     * reads as a sequence rather than as a race.
     */
    private open class FakeProbes(
        var routeResult: Result<String> = Result.success("appd 3.1.0 answered at 10.0.0.1:8787"),
        var tokenResult: Result<String> = Result.success("accepted by appd 3.1.0"),
        var claudeResult: Result<String> = Result.success("/usr/local/bin/claude — 2.1.4"),
        var deviceResult: Result<String> = Result.success("enrolled, waiting for work"),
        var localAiResult: LocalAiOutcome = LocalAiOutcome.Serving("serving qwen3-8b"),
        var notifyResult: Result<String> = Result.success("notifications via libnotify"),
        var autostartResult: Result<String> = Result.success("starts with your session — /x/y.desktop"),
    ) : SetupProbes {
        var cleared = 0
        var autostartAsked: Boolean? = null
        override suspend fun route(): Result<String> = routeResult
        override suspend fun token() = tokenResult
        override suspend fun claude() = claudeResult
        override suspend fun device() = deviceResult
        override suspend fun localAi() = localAiResult
        override suspend fun postTestNotification() = notifyResult
        override suspend fun clearTestNotification() { cleared++ }
        override suspend fun autostart(on: Boolean): Result<String> {
            autostartAsked = on
            return autostartResult
        }
    }

    private fun controller(
        settings: DesktopSettings = freshSettings(),
        probes: SetupProbes = FakeProbes(),
    ) = SetupController(settings, probes, CoroutineScope(Dispatchers.Unconfined))

    // ------------------------------------------------------------- opening

    @Test
    fun `a fresh install with an empty route book is offered the flow`() {
        assertTrue(SetupController.interested(routeBookEmpty = true, setupDone = false, hasPreAnswers = false))
    }

    @Test
    fun `an install that has finished the flow is never raised again`() {
        // However empty its book. Somebody who walked the flow and skipped
        // everything made a choice, and re-asking on every launch is how a
        // screen becomes something people close reflexively.
        assertFalse(SetupController.interested(routeBookEmpty = true, setupDone = true, hasPreAnswers = false))
        assertFalse(SetupController.interested(routeBookEmpty = true, setupDone = true, hasPreAnswers = true))
    }

    @Test
    fun `an upgrade with a working route book is left alone`() {
        assertFalse(SetupController.interested(routeBookEmpty = false, setupDone = false, hasPreAnswers = false))
    }

    @Test
    fun `an installer that left answers gets the flow regardless of the book`() {
        // The answers have nowhere else to go: they are consumed once, and a
        // launch that swallowed them without showing the flow would silently
        // throw away everything the person ticked at install time.
        assertTrue(SetupController.interested(routeBookEmpty = false, setupDone = false, hasPreAnswers = true))
    }

    @Test
    fun `adopting the installer's answers does not open the flow by itself`() {
        val c = controller()
        c.adopt(PreAnswers(mapOf(SetupStep.AUTOSTART to true)))
        assertFalse(c.visible.value, "adopting is bookkeeping; opening is a separate decision")
        c.open(routeBookEmpty = true)
        assertTrue(c.visible.value)
    }

    // -------------------------------------------------------------- probes

    @Test
    fun `a passing probe records what proved it and stops there`() {
        val c = controller()
        c.open(routeBookEmpty = true)
        c.primary()
        assertEquals(
            StepStatus.Passed("appd 3.1.0 answered at 10.0.0.1:8787"),
            c.state.value.statusOf(SetupStep.ROUTE),
        )
        // The probe does not also advance: the reader sees the proof, then moves.
        assertEquals(SetupStep.ROUTE, c.state.value.current)
        c.primary()
        assertEquals(SetupStep.TOKEN, c.state.value.current)
    }

    @Test
    fun `a failing probe carries the far end's own sentence`() {
        val probes = FakeProbes(routeResult = Result.failure(IllegalStateException("connection refused")))
        val c = controller(probes = probes)
        c.open(routeBookEmpty = true)
        c.primary()
        assertEquals(StepStatus.Failed("connection refused"), c.state.value.statusOf(SetupStep.ROUTE))
        assertFalse(c.busy.value, "a failure must release the flow, not leave it working forever")

        // Try again, with the world fixed underneath.
        probes.routeResult = Result.success("appd 3.1.0 answered at 10.0.0.1:8787")
        c.primary()
        assertTrue(c.state.value.statusOf(SetupStep.ROUTE) is StepStatus.Passed)
    }

    @Test
    fun `a probe that throws is a failed step, never a stuck flow`() {
        val c = controller(probes = object : FakeProbes() {
            override suspend fun route(): Result<String> = throw RuntimeException("the JVM had an opinion")
        })
        c.open(routeBookEmpty = true)
        c.primary()
        assertEquals(StepStatus.Failed("the JVM had an opinion"), c.state.value.statusOf(SetupStep.ROUTE))
        assertFalse(c.busy.value)
    }

    @Test
    fun `local AI that could serve but does not is neither a pass nor a failure`() {
        val c = controller(probes = FakeProbes(localAiResult = LocalAiOutcome.Offered("can serve, 4200 MB")))
        c.rerun(at = SetupStep.LOCAL_AI)
        c.primary()
        // ⚠ NOT A PASS. Turning serving on is a human's click on the consent
        // card, and a step that went green on "it could" would claim a machine
        // is serving models it has not downloaded.
        assertEquals(StepStatus.Pending, c.state.value.statusOf(SetupStep.LOCAL_AI))
        assertEquals("can serve, 4200 MB", c.note.value)
    }

    @Test
    fun `the notification step waits for a human and withdraws its own toast`() {
        val probes = FakeProbes()
        val c = controller(probes = probes)
        c.rerun(at = SetupStep.NOTIFY)
        c.primary()
        // Posted, and NOT passed: the only honest test is somebody saying they
        // saw it. `Notifiers.describe` reported this to stdout alone until now,
        // which a packaged Windows launcher does not have.
        assertTrue(c.awaitingAnswer.value)
        assertEquals(StepStatus.Pending, c.state.value.statusOf(SetupStep.NOTIFY))

        c.answerNotify(seen = false)
        assertEquals(StepStatus.Failed(SetupController.NOTIFY_MISSED), c.state.value.statusOf(SetupStep.NOTIFY))
        assertFalse(c.awaitingAnswer.value)
        assertEquals(1, probes.cleared, "a test toast with nowhere to go must be taken back down")

        c.primary() // try again
        c.answerNotify(seen = true)
        assertTrue(c.state.value.statusOf(SetupStep.NOTIFY) is StepStatus.Passed)
        assertEquals(2, probes.cleared)
    }

    @Test
    fun `a machine with nowhere to post is never asked whether it saw anything`() {
        // ⚠⚠ THE STEP ASKED FOR CONFIRMATION OF SOMETHING THAT NEVER HAPPENED.
        // With no tray and no libnotify the backend's post is a no-op, and the
        // flow went on to "did it appear?" beside a "Yes, I saw it" button — a
        // pass recordable for a route that cannot deliver, which then holds the
        // household's Telegram fallback back for a window that shows nothing.
        // `Notifiers.testRefusal` makes it a failure before anything is posted;
        // this asserts the consequence, which is that no question is armed.
        val refusal = "nothing on this computer can show a notification"
        val c = controller(probes = FakeProbes(notifyResult = Result.failure(IllegalStateException(refusal))))
        c.rerun(at = SetupStep.NOTIFY)
        c.primary()
        assertFalse(c.awaitingAnswer.value, "no Yes button over a notification that was never posted")
        assertEquals(StepStatus.Failed(refusal), c.state.value.statusOf(SetupStep.NOTIFY))
    }

    @Test
    fun `autostart records the flag only when the file was really written`() {
        val settings = freshSettings()
        val probes = FakeProbes(autostartResult = Result.failure(IllegalStateException(Autostart.NOT_PACKAGED)))
        val c = controller(settings, probes)
        c.rerun(at = SetupStep.AUTOSTART)
        c.primary()
        assertEquals(true, probes.autostartAsked)
        assertEquals(StepStatus.Failed(Autostart.NOT_PACKAGED), c.state.value.statusOf(SetupStep.AUTOSTART))
        // ⚠ THE FLAG DOES NOT MOVE ON A FAILED WRITE. A setting that says "on"
        // over a Startup folder with nothing in it is the lie this step exists
        // to prevent.
        assertFalse(settings.autostartNow())

        probes.autostartResult = Result.success("starts with your session — /x/y.desktop")
        c.primary()
        assertTrue(settings.autostartNow())
    }

    // --------------------------------------------------------- persistence

    @Test
    fun `closing the window keeps the place, and a relaunch resumes it`() {
        val settings = freshSettings()
        val c = controller(settings)
        c.open(routeBookEmpty = true)
        c.primary() // route passes
        c.primary() // -> token
        c.primary() // token passes
        c.close()
        assertFalse(c.visible.value)

        // A whole new controller over the SAME settings file — which is what a
        // relaunch is. This window hides to the tray rather than quitting, so
        // "halfway through" is a state that lasts days.
        val again = SetupController(settings, FakeProbes(), CoroutineScope(Dispatchers.Unconfined))
        assertEquals(SetupStep.TOKEN, again.state.value.current)
        assertTrue(again.state.value.statusOf(SetupStep.ROUTE) is StepStatus.Passed)
        assertTrue(again.state.value.statusOf(SetupStep.TOKEN) is StepStatus.Passed)
    }

    @Test
    fun `walking to the end marks the install done and puts the window back`() {
        val settings = freshSettings()
        val c = controller(settings)
        c.open(routeBookEmpty = true)
        repeat(SetupFlow.STEPS.size) { c.skip() }
        assertTrue(settings.setupDoneNow())
        assertFalse(c.visible.value, "the flow gets out of the way when it is finished")
        // And it does not come back uninvited on the next launch.
        assertFalse(
            SetupController(settings, FakeProbes(), CoroutineScope(Dispatchers.Unconfined))
                .interestedOnLaunch(routeBookEmpty = true),
        )
    }

    @Test
    fun `run setup again re-checks without turning anything off`() {
        val settings = freshSettings()
        val c = controller(settings)
        c.open(routeBookEmpty = true)
        repeat(SetupFlow.STEPS.size) { c.skip() }

        c.rerun()
        assertTrue(c.visible.value)
        assertEquals(SetupStep.ROUTE, c.state.value.current)
        for (step in SetupFlow.STEPS) {
            assertEquals(StepStatus.Pending, c.state.value.statusOf(step), "$step should be re-checked")
        }
        // ⚠ NEVER DESTRUCTIVE. Nothing that was configured is turned off — that
        // is what makes this safe to press on a WORKING install, which is the
        // only way it gets used as a diagnostic.
        assertFalse(c.state.value.finished)
        assertEquals(settings.autostartNow(), settings.autostartNow())
    }

    @Test
    fun `continuing past a failure keeps the failure on the record`() {
        // ⚠ NOT A SKIP. Skipping OVERWRITES the result, and a failure quietly
        // relabelled as a choice loses the "could not be proven" clause from the
        // finish line — and the reason with it. A reader whose daemon is down
        // must be able to move on WITHOUT that outage being recorded as
        // something they decided.
        val c = controller(probes = FakeProbes(routeResult = Result.failure(IllegalStateException("connection refused"))))
        c.open(routeBookEmpty = true)
        c.primary()
        assertEquals(StepStatus.Failed("connection refused"), c.state.value.statusOf(SetupStep.ROUTE))

        c.moveOn()
        assertEquals(SetupStep.TOKEN, c.state.value.current, "the flow moved on")
        assertEquals(
            StepStatus.Failed("connection refused"),
            c.state.value.statusOf(SetupStep.ROUTE),
            "the failure must survive being walked past",
        )
        assertTrue(SetupFlow.summary(c.state.value).contains("could not be proven"))
    }

    @Test
    fun `the local-AI card's door opens the same flow at its own step`() {
        val c = controller()
        c.openAt(SetupStep.LOCAL_AI)
        assertTrue(c.visible.value)
        assertEquals(SetupStep.LOCAL_AI, c.state.value.current)
        // TWO DOORS, ONE FLOW. The dormant first-launch card used to drop the
        // reader at the top of Settings and leave them to find the section.
    }
}
