package com.silencelen.huginn

import com.silencelen.huginn.data.Screen
import com.silencelen.huginn.ui.hasCopyableText
import com.silencelen.huginn.ui.linksOn
import com.silencelen.huginn.ui.screenText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Taking text back out of a pane.
 *
 * The first test is the real screen that made this exist: a Claude Code sign-in
 * URL on a headless machine, wrapped across five rows by a 110-column pane. The
 * owner could see it and had no way to use it.
 */
class ScreenCopyTest {

    private fun screen(width: Int, vararg lines: String) =
        Screen(width = width, height = lines.size, lines = lines.toList())

    @Test
    fun theSignInUrlThatStartedThis() {
        // Wrapped at exactly 110 columns — every row but the last reaches the edge.
        val s = screen(
            110,
            "Welcome to Claude Code v2.1.241",
            " Browser didn't open? Use the url below to sign in (c to copy)",
            "https://claude.com/cai/oauth/authorize?code=true&client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e&response_type=c",
            "ode&redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback&scope=org%3Acreate_api_key+user%3A",
            "profile+user%3Ainference+user%3Asessions%3Aclaude_code+user%3Amcp_servers+user%3Afile_upload&code_challenge=LIC",
            "ADWHCl4xucd18KF-1lgwSEFhQWLqm21nHf_0HRa0&code_challenge_method=S256&state=w4eSFCCedVWDU3WmlCuLA36kfCh_iZxn9tVHz",
            "XOU9Nc",
            " Paste code here if prompted >",
        )
        val links = linksOn(s)
        assertEquals(1, links.size, "the wrapped URL is one link, not five: $links")
        assertEquals(450, links[0].length)
        assertTrue(links[0].endsWith("state=w4eSFCCedVWDU3WmlCuLA36kfCh_iZxn9tVHzXOU9Nc"), links[0])
        assertTrue(links[0].startsWith("https://claude.com/cai/oauth/authorize?code=true"), links[0])
        // The escaped redirect_uri inside the query is NOT a second link — it is
        // percent-encoded, so nothing should have split it out.
        assertFalse(links[0].contains(" "))
    }

    @Test
    fun copyingTheScreenDoesNotReflowIt() {
        // The load-bearing distinction. A terminal draws in COLUMNS, and a copy
        // that silently rejoined its rows would corrupt every table and tree on
        // screen. Only the link copy undoes a wrap, and only inside the link.
        val s = screen(10, "aaaaaaaaaa", "bbbbbbbbbb", "cc")
        assertEquals("aaaaaaaaaa\nbbbbbbbbbb\ncc", screenText(s))
    }

    @Test
    fun anEmptyBottomIsNotContent() {
        val s = screen(20, "one", "two   ", "", "   ", "")
        assertEquals("one\ntwo", screenText(s), "trailing blank rows and trailing spaces go")
    }

    @Test
    fun aLinkThatFitsOnOneRowIsLeftAlone() {
        val s = screen(80, "see https://example.com/x for more", "next line")
        assertEquals(listOf("https://example.com/x"), linksOn(s))
    }

    @Test
    fun aFullWidthRowThatIsNotALinkStillJoinsWithoutInventingOne() {
        // Joining happens for link-finding whatever the content; it must not
        // manufacture a link out of two unrelated rows.
        val s = screen(10, "0123456789", "abcdefghij", "kl")
        assertTrue(linksOn(s).isEmpty())
    }

    @Test
    fun sentenceEndingPunctuationIsNotPartOfTheUrl() {
        val s = screen(80, "open https://example.com/page.", "and https://example.com/b).")
        assertEquals(listOf("https://example.com/page", "https://example.com/b"), linksOn(s))
    }

    @Test
    fun theSameLinkTwiceIsOneOffer() {
        // Panes repeat themselves — printed once, echoed in a status line — and
        // offering the same link twice is a choice with no answer.
        val s = screen(80, "https://example.com/a", "working…", "https://example.com/a")
        assertEquals(1, linksOn(s).size)
    }

    @Test
    fun aBareSchemeIsNotALink() {
        // What a truncated pane leaves behind. Copying it would look like it worked.
        val s = screen(80, "https://", "http://")
        assertTrue(linksOn(s).isEmpty())
    }

    @Test
    fun twoDifferentLinksOnOneRowBothCome() {
        val s = screen(80, "a https://one.example b https://two.example")
        assertEquals(listOf("https://one.example", "https://two.example"), linksOn(s))
    }

    @Test
    fun nothingToCopyIsSaidRatherThanOfferedEmpty() {
        assertFalse(hasCopyableText(null))
        assertFalse(hasCopyableText(screen(20, "", "   ")))
        assertTrue(hasCopyableText(screen(20, "x")))
        assertEquals("", screenText(null))
        assertTrue(linksOn(null).isEmpty())
    }

    @Test
    fun aPaneWithNoWidthIsCopiedRatherThanRefused() {
        // width 0 should never happen, but a screen that arrived malformed should
        // still give up its text — the fallback is "do not unwrap", not "do nothing".
        val s = Screen(width = 0, lines = listOf("https://example.com/x", "more"))
        assertEquals(listOf("https://example.com/x"), linksOn(s))
        assertEquals("https://example.com/x\nmore", screenText(s))
    }
}
