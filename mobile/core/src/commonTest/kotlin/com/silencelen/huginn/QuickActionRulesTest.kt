package com.silencelen.huginn

import com.silencelen.huginn.data.QuickActions
import com.silencelen.huginn.ui.QuickActionRules
import com.silencelen.huginn.ui.TableMarks
import com.silencelen.huginn.ui.SelectionAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The quote frame, asserted AS LITERALS.
 *
 * The frame is a wire contract in all but name: `lib/typing.js` delivers the
 * other half of it into a pane, and a session send is byte-compared against what
 * was typed. So this suite spells out the exact characters rather than rebuilding
 * them from the same helper the code under test uses — a test that says
 * `"> " + line` passes just as happily against a broken prefix. Same precedent as
 * `ScratchpadRulesTest`: the rules that cross a language boundary are written out.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message) — the reverse
 * of JUnit's, and already on this project's trap list.
 */
class QuickActionRulesTest {

    private val hostActions = QuickActions(
        rev = 3,
        explain = "Explain this, briefly:\n\n{selection}",
        execute = "Run this and show me the output:\n\n{selection}",
        askInNewChat = "{selection}\n\nWhat is going on here?",
        quote = "",
    )

    // -------------------------------------------------------------- the frame

    @Test
    fun `the worked example — a blank line between two lines becomes a bare marker`() {
        // Verbatim from the design contract:
        //   alpha            > alpha
        //                 →  >
        //   beta             > beta
        assertEquals("> alpha\n>\n> beta", QuickActionRules.quoteBlock("alpha\n\nbeta"))
    }

    @Test
    fun `a bare marker carries no trailing space`() {
        // "> " with nothing after it is invisible in a diff and is what makes a
        // quote block noisy when it is pasted anywhere else. The character after
        // the marker is a newline or nothing at all.
        val framed = QuickActionRules.quoteBlock("alpha\n\nbeta")
        assertFalse(framed.lines().any { it.endsWith(" ") }, framed)
        assertEquals(">", framed.lines()[1])
    }

    @Test
    fun `a whitespace-only line counts as blank`() {
        assertEquals("> alpha\n>\n> beta", QuickActionRules.quoteBlock("alpha\n   \nbeta"))
    }

    @Test
    fun `carriage returns are normalised away before the frame`() {
        // A \r that survives into a session send corrupts the transcript while the
        // pane still renders correctly — the paste-buffer trap, from the other end.
        assertEquals("> alpha\n> beta", QuickActionRules.quoteBlock("alpha\r\nbeta"))
        assertEquals("> alpha\n> beta", QuickActionRules.quoteBlock("alpha\rbeta"))
        assertFalse('\r' in QuickActionRules.quoteBlock("a\r\nb\rc"))
    }

    @Test
    fun `trailing blank lines are dropped, leading ones are not`() {
        // Trailing blank lines are an artefact of where the drag stopped. A
        // LEADING one is inside what was selected and the frame keeps it.
        assertEquals("> alpha", QuickActionRules.quoteBlock("alpha\n\n\n"))
        assertEquals(">\n> alpha", QuickActionRules.quoteBlock("\nalpha"))
        assertEquals("", QuickActionRules.quoteBlock("\n\n"))
    }

    @Test
    fun `indentation inside a line survives the frame`() {
        // Code is the most likely thing to be selected, and a frame that trimmed
        // would quietly destroy the only part of it that matters.
        assertEquals(">     indented", QuickActionRules.quoteBlock("    indented"))
    }

    @Test
    fun `an over-long selection is cut and says so`() {
        val long = "x".repeat(QuickActionRules.SELECTION_MAX + 500)
        val framed = QuickActionRules.quoteBlock(long)
        assertTrue(framed.endsWith(QuickActionRules.TRUNCATED), framed.takeLast(40))
        assertEquals(
            QuickActionRules.SELECTION_MAX + QuickActionRules.TRUNCATED.length,
            framed.length,
        )
        assertEquals("\n> …(truncated)", QuickActionRules.TRUNCATED)
    }

