package com.silencelen.huginn

import com.silencelen.huginn.ui.MdBlock
import com.silencelen.huginn.ui.Markdown
import com.silencelen.huginn.ui.tailRevision
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * The renderer only has to handle what Claude actually writes in an answer. The
 * property that matters most is that a code fence survives as a code block:
 * flattening a shell command into prose is what made v1's chat unusable on a
 * phone, and a mangled command is worse than no command.
 */
class MarkdownTest {

    @Test
    fun `a fenced block becomes a code block with its language`() {
        val b = Markdown.parse("Run this:\n\n```bash\ncd /root/projects/app\ngit status\n```\n")
        assertEquals(2, b.size)
        assertTrue(b[0] is MdBlock.Paragraph)
        val code = b[1] as MdBlock.Code
        assertEquals("bash", code.lang)
        assertEquals("cd /root/projects/app\ngit status", code.code)
    }

    @Test
    fun `markdown inside a fence is left completely alone`() {
        val code = Markdown.parse("```\n**not bold** and *not italic* and `not code`\n```").first() as MdBlock.Code
        assertEquals("**not bold** and *not italic* and `not code`", code.code)
    }

    @Test
    fun `an unclosed fence still yields a code block rather than eating the answer`() {
        val b = Markdown.parse("```python\nprint(1)\n")
        val code = b.first() as MdBlock.Code
        assertEquals("print(1)", code.code)
    }

    @Test
    fun `a tilde fence works like a backtick fence`() {
        val code = Markdown.parse("~~~\nplain\n~~~").first() as MdBlock.Code
        assertEquals("plain", code.code)
    }

    @Test
    fun `headings carry their level`() {
        val b = Markdown.parse("# One\n## Two\n### Three")
        assertEquals(1, (b[0] as MdBlock.Heading).level)
        assertEquals(2, (b[1] as MdBlock.Heading).level)
        assertEquals(3, (b[2] as MdBlock.Heading).level)
        assertEquals("One", (b[0] as MdBlock.Heading).text.text)
    }

    @Test
    fun `bullets and numbered items are separate blocks`() {
        val b = Markdown.parse("- first\n- second\n\n1. one\n2. two")
        val bullets = b.filterIsInstance<MdBlock.Bullet>()
        assertEquals(4, bullets.size)
        assertEquals(null, bullets[0].ordinal)
        assertEquals("1.", bullets[2].ordinal)
        assertEquals("one", bullets[2].text.text)
    }

    @Test
    fun `a wrapped bullet stays one bullet`() {
        val b = Markdown.parse("- a long item that\n  continues on the next line\n- second")
        val bullets = b.filterIsInstance<MdBlock.Bullet>()
        assertEquals(2, bullets.size)
        assertEquals("a long item that continues on the next line", bullets[0].text.text)
    }

    @Test
    fun `inline styles are applied and their markers removed`() {
        val s = Markdown.inline("**bold** and *italic* and `code` and ~~gone~~")
        assertEquals("bold and italic and code and gone", s.text)
        assertTrue(s.spanStyles.size >= 4, "expected several styled spans")
    }

    @Test
    fun `an unmatched marker is shown literally instead of vanishing`() {
        assertEquals("2 * 3 = 6", Markdown.inline("2 * 3 = 6").text)
        assertEquals("a `dangling", Markdown.inline("a `dangling").text)
        assertEquals("**not closed", Markdown.inline("**not closed").text)
    }

    @Test
    fun `snake_case identifiers are not treated as emphasis`() {
        // This one bites constantly in a codebase full of file_path and tool_use.
        assertEquals("some_long_name here", Markdown.inline("some_long_name here").text)
    }

