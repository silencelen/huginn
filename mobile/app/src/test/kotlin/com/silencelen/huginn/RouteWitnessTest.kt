package com.silencelen.huginn

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE ROUTE LIST'S "LAST REACHED" HAS TO HAVE A WRITER.
 *
 * ⚠ IT DID NOT. `RouteHealth` was only ever written by `RouteResolver.resolve()`
 * — the probe sweep — so a route this app had been talking to since breakfast
 * still read "never reached" in Settings → Host until somebody pressed *Find
 * live route*. The one line on that page whose entire job is to say which path
 * is working was the one line nothing kept current.
 *
 * `RouteResolver.touch` is the rule and `RouteResolverTest` holds it; what this
 * gate holds is that the shell still CALLS it on the status poll, because the
 * failure mode is a line that is merely old rather than anything that throws.
 *
 * WHY A SOURCE GREP: instantiating `HuginnViewModel` needs an Android
 * `Application`, and this module is plain JVM unit tests — the same reason
 * `ListFabClearanceTest` next door reads source rather than pixels.
 *
 * NOTE this module is on org.junit, whose argument order is (message, expected,
 * actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class RouteWitnessTest {

    private fun mobileRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    @Test
    fun `a successful status poll records the route as reached`() {
        val f = File(mobileRoot(), "app/src/main/kotlin/com/silencelen/huginn/ui/HuginnViewModel.kt")
        assertTrue("not found at ${f.absolutePath}", f.isFile)
        val text = f.readText()
        assertTrue("read as ${text.length} chars — wrong file", text.length > 10_000)
        assertTrue(
            "the witness helper is gone — the route rows are back to a probe-only record",
            text.contains("RouteResolver.touch("),
        )
        // The success branch of the status poll, sliced narrowly: a call anywhere
        // else in the file would not keep the rows current.
        val success = text.substringAfter("runCatching { client.status() }").substringBefore(".onFailure")
        assertTrue("the status poll's success branch was not found", success.length in 1..4_000)
        assertTrue(
            "the status poll no longer records that the route worked",
            success.contains("noteRouteReached()"),
        )
    }

    @Test
    fun `the witness never forges the probe's own record`() {
        // ⚠ THE HYSTERESIS READS `lastOkAt`. Writing traffic into it would make
        // the three-failures re-probe find a dead route "fresh" — see
        // `RouteHealth.lastSeenAt`. This is the one way the fix could be undone
        // by someone tidying two fields into one.
        val f = File(mobileRoot(), "core/src/commonMain/kotlin/com/silencelen/huginn/data/AppdRoutes.kt")
        assertTrue("not found at ${f.absolutePath}", f.isFile)
        val body = f.readText().substringAfter("fun touch(").substringBefore("private fun adoptable")
        assertTrue("touch() was not found", body.length in 1..2_000)
        assertTrue("touch() writes lastSeenAt", body.contains("lastSeenAt = now"))
        assertTrue("touch() must never write lastOkAt", !body.contains("lastOkAt ="))
    }
}
