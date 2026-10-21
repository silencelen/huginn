package com.silencelen.huginn

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠ THE SESSIONS LIST AND THE MODEL MENU ARE EACH READ BY ONE SHARED FETCH.
 *
 * Measured from the call sites: coming back from a session to the list ran the
 * session screen's teardown refresh and the list's poll starting up, and a cold
 * start ran the app-wide refresh and the poll — two `GET /v1/sessions` at once
 * (~0.4 s each of the daemon's single thread at p50), with the next session's
 * history queued behind both. A chat opened on a cold start asked for the model
 * menu twice, `init` and the open each finding it empty.
 *
 * Each read now goes through an `InFlight`, which `:core`'s InFlightTest holds
 * to "concurrent asks make one request". The view model cannot be built here
 * (see HuginnViewModelTest's header), so what is held is that every caller goes
 * through it: one place in the view model reads each route, inside its
 * `InFlight`.
 *
 * NOTE this module is on org.junit: `assertEquals(message, expected, actual)`.
 */
class SharedListReadsTest {

    private val vm: String by lazy {
        val f = File(mobileRoot(), "app/src/main/kotlin/com/silencelen/huginn/ui/HuginnViewModel.kt")
        assertTrue("HuginnViewModel.kt not found at ${f.absolutePath}", f.isFile)
        f.readText()
    }

    @Test
    fun `every sessions read goes through the one shared fetch`() {
        assertEquals(
            "a second place reading /v1/sessions would put a second copy on the wire",
            1,
            Regex("""client\.sessions\(""").findAll(vm).count(),
        )
        assertTrue(
            "the one read is not the shared one",
            Regex("""sessionsInFlight\.get \{ client\.sessions\(preview = true\) \}""").containsMatchIn(vm),
        )
        assertTrue("the refresh, the poll and the app-wide refresh all ask it", Regex("""fetchSessions\(\)""").findAll(vm).count() >= 3)
    }

    @Test
    fun `the model menu is read through its shared fetch`() {
        assertEquals(1, Regex("""client\.models\(""").findAll(vm).count())
        assertTrue(Regex("""modelsInFlight\.get \{ client\.models\(\) \}""").containsMatchIn(vm))
    }

    private fun mobileRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")
}
