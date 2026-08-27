package com.silencelen.huginn

import com.silencelen.huginn.data.Scratchpad
import com.silencelen.huginn.data.ScratchpadList
import com.silencelen.huginn.ui.AttachmentText
import com.silencelen.huginn.ui.ScratchpadRules
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The naming rules, and the frame a page arrives in. NOTE kotlin.test's argument
 * order is (expected, actual, message).
 *
 * The frames are asserted as LITERALS, spelled out here rather than built from
 * anything, because the writer is in another language: the daemon's
 * lib/scratchpads.js composes them and this only ever undoes one. A test that
 * round-tripped through a shared helper would pass happily while the two sides
 * had stopped agreeing, and the visible symptom is a raw marker sitting in the
 * sender's own message.
 */
class ScratchpadRulesTest {

    // -------------------------------------------------------------- the names

    @Test
    fun `a page needs a name that is actually a name`() {
        assertEquals("a page needs a name", ScratchpadRules.nameProblem(""))
        assertEquals("a page needs a name", ScratchpadRules.nameProblem("   "))
        assertNull(ScratchpadRules.nameProblem("Deploy notes"))
    }

    @Test
    fun `a name is one line, with nothing in it that can move a cursor`() {
        assertEquals("Deploy notes", ScratchpadRules.cleanName("Deploy\nnotes"))
        assertEquals("spaced out", ScratchpadRules.cleanName("  spaced   out  "))
        assertEquals(
            "Deploy [2Knotes",
            ScratchpadRules.cleanName("Deploy\u001B[2Knotes"),
            "the escape goes; its printable tail is not this function's business",
        )
    }

    @Test
    fun `a double quote is refused rather than quietly removed`() {
        // It is the frame's own delimiter. Stripping it would rename somebody's
        // page behind their back; leaving it in would break the collapse.
        assertEquals(
            "a page name cannot contain a double quote",
            ScratchpadRules.nameProblem("Ideas \"v2\""),
        )
    }

    @Test
    fun `names collide case-insensitively, because that is how a picker reads`() {
        assertEquals(
            "there is already a page with that name",
            ScratchpadRules.nameProblem("notes", listOf("Notes")),
        )
        assertNull(ScratchpadRules.nameProblem("notes", listOf("Deploy notes")), "a substring is not a collision")
    }

    @Test
    fun `the caps are the daemon's own`() {
        assertNull(ScratchpadRules.nameProblem("x".repeat(60)))
        assertEquals(
            "a page name is at most 60 characters",
            ScratchpadRules.nameProblem("x".repeat(61)),
        )
        assertNull(ScratchpadRules.contentProblem("x".repeat(100_000)))
        assertEquals(
            "a page holds at most 100000 characters",
            ScratchpadRules.contentProblem("x".repeat(100_001)),
        )
    }

    @Test
    fun `the length cap is measured the way the daemon measures it`() {
        // ⚠ NORMALISED length, not raw. lib/scratchpads.js collapses whitespace
        // and turns control characters into spaces BEFORE it counts, so a name
        // padded out with tabs or carrying a newline from a paste is well inside
        // the cap on the server — while the editor here refused it. A courtesy
        // check that says no to something the server accepts is the worst kind:
        // there is nothing to argue with and no way through.
        // Twenty two-letter words with five spaces between them: 135 characters
        // as typed, 59 as the daemon stores it. The daemon takes it; the editor
        // used to refuse it.
        val spacedOut = List(20) { "ab" }.joinToString("     ")
        assertEquals(135, spacedOut.length, "the fixture is the point of this test")
        assertNull(ScratchpadRules.nameProblem(spacedOut), "collapsed whitespace counts once, as it does on the daemon")
        assertEquals(59, ScratchpadRules.cleanName(spacedOut).length)
        assertNull(ScratchpadRules.nameProblem("  " + "x".repeat(60) + "  "))
        // Still over once collapsed, and still refused.
        assertEquals(
            "a page name is at most 60 characters",
            ScratchpadRules.nameProblem(List(31) { "ab" }.joinToString("     ")),
        )
    }

    // ------------------------------------------------------------ the ordering