    /**
     * ⚠ VERBATIM FROM THE /context TABLE (2026-11-03, both apps): an MCP tool's
     * name is `mcp__<server>__<tool>`, and it rendered as
     * "mcp_*mempalace*mempalacekg_timeline" — the underscores eaten and the
     * middle in italics. Only the OPENER was checked for a letter before it, so
     * the second `_` of every `__` opened emphasis. CommonMark: a `_` run that
     * sits between two alphanumerics can neither open nor close.
     */
    @Test
    fun `intraword underscores and double underscores stay literal`() {
        for (name in listOf(
            "mcp__mempalace__mempalace_kg_timeline",
            "mcp__claude_ai_Claude_Docs__batch",
            "a__b__c",
            "x_1_y and z__2__w",
        )) {
            val s = Markdown.inline(name)
            assertEquals(name, s.text, "every underscore of $name is a character")
            assertTrue(s.spanStyles.isEmpty(), "nothing in $name is emphasis: ${s.spanStyles}")
        }
        // The table row it came from: a cell is inline-parsed like prose.
        val t = Markdown.parse("| Tool | Tokens |\n|---|---|\n| mcp__mempalace__mempalace_kg_timeline | 152 |")
            .single() as MdBlock.Table
        assertEquals("mcp__mempalace__mempalace_kg_timeline", t.rows[1][0].text)
        // ...while `*` keeps working intraword: CommonMark holds back only `_`.
        val star = Markdown.inline("foo*bar*baz and un**frigging**believable")
        assertEquals("foobarbaz and unfriggingbelievable", star.text)
        assertTrue(italicOver(star, "bar"))
        assertTrue(boldOver(star, "frigging"))
    }

    @Test
    fun `underscore emphasis and strong still work at word boundaries`() {
        val em = Markdown.inline("this is _emphasis_ here")
        assertEquals("this is emphasis here", em.text)
        assertTrue(italicOver(em, "emphasis"))
        val strong = Markdown.inline("and __strong__ too")
        assertEquals("and strong too", strong.text)
        assertTrue(boldOver(strong, "strong"))
        assertEquals("(underlined)", Markdown.inline("(_underlined_)").text, "punctuation is a boundary")
    }

    /**
     * ⚠ BOTH APPS DREW `**\`hi.png\`**` AS BOLD WITH THE BACKTICKS IN IT: the
     * bold branch appended its inner text raw, so nothing inside a strong or an
     * emphasis span was parsed at all.
     */
    @Test
    fun `a code span nests inside bold and emphasis`() {
        val s = Markdown.inline("**`hi.png`**")
        assertEquals("hi.png", s.text, "the backticks are markers, not text")
        assertTrue(boldOver(s, "hi.png"))
        assertTrue(monoOver(s, "hi.png"))
        val e = Markdown.inline("saved *`out/report.md`* and **see `ls -la` first**")
        assertEquals("saved out/report.md and see ls -la first", e.text)
        assertTrue(italicOver(e, "out/report.md"))
        assertTrue(monoOver(e, "out/report.md"))
        assertTrue(boldOver(e, "see ls -la first"))
        assertTrue(monoOver(e, "ls -la"))
        assertFalse(monoOver(e, "see "), "only the span is code")
    }

    @Test
    fun `emphasis nests inside strong`() {
        val s = Markdown.inline("**a *b* c**")
        assertEquals("a b c", s.text)
        assertTrue(boldOver(s, "a b c"))
        assertTrue(italicOver(s, "b"))
        assertFalse(italicOver(s, "a "))
        val both = Markdown.inline("***both***")
        assertEquals("both", both.text)
        assertTrue(boldOver(both, "both"))
        assertTrue(italicOver(both, "both"))
    }

    @Test
    fun `a double backtick span may hold a backtick, and nothing inside a span is markup`() {
        assertEquals("use a`b here", Markdown.inline("use ``a`b`` here").text)
        assertEquals("``unclosed", Markdown.inline("``unclosed").text)
        val s = Markdown.inline("run `a_b * c **d**` now")
        assertEquals("run a_b * c **d** now", s.text)
        assertEquals(1, s.spanStyles.size, "the code span is the only style: ${s.spanStyles}")
    }

