package com.silencelen.huginn

import com.silencelen.huginn.ui.BlockLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⚠⚠ A TABLE IS NOT A SENTENCE (P-29).
 *
 * The daemon labels a turn with the first LINE of what the model said, which is
 * right for prose and meaningless for the one block Claude writes that has no
 * first sentence. The review's Overview carried a block labelled `| Host | Role |`
 * — twice — and a map whose rows read as pipes is a map nobody scans.
 *
 * The rule that keeps this honest is the one it must not break: a label is
 * returned VERBATIM unless it is a table row. Prose is full of pipes (every shell
 * pipeline in every answer), and a formatter that starts rewriting sentences is
 * worse than the pipes were.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class BlockLabelTest {

    @Test
    fun `the walk's own label becomes words`() {
        assertEquals("table: Host, Role", BlockLabel.words("| Host | Role |"))
    }

    @Test
    fun `an alignment rule is a table with nothing to say`() {
        assertEquals("table", BlockLabel.words("|---|---|"))
        assertEquals("table", BlockLabel.words("| :--- | ---: | :---: |"))
        assertEquals("table", BlockLabel.words("|  |  |"), "an empty header row names no columns")
    }

    @Test
    fun `a wide header names the first few and stops`() {
        assertEquals(
            "table: a, b, c, d, …",
            BlockLabel.words("| a | b | c | d | e | f |"),
            "five column names in a block label is the pipes again, with commas",
        )
        assertEquals("table: a, b, c, d", BlockLabel.words("| a | b | c | d |"), "exactly the cap does not elide")
    }

    @Test
    fun `a blank cell in the middle is dropped, not printed as a gap`() {
        assertEquals("table: Host, Role", BlockLabel.words("| Host |  | Role |"))
    }

    @Test
    fun `an escaped pipe stays inside its cell`() {
        assertEquals("table: a|b, c", BlockLabel.words("""| a\|b | c |"""))
    }

    @Test
    fun `prose is returned verbatim, pipes and all`() {
        // The whole risk of this formatter, asserted: answers are full of shell.
        for (line in listOf(
            "Ran `ps aux | grep claude` and found two.",
            "The exit code | matters here",
            "|",
            "||",
            "",
            "Wrote the report to docs/current/as-built/infrastructure-summary.md",
        )) {
            assertEquals(line, BlockLabel.words(line), "a sentence must survive this untouched")
        }
    }

    @Test
    fun `a single-cell row is not a table`() {
        assertNull(BlockLabel.tableCells("| just this |"), "one column is a quoted line, not a grid")
        assertEquals("| just this |", BlockLabel.words("| just this |"))
    }

    @Test
    fun `the cells come back trimmed and in order`() {
        assertEquals(listOf("Host", "Role", "Notes"), BlockLabel.tableCells("|Host|Role |  Notes|"))
        assertTrue(BlockLabel.words("| Host | Role |").length < "| Host | Role |".length + 10)
    }
}
