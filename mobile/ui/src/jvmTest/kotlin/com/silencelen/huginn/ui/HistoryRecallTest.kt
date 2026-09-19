package com.silencelen.huginn.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⚠⚠ A [TextRange] IS DIRECTED, AND READING IT RAW IS A BUG FAMILY, NOT A BUG.
 *
 * `start` is the anchor and `end` is the focus, so Shift+Left, Shift+Home,
 * Shift+Up and any right-to-left drag all produce `start > end` — and Compose's
 * legacy `TextFieldValue` path hands that to `onValueChange` and
 * `onPreviewKeyEvent` unnormalised. The first member of the family shipped:
 * `newlineIn`'s Shift+Enter splice did `substring(0, start) + "\n" + substring(end)`
 * on a reversed range, which OVERLAPS instead of replacing — "hello world" with
 * "world" selected backwards became "hello world\nworld", Shift+Home from the end
 * doubled the whole draft, and the result went straight through `onDraft` into
 * the drafts book. That one was fixed with `min`/`max`; this file's `canEnter`
 * gate was the other raw reader and is fixed the same way.
 *
 * The two halves below are deliberate. The behavioural half pins what a reversed
 * selection must DO; the source half pins that nobody reads the raw pair again,
 * because `canEnter`'s own `!=` test happens to survive a reversed range and the
 * next piece of caret arithmetic written beside it will not.
 */
class HistoryRecallTest {

    private val history = listOf("first", "second", "newest")

    private class Composer(text: String, selection: TextRange) {
        var field = TextFieldValue(text, selection)
        var draft: String? = null
        val recall = mutableStateOf<HistoryWalk.Cursor?>(null)

        fun press(key: Key, suppressed: Boolean = false): Boolean = handleHistoryKey(
            key = key,
            field = field,
            recall = recall,
            history = listOf("first", "second", "newest"),
            suppressed = suppressed,
            setField = { field = it },
            onDraft = { draft = it },
        )
    }

    // ------------------------------------------------------- what Up must do

    /**
     * ⚠ THE REVERSED CASE. A right-to-left drag over the composer leaves
     * `start > end`; Up there is the platform's "collapse the selection and move
     * the caret", never "replace everything I am holding with a sent message".
     */
    @Test
    fun `Up over a backwards selection does not walk into history`() {
        val c = Composer("draft I am still writing", TextRange(18, 6))
        assertTrue(c.field.selection.reversed, "the fixture is the backwards drag, not a forward one")
        assertFalse(c.press(Key.DirectionUp), "the key belongs to the caret, not to recall")
        assertNull(c.recall.value)
        assertEquals("draft I am still writing", c.field.text, "and nothing was replaced")
        assertNull(c.draft, "nothing was written to the drafts book either")
    }

    /** The same holds the ordinary way round, which is the rule this states. */
    @Test
    fun `Up over a forwards selection does not walk into history`() {
        val c = Composer("draft I am still writing", TextRange(6, 18))
        assertFalse(c.press(Key.DirectionUp))
        assertNull(c.recall.value)
        assertEquals("draft I am still writing", c.field.text)
    }

    /** A collapsed caret on the first line is what recall is actually for. */
    @Test
    fun `Up from a collapsed caret on the first line recalls the newest entry`() {
        val c = Composer("", TextRange(0))
        assertTrue(c.press(Key.DirectionUp))
        assertEquals("newest", c.field.text)
        assertEquals("newest", c.draft)
        assertEquals(TextRange(6), c.field.selection, "the caret parks at the end of what was recalled")
    }

    /** Past the first line, Up is the caret's — a multiline draft must stay navigable. */
    @Test
    fun `Up below the first line moves the caret rather than the history`() {
        val c = Composer("line one\nline two", TextRange(13))
        assertFalse(c.press(Key.DirectionUp))
        assertNull(c.recall.value)
    }

    /** The Screen tab's live keyboard owns every keystroke; recall may not eat one. */
    @Test
    fun `a suppressed composer never consumes the arrow`() {
        val c = Composer("", TextRange(0))
        assertFalse(c.press(Key.DirectionUp, suppressed = true))
        assertNull(c.recall.value)
    }

    // ----------------------------------------------- and nobody reads it raw

    /**
     * THE RULE, stated against the source tree so it cannot rot: no shipped
     * Kotlin reads `.selection.start` or `.selection.end`.
     *
     * ⚠ WHY A SOURCE SCAN. The two call sites this family has produced are a
     * `substring` splice and a `!=` test, and only the first one BROKE — a
     * reversed range is still unequal, so `canEnter` gave the right answer for
     * the wrong reason and no behavioural test could have caught it. What is
     * actually wrong is reading a directed pair as if it were ordered, and the
     * source text is the only place that is visible. Same instrument as
     * `OverlaysDisableSelectionTest`, for the same kind of rule.
     *
     * `min`/`max` are the ordered accessors and are what every call site uses.
     * A genuine need for the anchor and the focus separately would be a
     * different question with a different name, and should say so loudly enough
     * to be worth editing this test for.
     */
    @Test
    fun `no shipped source reads the raw directed selection`() {
        val offenders = sources()
            .map { it to stripComments(it.readText()) }
            .filter { (_, text) -> RAW.containsMatchIn(text) }
            .map { (f, _) -> f.path }
        assertTrue(
            offenders.isEmpty(),
            "a TextRange is directed — use selection.min/max, never start/end: $offenders",
        )
    }

    /**
     * ⚠ AND THE SCAN MUST BE SCANNING SOMETHING. A pattern that stops matching
     * (a rename, a moved module, a wrong working directory) would otherwise make
     * this pass by reading nothing at all — the failure mode every source gate
     * here guards against with a floor.
     */
    @Test
    fun `the scan actually reaches the client sources`() {
        val files = sources()
        assertTrue(files.size > 100, "only ${files.size} sources found — the scan is looking in the wrong place")
        assertTrue(
            files.any { it.name == "HistoryRecall.kt" },
            "the file this gate was written for is not among them",
        )
        // And the pattern still recognises the shape it is looking for.
        assertTrue(RAW.containsMatchIn("val lo = field.selection.start"))
        assertTrue(RAW.containsMatchIn("field.selection.end"))
        assertFalse(RAW.containsMatchIn("val lo = field.selection.min"))
    }

    private companion object {
        val RAW = Regex("""\.selection\s*\.\s*(start|end)\b""")

        /** Shipped Kotlin in the four modules; test sources excluded. */
        fun sources(): List<File> {
            val root = generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").isFile }
                ?: error("cannot find the gradle root from ${File("").absolutePath}")
            return listOf("core", "ui", "app", "app-desktop")
                .map { File(root, "$it/src") }
                .filter { it.isDirectory }
                .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
                .filter { f -> f.path.split(File.separatorChar).none { it == "test" || it.endsWith("Test") } }
        }

        /** Prose about a rule is not a use of it. */
        fun stripComments(src: String): String = src
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")
    }
}