    private fun covering(s: AnnotatedString, piece: String): List<SpanStyle> {
        val at = s.text.indexOf(piece)
        assertTrue(at >= 0, "'$piece' is not in '${s.text}'")
        return s.spanStyles.filter { it.start <= at && it.end >= at + piece.length }.map { it.item }
    }

    private fun boldOver(s: AnnotatedString, piece: String) = covering(s, piece).any { it.fontWeight == FontWeight.Bold }
    private fun italicOver(s: AnnotatedString, piece: String) = covering(s, piece).any { it.fontStyle == FontStyle.Italic }
    private fun monoOver(s: AnnotatedString, piece: String) = covering(s, piece).any { it.fontFamily == FontFamily.Monospace }

    /**
     * WAS `a link keeps its label and appends the url only when it adds
     * something`, and the parens URL was pinned here since the renderer had no
     * way to make a label clickable. It has one now (decision 44), so the URL
     * lives in the link annotation instead of in the prose — the text people
     * read, and copy, is the label alone.
     */
    @Test
    fun `a link keeps its label and nothing else`() {
        assertEquals("docs", Markdown.inline("[docs](https://x.test/a)").text)
        assertEquals("https://x.test", Markdown.inline("[https://x.test](https://x.test)").text)
    }

    /**
     * A link's words are inline markdown like any others — the same nesting fix
     * as a code span in bold — but a link inside a link is never made: the label
     * of `[https://x.test](https://x.test)` is not a second, auto-linked URL.
     */
    @Test
    fun `a link's words are inline markdown, and never a second link`() {
        val s = Markdown.inline("see [**the** `runbook`](https://x.test/r) now")
        assertEquals("see the runbook now", s.text)
        assertTrue(boldOver(s, "the"))
        assertTrue(monoOver(s, "runbook"))
        val link = s.getLinkAnnotations(0, s.length).single()
        assertEquals("the runbook", s.text.substring(link.start, link.end))
        assertEquals(1, Markdown.inline("[https://x.test](https://x.test)").getLinkAnnotations(0, 14).size)
    }

    @Test
    fun `a link becomes a real link span carrying the url`() {
        val s = Markdown.inline("see [docs](https://x.test/a) now")
        val link = s.getLinkAnnotations(0, s.length).single()
        assertEquals("https://x.test/a", (link.item as LinkAnnotation.Url).url)
        assertEquals("docs", s.text.substring(link.start, link.end))
        assertEquals("see docs now", s.text)
    }

    /**
     * ⚠ THE SECURITY LINE OF THIS FEATURE. Claude's output is text from a model,
     * and a clickable `huginn://` in it would reach the desktop's own scheme
     * handler — which is fingerprint-gated precisely because it is reachable from
     * outside. `file:` and `javascript:` are the same argument. A refused scheme
     * is not hidden: the label is still shown, it simply is not a link.
     */
    @Test
    fun `a non-http scheme is refused as a link and shown as ordinary text`() {
        val refused = listOf(
            "file:///etc/passwd",
            "huginn://open/session/x",
            "javascript:alert(1)",
            "mailto:someone@x.test",
            "/docs/page",
            "ftp://x.test/a",
        )
        for (u in refused) {
            val s = Markdown.inline("[x]($u)")
            assertEquals("x", s.text, "label still shown for $u")
            assertTrue(s.getLinkAnnotations(0, s.length).isEmpty(), "must not be a link: $u")
        }
    }

    @Test
    fun `a bare url is auto-linked without swallowing the sentence punctuation after it`() {
        val s = Markdown.inline("see https://x.test/a. done")
        assertEquals("see https://x.test/a. done", s.text, "the text is untouched")
        val link = s.getLinkAnnotations(0, s.length).single()
        assertEquals("https://x.test/a", (link.item as LinkAnnotation.Url).url)
        assertEquals("https://x.test/a", s.text.substring(link.start, link.end))
    }

