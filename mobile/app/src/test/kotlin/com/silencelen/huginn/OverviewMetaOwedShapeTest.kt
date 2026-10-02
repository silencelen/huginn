package com.silencelen.huginn

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's Overview loop must keep asking for the meta until it has arrived
 * (2026-10-02).
 *
 * A SOURCE-SHAPE test, because the view model cannot be built in a JVM unit test
 * (see HuginnViewModelTest's header). The behaviour itself is proven against the
 * desktop's twin loop in app-desktop OverviewMetaArrivalTest, and the generation
 * race underneath both in core SessionMetaSaverTest.
 *
 * Observed: a cursored graph poll of an idle session answers "unchanged" with no
 * meta, so when the editors were opened and the header did not deliver, they
 * stayed blank until the transcript grew and a keystroke saved over the real
 * goals. The loop now drops the cursor while the meta is owed.
 */
class OverviewMetaOwedShapeTest {

    private fun mobileRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun startOverviewPolling(): String {
        val text = File(mobileRoot(), "app/src/main/kotlin/com/silencelen/huginn/ui/HuginnViewModel.kt").readText()
        val start = text.indexOf("fun startOverviewPolling(")
        assertTrue("startOverviewPolling not found", start >= 0)
        val end = text.indexOf("fun overviewTabShown(", start)
        assertTrue("overviewTabShown not found after startOverviewPolling", end > start)
        return text.substring(start, end)
    }

    @Test
    fun `opening the editors marks the meta as owed`() {
        val body = startOverviewPolling()
        val open = body.indexOf("metaSaver.open(name,")
        assertTrue("the editors are no longer opened here", open >= 0)
        assertTrue("open() must mark the meta owed", "metaOwed = true" in body.substring(open, open + 200))
    }

    @Test
    fun `the graph poll drops its cursor while the meta is owed`() {
        val body = startOverviewPolling()
        assertTrue(
            "the graph poll must go uncursored until the meta has arrived",
            Regex("""client\.sessionGraph\(name,\s*if \(metaOwed\) null else _sessionGraph\.value\?\.cursor\)""")
                .containsMatchIn(body),
        )
        assertTrue("both the header and a changed graph must settle it", body.split("metaOwed = false").size - 1 >= 2)
    }
}
