package com.silencelen.huginn.desktop.setup

import com.silencelen.huginn.desktop.DesktopSettings
import com.silencelen.huginn.settings.SetupStep
import com.silencelen.huginn.settings.StepStatus
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where a probe's answer lands when it takes a while.
 *
 * Enrolment waits up to 25 s for the daemon, and the token step can wait 30 s
 * for huginn core to start. The answer belongs to the step that was probed; a
 * reader who clicked about meanwhile must not find it recorded against
 * Notifications or Autostart, which then count as set up in the finish tally
 * without ever having been tried.
 *
 * The gated probes SUSPEND until the test releases them, on an Unconfined scope,
 * so "while the probe runs" is a line of the test rather than a race.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class SetupProbeVerdictTest {

    private val dirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() = dirs.forEach { it.deleteRecursively() }

    private fun freshSettings(): DesktopSettings {
        val dir = Files.createTempDirectory("huginn-setup-verdict").toFile()
        dirs += dir
        return DesktopSettings(File(dir, "settings.json"))
    }

    /** Every probe answers at once except the ones given a gate, which wait for it. */
    private class GatedProbes(
        val tokenGate: CompletableDeferred<Result<String>>? = null,
        val deviceGate: CompletableDeferred<Result<String>>? = null,
    ) : SetupProbes {
        var autostartAsked = 0
        override suspend fun route() = Result.success("appd 3.13.2 answered at 192.0.2.10:8787")
        override suspend fun token() = tokenGate?.await() ?: Result.success("accepted by appd 3.13.2")
        override suspend fun claude() = Result.success("/usr/local/bin/claude — 2.1.4")
        override suspend fun device() = deviceGate?.await() ?: Result.success("Waiting for work")
        override suspend fun localAi(): LocalAiOutcome = LocalAiOutcome.Serving("serving")
        override suspend fun postTestNotification() = Result.success("posted")
        override suspend fun clearTestNotification() = Unit
        override suspend fun autostart(on: Boolean): Result<String> {
            autostartAsked++
            return Result.success("starts with your session")
        }
    }

    private fun controller(probes: SetupProbes) =
        SetupController(freshSettings(), probes, CoroutineScope(Dispatchers.Unconfined))

    /** The one button, pressed until the reader stands on [step]: each step is checked, then left. */
    private fun walkTo(c: SetupController, step: SetupStep) {
        repeat(20) {
            if (c.state.value.current == step) return
            c.primary()
        }
        error("never reached $step: ${c.state.value}")
    }

    @Test
    fun `a step picked in the rail while enrolment runs neither moves the reader nor takes the answer`() {
        val gate = CompletableDeferred<Result<String>>()
        val c = controller(GatedProbes(deviceGate = gate))
        c.open(routeBookEmpty = true)
        walkTo(c, SetupStep.DEVICE)

        c.primary() // "Enrol this computer": waits for the daemon
        assertTrue(c.busy.value, "the enrol wait is under way")
        c.goTo(SetupStep.NOTIFY)
        assertEquals(SetupStep.DEVICE, c.state.value.current, "nothing moves while a probe runs")

        gate.complete(Result.success("Waiting for work"))
        assertEquals(StepStatus.Passed("Waiting for work"), c.state.value.statusOf(SetupStep.DEVICE))
        assertEquals(
            StepStatus.Pending,
            c.state.value.statusOf(SetupStep.NOTIFY),
            "no test notification was ever posted, so Notifications is not set up",
        )

        // Once the probe is done, the rail works again.
        c.goTo(SetupStep.NOTIFY)
        assertEquals(SetupStep.NOTIFY, c.state.value.current)
    }

    @Test
    fun `the answer lands on the step that was probed even when the flow moved under it`() {
        val gate = CompletableDeferred<Result<String>>()
        val probes = GatedProbes(tokenGate = gate)
        val c = controller(probes)
        c.open(routeBookEmpty = true)
        walkTo(c, SetupStep.TOKEN)

        c.primary() // the token, while huginn core is still starting
        c.openAt(SetupStep.AUTOSTART) // another door into the flow moves it
        gate.complete(Result.success("huginn core paired: accepted by appd 3.13.2"))

        assertEquals(
            StepStatus.Passed("huginn core paired: accepted by appd 3.13.2"),
            c.state.value.statusOf(SetupStep.TOKEN),
        )
        assertEquals(StepStatus.Pending, c.state.value.statusOf(SetupStep.AUTOSTART), "no startup entry was written")
        assertEquals(0, probes.autostartAsked)
    }

    @Test
    fun `a refusal lands on the step that was probed too`() {
        val gate = CompletableDeferred<Result<String>>()
        val c = controller(GatedProbes(tokenGate = gate))
        c.open(routeBookEmpty = true)
        walkTo(c, SetupStep.TOKEN)

        c.primary() // the token
        c.openAt(SetupStep.CLAUDE)
        gate.complete(Result.failure(IllegalStateException("401 the token was refused")))

        assertEquals(StepStatus.Failed("401 the token was refused"), c.state.value.statusOf(SetupStep.TOKEN))
        assertEquals(StepStatus.Pending, c.state.value.statusOf(SetupStep.CLAUDE))
    }
}