    @Test
    fun `an auto-linked url keeps its own parentheses and drops the one closing a sentence`() {
        // Wikipedia-shaped URLs really do carry parens, and a URL written inside
        // a parenthetical really does not own the closing one. Counting is the
        // only thing that tells them apart.
        assertEquals("https://x.test/a_(b)", firstUrl("(see https://x.test/a_(b))"))
        assertEquals("https://x.test/a", firstUrl("(https://x.test/a)"))
        assertEquals("http://h:8787/v1/ping", firstUrl("try http://h:8787/v1/ping!"))
        assertEquals("https://x.test/a", firstUrl("https://x.test/a, then"))
    }

    @Test
    fun `a url inside a code span is left as code, not linked`() {
        val s = Markdown.inline("run `curl https://x.test/a` first")
        assertTrue(s.getLinkAnnotations(0, s.length).isEmpty(), "code is quoted, not clicked")
    }

    @Test
    fun `an image on its own line becomes an image block`() {
        val b = Markdown.parse("here it is\n\n![a shot](/tmp/shot.png)\n")
        val img = b.filterIsInstance<MdBlock.Image>().single()
        assertEquals("/tmp/shot.png", img.src)
        assertEquals("a shot", img.alt)
        assertEquals(1, b.filterIsInstance<MdBlock.Paragraph>().size)
    }

    /**
     * Half an image in the middle of a sentence has nowhere to draw — the
     * paragraph is one text flow — so it degrades to what was written rather
     * than to a link, which is what the `[` branch would have made of it.
     */
    @Test
    fun `an image inside a paragraph stays literal text`() {
        val p = Markdown.parse("look ![a](/tmp/x.png) there").single() as MdBlock.Paragraph
        assertEquals("look ![a](/tmp/x.png) there", p.text.text)
        assertTrue(p.text.getLinkAnnotations(0, p.text.length).isEmpty())
    }

    private fun firstUrl(src: String): String? {
        val s = Markdown.inline(src)
        return (s.getLinkAnnotations(0, s.length).firstOrNull()?.item as? LinkAnnotation.Url)?.url
    }

    @Test
    fun `blank lines separate paragraphs`() {
        val b = Markdown.parse("one\n\ntwo")
        assertEquals(2, b.filterIsInstance<MdBlock.Paragraph>().size)
    }

    @Test
    fun `a horizontal rule is its own block`() {
        val b = Markdown.parse("above\n\n---\n\nbelow")
        assertTrue(b.any { it is MdBlock.Rule })
    }

    @Test
    fun `a blockquote is recognised`() {
        val q = Markdown.parse("> quoted text").first() as MdBlock.Quote
        assertEquals("quoted text", q.text.text)
    }

    @Test
    fun `plain prose with no markup survives unchanged`() {
        val text = "Disk is at 62% and the daemon is healthy."
        assertEquals(text, (Markdown.parse(text).first() as MdBlock.Paragraph).text.text)
    }
}

/**
 * The follower's revision signal. Its whole job is to change when new content
 * arrives, including in the case that broke the previous implementation: a long
 * session whose retained event window is full, so the COUNT stops changing while
 * content keeps arriving.
 */
class TailRevisionTest {

    @Test
    fun `revision changes when the transcript advances even though the count is pinned`() {
        val cappedCount = 600
        val before = tailRevision(4_939_818L, cappedCount, 120)
        val after = tailRevision(4_945_610L, cappedCount, 120)
        assertTrue(before != after, "a new byte of transcript must move the follower")
    }

    @Test
    fun `revision changes as a streaming answer grows without a new item`() {
        val a = tailRevision(1000L, 12, 40)
        val b = tailRevision(1000L, 12, 41)
        assertTrue(a != b, "each token must move the follower")
    }

    @Test
    fun `revision is stable when nothing changed, so it cannot fight the reader`() {
        assertEquals(tailRevision(1000L, 12, 40), tailRevision(1000L, 12, 40))
        assertEquals(tailRevision(null, 0, null), tailRevision(null, 0, null))
    }