    @Test
    fun `a selection that fits is not marked truncated`() {
        val fits = "y".repeat(QuickActionRules.SELECTION_MAX - 2)
        val framed = QuickActionRules.quoteBlock(fits)
        assertEquals(QuickActionRules.SELECTION_MAX, framed.length)
        assertFalse(framed.contains("truncated"), "a selection that fit must not be accused of not fitting")
    }

    @Test
    fun `a quote lead-in sits above the block, and an empty one adds nothing`() {
        assertEquals("> alpha", QuickActionRules.quote("", "alpha"))
        assertEquals("As you said:\n\n> alpha", QuickActionRules.quote("As you said:", "alpha"))
    }

    // ------------------------------------------------------------- compose

    @Test
    fun `compose substitutes the placeholder literally`() {
        // THE TRAP: a regex-based replace reads `$1` and `\\1` in the REPLACEMENT
        // as group references, so a selection containing either comes out mangled
        // or throws. Selected text is arbitrary bytes — sed one-liners and Windows
        // paths are exactly the sort of thing a person selects and asks about.
        val selection = """sed -e 's/(a)(b)/$1-$2/' C:\temp\x"""
        assertEquals(
            "Explain this, briefly:\n\n$selection",
            QuickActionRules.compose(hostActions.explain, selection),
        )
    }

    @Test
    fun `compose puts the selection where the host put the placeholder`() {
        assertEquals(
            "boom\n\nWhat is going on here?",
            QuickActionRules.compose(hostActions.askInNewChat, "boom"),
        )
    }

    @Test
    fun `compose never loses the selection to a broken template`() {
        // A daemon refuses a template with no placeholder, so this is the shape of
        // a daemon that is already wrong. Dropping the selection on the floor is
        // the one outcome that must not happen: the person selected it.
        assertEquals("alpha", QuickActionRules.compose("", "alpha"))
        assertEquals("Look:\n\nalpha", QuickActionRules.compose("Look:", "alpha"))
    }

    @Test
    fun `compose normalises carriage returns out of the selection too`() {
        assertEquals("Look:\n\na\nb", QuickActionRules.compose("Look:\n\n{selection}", "a\r\nb"))
    }

    // ----------------------------------------------------------- the draft

    @Test
    fun `appendToDraft appends and never clobbers`() {
        assertEquals(
            "half a thought\n\n> alpha",
            QuickActionRules.appendToDraft("half a thought", "> alpha"),
        )
        assertEquals("> alpha", QuickActionRules.appendToDraft("", "> alpha"))
        assertEquals("> alpha", QuickActionRules.appendToDraft("   ", "> alpha"))
    }

    @Test
    fun `appendToDraft leaves a draft alone when there is nothing to add`() {
        assertEquals("half a thought", QuickActionRules.appendToDraft("half a thought", ""))
    }

    // ----------------------------------------------------------- what is offered

    @Test
    fun `a daemon with no templates offers the one verb this client owns`() {
        assertEquals(listOf(SelectionAction.QUOTE), QuickActionRules.offered("alpha", null))
        assertEquals(
            listOf(
                SelectionAction.EXPLAIN,
                SelectionAction.EXECUTE,
                SelectionAction.QUOTE,
                SelectionAction.ASK_IN_NEW_CHAT,
            ),
            QuickActionRules.offered("alpha", hostActions),
        )
    }

    @Test
    fun `nothing is offered for a blank selection or one past the cap`() {
        assertEquals(emptyList(), QuickActionRules.offered("", hostActions))
        assertEquals(emptyList(), QuickActionRules.offered("   \n  ", hostActions))
        assertEquals(
            emptyList(),
            QuickActionRules.offered("z".repeat(QuickActionRules.SELECTION_MAX + 1), hostActions),
        )
    }

    @Test
    fun `textFor routes each verb at the template the host wrote for it`() {
        assertEquals(
            "Run this and show me the output:\n\nls -la",
            QuickActionRules.textFor(SelectionAction.EXECUTE, hostActions, "ls -la"),
        )
        assertEquals(
            "> ls -la",
            QuickActionRules.textFor(SelectionAction.QUOTE, hostActions, "ls -la"),
        )
        // Quote against a daemon that serves nothing is still the frame — that is
        // the whole reason it is the verb offered on its own.
        assertEquals("> ls -la", QuickActionRules.textFor(SelectionAction.QUOTE, null, "ls -la"))
    }