    @Test
    fun `pages are listed Main first, then by name`() {
        // ⚠ NOT by "recently edited", which is the order they arrive in. A list
        // that re-sorts while somebody is typing moves the row under the cursor —
        // during testing that put two paragraphs into the wrong page.
        val pads = listOf(
            Scratchpad(id = "c", name = "zebra", updatedAt = 900),
            Scratchpad(id = "a", name = "Main", main = true, updatedAt = 100),
            Scratchpad(id = "b", name = "Alpha", updatedAt = 500),
        )
        assertEquals(
            listOf("Main", "Alpha", "zebra"),
            ScratchpadRules.ordered(pads).map { it.name },
        )
    }

    @Test
    fun `the order does not depend on the case somebody typed`() {
        val pads = listOf(
            Scratchpad(id = "1", name = "beta"),
            Scratchpad(id = "2", name = "Alpha"),
            Scratchpad(id = "3", name = "ALPHABET"),
        )
        assertEquals(
            listOf("Alpha", "ALPHABET", "beta"),
            ScratchpadRules.ordered(pads).map { it.name },
        )
    }

    @Test
    fun `two pages that read the same never swap places between polls`() {
        // The tie-break is the id, so a poll that returns them the other way
        // round draws them in the same order it did a second ago.
        val one = listOf(Scratchpad(id = "aaa", name = "Notes"), Scratchpad(id = "bbb", name = "notes"))
        assertEquals(
            ScratchpadRules.ordered(one).map { it.id },
            ScratchpadRules.ordered(one.reversed()).map { it.id },
        )
    }

    // ------------------------------------------------------------- the frames

    @Test
    fun `a chat message collapses to a pill and the sender's own words`() {
        val wire = "[Scratchpad \"Hostnames\"]\nheimdall\nskybox\n[End scratchpad]\n\nwhich one is the standby?"
        assertEquals("📝 Hostnames\nwhich one is the standby?", ScratchpadRules.collapse(wire))
    }

    @Test
    fun `a session message collapses too, path and all`() {
        val wire = "[Scratchpad \"Deploy notes\" at /var/lib/huginn-appd/scratchpads/render/a.md — " +
            "read it before acting on this message.]\nfollow this"
        assertEquals("📝 Deploy notes\nfollow this", ScratchpadRules.collapse(wire))
    }

    @Test
    fun `an ordinary message is returned untouched`() {
        assertEquals("nothing bracketed here", ScratchpadRules.collapse("nothing bracketed here"))
        assertEquals("a [list] of things", ScratchpadRules.collapse("a [list] of things"))
        assertFalse(ScratchpadRules.hasReference("a [list] of things"))
    }

    @Test
    fun `the page's own words never outrank the sender's`() {
        // Somebody asking ABOUT the marker is why this stops at the FIRST closing
        // line rather than the last. A greedy scan runs to the marker inside the
        // QUESTION and deletes the words in between — here, "what does" — leaving
        // a message that reads as if they never typed it.
        val wire = "[Scratchpad \"Meta\"]\nnotes\n[End scratchpad]\n\nwhat does\n[End scratchpad]\nmean?"
        assertEquals("📝 Meta\nwhat does\n[End scratchpad]\nmean?", ScratchpadRules.collapse(wire))
    }

    @Test
    fun `a tagged frame collapses, and only against its own closing tag`() {
        // The daemon mints a tag when the PAGE's own text contains a line starting
        // "[End scratchpad" — the one case where stopping at the first closer
        // would leave half the page on screen. The closer then carries the
        // opener's tag, so the scan can run past the impostor safely.
        val tagged = "[Scratchpad \"Meta\" #a1b2c3]\nnotes\n[End scratchpad]\nmore notes\n" +
            "[End scratchpad #a1b2c3]\n\nwhat does that mean?"
        assertEquals("📝 Meta\nwhat does that mean?", ScratchpadRules.collapse(tagged))
        assertEquals("Meta", ScratchpadRules.referencedName(tagged))
    }

