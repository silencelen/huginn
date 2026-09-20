package com.silencelen.huginn.ui

import com.silencelen.huginn.data.SessionMetaSaver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the session map is SAYING with a tint, and the one line under the notes.
 *
 * Colour is the whole vocabulary of that gutter, so getting it wrong is a map
 * that reads confidently and is not true — a stalled agent drawn in its own hue
 * says it finished, and a running one drawn as settled says the fan-out is over.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class SessionOverviewViewTest {

    @Test
    fun `the four kinds of block are four different things`() {
        assertEquals(BlockTone.SAID, blockTone("user"))
        assertEquals(BlockTone.WORK, blockTone("action"))
        assertEquals(BlockTone.SPOKE, blockTone("response"))
        assertEquals(BlockTone.BREAK, blockTone("compact"))
    }

    @Test
    fun `a kind this client has never heard of is still drawn`() {
        // Wire models are nullable-with-default so an older client keeps parsing a
        // newer daemon; the same tolerance has to reach the renderer, or a fifth
        // block kind arrives as a hole in the middle of the map.
        assertEquals(BlockTone.WORK, blockTone("something-new"))
        assertEquals(BlockTone.WORK, blockTone(""))
    }

    @Test
    fun `a running agent is the only one that gets the live tint`() {
        assertEquals(LaneTone.LIVE, laneTone("running"))
        assertEquals(LaneTone.OWN, laneTone("done"))
    }

    @Test
    fun `a failure is the error tint and nothing else is`() {
        assertEquals(LaneTone.FAILED, laneTone("failed"))
        assertTrue(laneTone("stalled") != LaneTone.FAILED, "stopping without a result is not an error")
        assertTrue(laneTone("orphan") != LaneTone.FAILED, "a join lost to a compaction is not an error")
    }

    @Test
    fun `a loose end is muted rather than coloured`() {
        // Its own hue would claim it finished. The error tint would claim it broke.
        assertEquals(LaneTone.LOOSE, laneTone("stalled"))
        assertEquals(LaneTone.LOOSE, laneTone("orphan"))
    }

    @Test
    fun `an unknown status settles rather than alarming`() {
        assertEquals(LaneTone.OWN, laneTone("something-new"))
    }

    @Test
    fun `the words for a status never overstate it`() {
        assertEquals("working", statusWords("running"))
        assertEquals("settled", statusWords("done"))
        assertEquals("failed", statusWords("failed"))
        assertEquals("stopped without a result", statusWords("stalled"))
        assertEquals("ran, unplaced", statusWords("orphan"))
    }

    @Test
    fun `the save line says nothing about text nobody has touched`() {
        // "Saved" over an untouched page is a claim about work that never happened
        // — the same rule the scratchpad editor's line follows.
        assertEquals("", metaSaveWords(SessionMetaSaver.State.IDLE))
        assertEquals("Editing…", metaSaveWords(SessionMetaSaver.State.PENDING))
        assertEquals("Saving…", metaSaveWords(SessionMetaSaver.State.SAVING))
        assertEquals("Saved", metaSaveWords(SessionMetaSaver.State.SAVED))
        assertEquals("Not saved", metaSaveWords(SessionMetaSaver.State.FAILED))
    }

    // ------------------------------------------------- P-28 / P-29 the card

    private fun source(): String {
        val f = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, "settings.gradle.kts").isFile }
            ?.let { java.io.File(it, "ui/src/commonMain/kotlin/com/silencelen/huginn/ui/SessionOverviewView.kt") }
        assertTrue(f != null && f.isFile, "SessionOverviewView.kt not found from ${java.io.File("").absolutePath}")
        val text = f!!.readText()
        // A grep that matches nothing exits 0. Assert the floor before the finding.
        assertTrue(text.length > 10_000, "SessionOverviewView.kt read as ${text.length} chars — wrong file")
        return text
    }

    /**
     * ⚠⚠ A MINUTE OF SESSION IS NOT A PACE (P-28). The card read "At this pace,
     * about 186.5M tokens more" off a 54-second sample. The RULE lives in
     * [OverviewFormat.pace] and is tested there; what this gates is that the card
     * actually hands it a sample, and that the "an estimate" hedge is drawn under
     * a projection rather than under the measuring line.
     */
    @Test
    fun `the pace card hands the sample in and hedges only a projection`() {
        val body = source().substringAfter("internal fun ProjectionsCard(").substringBefore("// ------")
        assertTrue(body.length > 500, "ProjectionsCard read as ${body.length} chars — wrong slice")
        assertTrue("OverviewFormat.pace(rate, plan, nowMs, sampleMs)" in body, "the card extrapolates off no sample")
        val hedge = body.indexOf("an estimate, from the current rate")
        val guard = body.indexOf("if (pace.projected)")
        assertTrue(hedge > 0 && guard > 0, "the gate lost its subject: hedge=$hedge guard=$guard")
        assertTrue(guard < hedge, "the hedge is drawn under the measuring line, where there is nothing to hedge")
        assertTrue("totals.wallMs" in source(), "the sample must be the session's own wall time")
    }

    /**
     * ⚠ A TABLE IS NOT A SENTENCE (P-29). A turn whose reply opens with a
     * markdown table was labelled `| Host | Role |` — twice on the review's
     * screen. The rule is [BlockLabel]; this gates that the map and the sheet it
     * opens both spell the label through it, because two spellings of one label
     * is the pipes coming back on the second screen.
     */
    @Test
    fun `the map and its sheet draw the same, de-piped label`() {
        val text = source()
        assertEquals(
            0,
            Regex("""\bnode\.label\.ifEmpty""").findAll(text).count(),
            "a raw node label is drawn somewhere again",
        )
        assertEquals(
            2,
            Regex("""BlockLabel\.words\(node\.label\)""").findAll(text).count(),
            "the row and the detail sheet each spell the label once, through BlockLabel",
        )
    }
}