    // ------------------------------------------------------------- tables

    /**
     * WHAT THE WALK SAW. The Conversation tab drew the answer's table as forty
     * lines of raw pipes — `| # | Item | What I need |`, `|---|---|---|` and all —
     * while the Screen tab beside it drew the same table as a box table, because
     * that one is Claude Code's own terminal rendering. "No tables" was a
     * deliberate line in this parser's contract, and a reader comparing the two
     * tabs reads it as the app being worse at its own job.
     */
    @Test
    fun `a pipe table with a header becomes a table block`() {
        val b = Markdown.parse(
            """
            | # | Item | What I need |
            |---|---|---|
            | 1 | Storage cleanup | Which tiers to execute |
            | 3 | Standby repair | Hands-on at the box |
            """.trimIndent()
        )
        val t = b.single() as MdBlock.Table
        assertTrue(t.header, "the delimiter row is what makes the first row a header")
        assertEquals(3, t.rows.size, "the delimiter row is not a row of data")
        assertEquals(listOf("#", "Item", "What I need"), t.rows[0].map { it.text })
        assertEquals(listOf("1", "Storage cleanup", "Which tiers to execute"), t.rows[1].map { it.text })
    }

    @Test
    fun `a table with no delimiter row is a table with no header`() {
        val t = Markdown.parse("| a | b |\n| c | d |").single() as MdBlock.Table
        assertEquals(false, t.header, "nothing said the first row was a heading")
        assertEquals(2, t.rows.size)
        assertEquals(listOf("c", "d"), t.rows[1].map { it.text })
    }

    @Test
    fun `a ragged row is padded out to the table's width`() {
        // A grid cannot draw a hole. Padding here rather than in the renderer is
        // what keeps the columns of the rows BELOW a short one lined up.
        val t = Markdown.parse("| a | b | c |\n| d |\n| e | f | g |").single() as MdBlock.Table
        assertEquals(listOf(3, 3, 3), t.rows.map { it.size })
        assertEquals(listOf("d", "", ""), t.rows[1].map { it.text })
    }

    @Test
    fun `an escaped pipe stays inside its cell`() {
        val t = Markdown.parse("| cmd | what |\n|---|---|\n| a \\| b | a pipe |").single() as MdBlock.Table
        assertEquals(2, t.rows[1].size, "the escaped pipe is not a cell boundary")
        assertEquals("a | b", t.rows[1][0].text, "and it is rendered as the pipe it is")
    }

    @Test
    fun `a lone pipe in prose is not a table`() {
        // The failure that matters: one sentence mentioning a pipe must not turn
        // a paragraph into a one-cell grid.
        assertTrue(Markdown.parse("Pipe the output | into grep and read it.").single() is MdBlock.Paragraph)
        // Nor does a single bar-wrapped line with nothing to make a table of.
        assertTrue(Markdown.parse("| not a table, just a line |").single() is MdBlock.Paragraph)
    }

    @Test
    fun `a cell keeps its inline markdown`() {
        val t = Markdown.parse("| a | b |\n|---|---|\n| **bold** | `code` |").single() as MdBlock.Table
        assertEquals("bold", t.rows[1][0].text, "the markers are the renderer's, not the reader's")
    }

    // ------------------------------------------------- plainInline (one-line rows)

    @Test
    fun `a list row's snippet loses the markers it cannot render`() {
        // Verbatim from the owner's Fold, 2026-09-15: the chats list drew
        // "**Creative is back online at 15:05.** Both players…" — the row is one
        // style, so the asterisks were simply the first two characters.
        assertEquals(
            "Creative is back online at 15:05. Both players (alex, sam) are on it.",
            Markdown.plainInline(
                "**Creative is back online at 15:05.** Both players (alex, sam) are on it.",
            ),
        )
        assertEquals(
            "MemPalace on memhost upgraded 3.7.0 → 3.8.0",
            Markdown.plainInline("**MemPalace on memhost upgraded 3.7.0 → 3.8.0**"),
        )
    }

