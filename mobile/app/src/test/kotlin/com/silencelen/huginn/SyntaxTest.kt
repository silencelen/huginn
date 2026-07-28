package com.silencelen.huginn

import com.silencelen.huginn.ui.Syntax
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The highlighter is a lexer, so the tests care about two things: that the spans
 * it produces are *safe* (in range, non-overlapping enough to apply), and that
 * the classes which actually aid reading land on the right text. A missed
 * keyword is cosmetic; an out-of-range span would crash the render.
 */
class SyntaxTest {

    private fun spansOf(code: String, lang: String?) = Syntax.highlight(code, lang)

    private fun textOf(code: String, lang: String?, tok: Syntax.Tok): List<String> =
        spansOf(code, lang).filter { it.tok == tok }.map { code.substring(it.start, it.end) }

    // ---- safety: this is what would crash a screen ------------------------

    @Test
    fun `every span stays inside the string`() {
        val samples = listOf(
            "echo \"unterminated",
            "# just a comment",
            "val x = \"a\\\"b\"",
            "/* unclosed block",
            "'",
            "",
            "\n\n\n",
            "0x",
            "\$VAR",
        )
        for (lang in listOf("bash", "kotlin", "json", "diff", null, "unknownlang")) {
            for (s in samples) {
                for (sp in spansOf(s, lang)) {
                    assertTrue("start in range for '$s'/$lang", sp.start in 0..s.length)
                    assertTrue("end in range for '$s'/$lang", sp.end in sp.start..s.length)
                }
            }
        }
    }

    @Test
    fun `a plain fence produces no spans at all`() {
        assertEquals(emptyList<Syntax.Span>(), spansOf("just some text", null))
    }

    @Test
    fun `an unterminated string does not swallow the following lines`() {
        val code = "echo \"oops\nls -la\n"
        val strings = textOf(code, "bash", Syntax.Tok.STRING)
        assertTrue("string span must stop at the newline", strings.none { it.contains("ls -la") })
    }

    @Test
    fun `an unclosed block comment ends at the end of input rather than looping`() {
        val code = "/* forever"
        assertEquals(listOf("/* forever"), textOf(code, "kotlin", Syntax.Tok.COMMENT))
    }

    // ---- the classes that aid reading -------------------------------------

    @Test
    fun `a shell command colours its comment, string and flags`() {
        val code = "# deploy it\nrsync -av \"src/\" host:/dest   # trailing"
        assertEquals(listOf("# deploy it", "# trailing"), textOf(code, "bash", Syntax.Tok.COMMENT))
        assertEquals(listOf("\"src/\""), textOf(code, "bash", Syntax.Tok.STRING))
        assertTrue("the -av flag should read as structure", textOf(code, "bash", Syntax.Tok.META).contains("-av"))
    }

    @Test
    fun `shell keywords are recognised but ordinary words are not`() {
        val kw = textOf("if test -f x; then echo hi; fi", "bash", Syntax.Tok.KEYWORD)
        assertTrue(kw.containsAll(listOf("if", "then", "echo", "fi")))
        assertTrue("a path is not a keyword", !kw.contains("x"))
    }

    @Test
    fun `kotlin keywords and numbers are classified`() {
        val code = "private val timeout = 8000  // ms"
        assertTrue(textOf(code, "kotlin", Syntax.Tok.KEYWORD).containsAll(listOf("private", "val")))
        assertEquals(listOf("8000"), textOf(code, "kotlin", Syntax.Tok.NUMBER))
        assertEquals(listOf("// ms"), textOf(code, "kotlin", Syntax.Tok.COMMENT))
    }

    @Test
    fun `a called function is distinguished from a bare word`() {
        assertEquals(listOf("compute"), textOf("compute(x) + other", "kotlin", Syntax.Tok.FUNCTION))
    }

    @Test
    fun `an identifier containing digits is not read as a number`() {
        assertEquals(emptyList<String>(), textOf("claude_fable_5 = 1", "kotlin", Syntax.Tok.NUMBER)
            .filter { it != "1" })
    }

    @Test
    fun `an escaped quote does not terminate the string early`() {
        assertEquals(listOf("\"a\\\"b\""), textOf("val s = \"a\\\"b\"", "kotlin", Syntax.Tok.STRING))
    }

    // ---- diffs -------------------------------------------------------------

    @Test
    fun `a diff colours whole lines by their sign`() {
        val code = "--- a/x\n+++ b/x\n@@ -1,2 +1,2 @@\n-old line\n+new line\n unchanged"
        assertEquals(listOf("-old line"), textOf(code, "diff", Syntax.Tok.REMOVED))
        assertEquals(listOf("+new line"), textOf(code, "diff", Syntax.Tok.ADDED))
        assertEquals(listOf("--- a/x", "+++ b/x", "@@ -1,2 +1,2 @@"), textOf(code, "diff", Syntax.Tok.META))
    }

    @Test
    fun `an unchanged diff line gets no colour`() {
        assertEquals(emptyList<Syntax.Span>(), spansOf(" context only", "diff"))
    }

    // ---- dialect mapping ---------------------------------------------------

    @Test
    fun `fence labels map onto the dialects we actually tokenize`() {
        assertEquals("shell", Syntax.dialect("bash"))
        assertEquals("shell", Syntax.dialect("Console"))
        assertEquals("diff", Syntax.dialect("patch"))
        assertEquals("json", Syntax.dialect("json"))
        assertEquals("conf", Syntax.dialect("yml"))
        assertEquals("plain", Syntax.dialect(null))
        assertEquals("code", Syntax.dialect("kotlin"))
    }
}