    // ------------------------------------------- a selection across a table (D-5)

    private val CELL = TableMarks.CELL
    private val ROW = TableMarks.ROW

    /**
     * ⚠⚠ D-5. `TableGrid` draws each cell as its own `Text` inside the
     * transcript's `SelectionContainer` — which is what makes it a grid — so a
     * drag across a table came back as every cell run together:
     *
     *     …markdown table of three rows…PlanetMoonsEarthThe MoonMarsPhobos, Deimos
     *
     * No column break, no row break, and the prose running straight into the
     * table. Selecting across a table is the ordinary way to quote data. The
     * cells now carry invisible marks and this is where the pipes come back.
     */
    @Test
    fun `a selection across a table comes back as markdown rows`() {
        val selected = "Planet${CELL}Moons$ROW" + "Earth${CELL}The Moon$ROW" + "Mars${CELL}Phobos, Deimos$ROW"
        assertEquals(
            "| Planet | Moons |\n| Earth | The Moon |\n| Mars | Phobos, Deimos |",
            QuickActionRules.tableRows(selected),
        )
    }

    @Test
    fun `quoting a table quotes the rows, one per line`() {
        val selected = "Planet${CELL}Moons$ROW" + "Earth${CELL}The Moon$ROW"
        assertEquals(
            "> | Planet | Moons |\n> | Earth | The Moon |",
            QuickActionRules.quoteBlock(selected),
        )
    }

    /**
     * The walker's exact shape: a drag that started in one speaker's message and
     * ran down into a table in the other's. The prose gets its own line instead
     * of running into the first cell.
     */
    @Test
    fun `prose that runs into a table is separated from it`() {
        val selected = "ease answer with a markdown table.${CELL}Planet${CELL}Moons$ROW" + "Earth${CELL}The Moon$ROW"
        assertEquals(
            "| ease answer with a markdown table. | Planet | Moons |\n| Earth | The Moon |",
            QuickActionRules.tableRows(selected),
            "every marked segment is a row; nothing runs together any more",
        )
    }

    /**
     * ⚠ A PARTIAL SELECTION IS NOT AN ERROR. A drag that starts in the middle of
     * a table gets the cells it covered, as a shorter row — never an invented
     * cell and never a run-on.
     */
    @Test
    fun `a drag that starts mid-table gets the cells it covered`() {
        assertEquals(
            "| The Moon |\n| Mars | Phobos, Deimos |",
            QuickActionRules.tableRows("The Moon$ROW" + "Mars${CELL}Phobos, Deimos$ROW"),
        )
    }

    /** Ordinary prose never crossed a table and comes back untouched. */
    @Test
    fun `text with no marks is returned exactly`() {
        val plain = "just a sentence\nand another"
        assertSame(plain, QuickActionRules.tableRows(plain))
        assertEquals(plain, QuickActionRules.copyText(plain))
    }

    /**
     * ⚠⚠ THE MARKS MUST NEVER TRAVEL. A zero-width character in a composer is a
     * zero-width character in a prompt, and in a shell command it is worse than
     * confusing. Every exit a selection has goes through `copyText` or
     * `textFor`.
     */
    @Test
    fun `no exit from a selection carries the marks`() {
        val selected = "A${CELL}B$ROW"
        for (out in listOf(
            QuickActionRules.copyText(selected),
            QuickActionRules.quoteBlock(selected),
            QuickActionRules.quote("look:", selected),
            QuickActionRules.compose("explain {selection}", selected),
            QuickActionRules.textFor(SelectionAction.EXPLAIN, null, selected),
            QuickActionRules.textFor(SelectionAction.QUOTE, null, selected),
        )) {
            assertFalse(TableMarks.marked(out), "a mark escaped into: ${out.map { it.code }}")
        }
    }

    @Test
    fun `strip removes them and nothing else`() {
        assertEquals("AB", TableMarks.strip("A${CELL}B$ROW"))
        assertEquals("plain", TableMarks.strip("plain"))
    }
}