    @Test
    fun `every inline marker the renderer knows is taken off`() {
        assertEquals("bold italic code struck", Markdown.plainInline("**bold** *italic* `code` ~~struck~~"))
        assertEquals("under", Markdown.plainInline("_under_"))
        // snake_case is not emphasis to the renderer, so it is not stripped here.
        assertEquals("a_b_c", Markdown.plainInline("a_b_c"))
    }

    /**
     * The other half of decision 44, and the one the contract missed:
     * `plainInline` DELEGATES to `inline`, so dropping the parens URL from a
     * transcript link drops it from every chats-list snippet too. That is the
     * wanted answer — a one-line row cannot carry a link, and a bare URL in it
     * was noise — but it is a second visible change, not a side effect.
     */
    @Test
    fun `a link keeps its label`() {
        assertEquals("the runbook", Markdown.plainInline("[the runbook](https://x/y)"))
        assertEquals("https://x/y", Markdown.plainInline("[https://x/y](https://x/y)"), "no point saying it twice")
    }

    @Test
    fun `a snippet is one line`() {
        assertEquals("first second", Markdown.plainInline("first\nsecond"))
        assertEquals("first second", Markdown.plainInline("  first\r\nsecond  "))
    }

    @Test
    fun `block syntax is left where it is`() {
        // One leading character reads as the punctuation it is; a stray `**` pair
        // reads as a bug. Only the inline markers are worth the risk of removing.
        assertEquals("# a heading", Markdown.plainInline("# a heading"))
        assertEquals("- a bullet", Markdown.plainInline("- a bullet"))
    }

    @Test
    fun `an unmatched marker survives, exactly as the renderer leaves it`() {
        // plainInline is inline() with the styling thrown away, so it cannot
        // disagree with what the transcript shows for the same text.
        assertEquals("2 ** 8 is 256", Markdown.plainInline("2 ** 8 is 256"))
    }

    /**
     * A chats row is ONE line drawn in ONE style, so a table in a snippet cannot
     * be a table there. Its first row is the label the rest of it hangs off.
     */
    @Test
    fun `a snippet flattens a table to its first row`() {
        assertEquals(
            "# · Item · What I need",
            Markdown.plainInline("| # | Item | What I need |\n|---|---|---|\n| 1 | Storage cleanup | Tier 1 |"),
        )
        assertEquals(
            "Before it. a · b After it.",
            Markdown.plainInline("Before it.\n| a | b |\n| c | d |\nAfter it."),
        )
    }

    /**
     * ⚠ AN INFO STRING MAY CARRY MORE THAN THE LANGUAGE. A lead's proposal opens
     * "```huginn-project 34f88e7484" — the project's tag after the word — and a
     * fence grammar that knew one word rendered the whole proposal as a
     * paragraph of JSON, which the owner read as a failed call.
     */
    @Test
    fun `a fence whose info string has a second word is still a fence`() {
        val b = Markdown.parse("Ready.\n\n```huginn-project 34f88e7484\n{\"type\":\"software\"}\n```\n")
        assertEquals(2, b.size)
        val code = b[1] as MdBlock.Code
        assertEquals("huginn-project", code.lang)
        assertEquals("34f88e7484", code.info)
        assertEquals("{\"type\":\"software\"}", code.code)
        assertEquals(null, (Markdown.parse("```bash\nls\n```").first() as MdBlock.Code).info)
    }

    /** A closing fence carries no words (CommonMark), so a fence line with a language inside a block is content. */
    @Test
    fun `a closing fence is bare, so a nested opening fence stays inside the block`() {
        val b = Markdown.parse("````markdown\n```bash\nls\n```\n````\n")
        assertEquals(1, b.size)
        assertEquals("```bash\nls\n```", (b[0] as MdBlock.Code).code)
    }
}
