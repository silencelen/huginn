package com.silencelen.huginn

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-02 on the Fold at font scale 2.0: a Sessions card's age label vanished
 * on one card and was 8 px wide and 252 px tall on another, and "ctx 48%" took
 * three lines. The header Row measured the name first and gave the trailing ctx
 * and age only what was left.
 *
 * ⚠ A SOURCE-SHAPE TEST, on purpose: the card lives in :app, whose unit tests
 * have no Compose renderer (no Robolectric). The layout itself was checked by
 * rendering this exact recipe in :ui's jvmTest at 1080 px / density 2.625 /
 * font scale 2.0 (age 128 x 84 px, one line); [ContextBadge]'s no-wrap is pinned
 * there by FontScaleLayoutTest. What this gates is that the recipe stays.
 */
class SessionCardHeaderShapeTest {

    private fun header(): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val src = File(root, "app/src/main/kotlin/com/silencelen/huginn/ui/SessionsScreen.kt").readText()
        val row = src.substringAfter("private fun SessionRow(").substringBefore("val title = s.title")
        assertTrue("SessionRow header not found", row.length > 500 && "relTime(s.activityAt)" in row)
        return row
    }

    @Test
    fun `the age and ctx are measured before the name and never wrap`() {
        val row = header()
        // The name and state ride a weighted group, so the trailing pair is
        // measured first; a bare Spacer(weight) only soaked up spare room.
        assertTrue("the name/state group is not weighted", "Row(Modifier.weight(1f)" in row)
        assertFalse("a bare weighted Spacer is back in the header", "Spacer(Modifier.weight(1f))" in row)
        val name = row.substringAfter("s.name,").substringBefore(")")
        assertTrue("the name must stay on one line and ellipsise", "maxLines = 1" in name && "Ellipsis" in name)
        val age = row.substringAfter("relTime(s.activityAt),").substringBefore(")")
        assertTrue("the age label must not wrap", "maxLines = 1" in age && "softWrap = false" in age)
    }

    /**
     * The phone's own breathing dot must be the shared stepped one: its copy was
     * an infinite tween that redrew the Sessions tab at display rate while a
     * session worked. The stepping is pinned by :ui's FontScaleLayoutTest.
     */
    @Test
    fun `the phone's pulsing dot is the shared stepped one`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val body = File(root, "app/src/main/kotlin/com/silencelen/huginn/ui/Common.kt").readText()
            .substringAfter("fun PulsingDot(").substringBefore("\nfun ")
        assertTrue("PulsingDot no longer delegates", "PulseDot(color, modifier)" in body)
        assertFalse("PulsingDot runs its own infinite transition again", "rememberInfiniteTransition" in body)
    }
}