    @Test
    fun `a closing tag that does not match its opener is not a frame`() {
        // A backreference, not two independent optionals: a marker somebody typed
        // (or a second page's closer) must not end a frame it did not open.
        val mismatched = "[Scratchpad \"Meta\" #a1b2c3]\nnotes\n[End scratchpad #ffffff]\n\nhello"
        assertEquals(mismatched.trim(), ScratchpadRules.collapse(mismatched), "it collapsed on somebody else's tag")
    }

    @Test
    fun `an untagged frame still collapses, which is every frame ever written`() {
        // ⚠⚠ THE CROSS-LANGUAGE TRAP. The daemon's pattern is one optional group
        // plus a `\2` backreference, which in JavaScript matches the empty string
        // when the group did not participate. In Java — Kotlin's Regex — the same
        // backreference FAILS, so a literal port would stop collapsing every
        // untagged frame and leave a raw marker in the sender's own message.
        val plain = "[Scratchpad \"Hostnames\"]\nheimdall\n[End scratchpad]\n\nwhich one?"
        assertEquals("📝 Hostnames\nwhich one?", ScratchpadRules.collapse(plain))
        assertEquals("Hostnames", ScratchpadRules.referencedName(plain))
    }

    @Test
    fun `the name travels out of the frame, for a chip that says which page`() {
        val wire = "[Scratchpad \"Hostnames\"]\nheimdall\n[End scratchpad]\n\nhello"
        assertEquals("Hostnames", ScratchpadRules.referencedName(wire))
        assertNull(ScratchpadRules.referencedName("hello"))
    }

    @Test
    fun `a page and a photo in one message both collapse`() {
        // Both markers are the daemon's plumbing and neither is what the person
        // said; displayText is the one place a reader's message is assembled.
        val wire = "[Scratchpad \"Hostnames\"]\nheimdall\n[End scratchpad]\n\nlook at this\n\n" +
            AttachmentText.marker("/var/lib/huginn-appd/uploads/up-1-ab.jpg")
        assertEquals("📝 Hostnames\nlook at this\n\n📷 Photo attached", AttachmentText.displayText(wire))
    }

    // ---------------------------------------------------------------- the keys

    @Test
    fun `a chat's chosen page and a session's are different memories`() {
        assertEquals("padref:chat:x", ScratchpadRules.chatRefKey("x"))
        assertEquals("padref:sess:x", ScratchpadRules.sessionRefKey("x"))
    }

    // --------------------------------------------------------------- the wire

    @Test
    fun `the daemon's own list body decodes straight into the picker`() {
        // The SAME decoder HuginnClient uses, unknown keys and all — a renamed
        // field decodes to a default rather than throwing, so nothing else in the
        // suite would notice. This body is what GET /v1/scratchpads really answers.
        val wire = """
            {"pads":[
              {"id":"6f1c0f5e-0000-4000-8000-000000000001","name":"Main","createdAt":1787000000,
               "updatedAt":1787000900,"rev":4,"main":true,"size":128},
              {"id":"6f1c0f5e-0000-4000-8000-000000000002","name":"Deploy notes","createdAt":1787000100,
               "updatedAt":1787000200,"rev":1,"main":false,"size":0}
            ]}
        """.trimIndent()
        val list = Json { ignoreUnknownKeys = true; explicitNulls = false }
            .decodeFromString<ScratchpadList>(wire)
        assertEquals(2, list.pads.size)
        assertTrue(list.pads[0].main, "the fallback page must be identifiable without a name match")
        assertEquals(4, list.pads[0].rev, "a rev that decoded to 0 would 409 on the very first save")
        assertEquals(128, list.pads[0].size)
        assertEquals("", list.pads[0].content, "a list row carries no content, and that is not an error")
    }

    @Test
    fun `a fetched page carries the text and the rev a save must send back`() {
        val wire = """{"id":"a","name":"Main","content":"one\ntwo","rev":7,"main":true,
            "createdAt":1,"updatedAt":2}""".trimIndent()
        val pad = Json { ignoreUnknownKeys = true; explicitNulls = false }.decodeFromString<Scratchpad>(wire)
        assertEquals("one\ntwo", pad.content)
        assertEquals(7, pad.rev)
    }
}
