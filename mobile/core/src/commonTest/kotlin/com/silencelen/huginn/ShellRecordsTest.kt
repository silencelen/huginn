package com.silencelen.huginn

import com.silencelen.huginn.ui.ShellRecords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Claude Code's shell-mode records, from their verbatim text (2.1.280 – 2.1.316
 * transcripts on one host; paths and output replaced by placeholders, the
 * shapes untouched).
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class ShellRecordsTest {

    private fun out(stdout: String, stderr: String = "") = ShellRecords.Output(stdout, stderr)

    @Test
    fun `the command record is the command`() {
        // The daemon's own `shell: true` send (2.1.316) and a person's `! cmd`.
        assertEquals(ShellRecords.Input("pwd"), ShellRecords.parse("<bash-input>pwd</bash-input>"))
        assertEquals(ShellRecords.Input("cd /srv/app"), ShellRecords.parse("<bash-input> cd /srv/app</bash-input>"))
        // Not escaped on the way in: `&&` and `>` are literal in 48 and 27 records.
        assertEquals(
            ShellRecords.Input("cd /srv/app && ls -t *.deb | tail -n +3 > /dev/null"),
            ShellRecords.parse("<bash-input> cd /srv/app && ls -t *.deb | tail -n +3 > /dev/null</bash-input>"),
        )
        // The api-request copy ends in a newline; the record may be trimmed or not.
        assertEquals(ShellRecords.Input("pwd"), ShellRecords.parse("<bash-input>pwd</bash-input>\n"))
    }

    @Test
    fun `the output record is stdout and stderr, unescaped`() {
        assertEquals(out("/home/alex/projects/x"), ShellRecords.parse("<bash-stdout>/home/alex/projects/x</bash-stdout><bash-stderr></bash-stderr>"))
        assertEquals(
            out("MemoryMax=9663676416\nMemorySwapMax=536870912"),
            ShellRecords.parse("<bash-stdout>MemoryMax=9663676416\nMemorySwapMax=536870912</bash-stdout><bash-stderr></bash-stderr>"),
        )
        assertEquals(
            out("READBACK: ALLOW 192.0.2.10->192.0.2.20 && <ok>"),
            ShellRecords.parse("<bash-stdout>READBACK: ALLOW 192.0.2.10-&gt;192.0.2.20 &amp;&amp; &lt;ok&gt;</bash-stdout><bash-stderr></bash-stderr>"),
        )
        assertEquals(out("x &lt; y"), ShellRecords.parse("<bash-stdout>x &amp;lt; y</bash-stdout><bash-stderr></bash-stderr>"), "one pass")
        assertEquals(out("say \"hi\" it's"), ShellRecords.parse("<bash-stdout>say \"hi\" it's</bash-stdout><bash-stderr></bash-stderr>"))
    }

    @Test
    fun `stderr is kept apart, and escaped the same way`() {
        assertEquals(
            out("", "models/current -> v2-A\nrefusing: entries that belong to no part"),
            ShellRecords.parse(
                "<bash-stdout></bash-stdout><bash-stderr>models/current -&gt; v2-A\nrefusing: entries that belong to no part\n</bash-stderr>",
            ),
        )
        assertEquals(
            out("", "Shell cwd was reset to /srv/app"),
            ShellRecords.parse("<bash-stdout>(Bash completed with no output)</bash-stdout><bash-stderr>\nShell cwd was reset to /srv/app</bash-stderr>"),
        )
        // A command that could not start writes stderr alone.
        assertEquals(out("", "Command failed: exit 127"), ShellRecords.parse("<bash-stderr>Command failed: exit 127</bash-stderr>"))
    }

    @Test
    fun `nothing printed is empty, including the Bash tool's word for it`() {
        assertEquals(true, (ShellRecords.parse("<bash-stdout></bash-stdout><bash-stderr></bash-stderr>") as ShellRecords.Output).empty)
        assertEquals(true, (ShellRecords.parse("<bash-stdout>(Bash completed with no output)</bash-stdout><bash-stderr></bash-stderr>") as ShellRecords.Output).empty)
        assertEquals(false, out("", "x").empty)
    }

    @Test
    fun `stdout ends at the last closer, as Claude Code reads it`() {
        assertEquals(
            out("echo </bash-stdout> was printed"),
            ShellRecords.parse("<bash-stdout>echo </bash-stdout> was printed</bash-stdout><bash-stderr></bash-stderr>"),
        )
    }

    @Test
    fun `terminal noise becomes what a terminal showed`() {
        // CRLF from apt (2.1.312), a progress bar's carriage returns, colour codes.
        assertEquals(
            out("Processing triggers for libc-bin ...\nSetting up libgtk-3-bin ..."),
            ShellRecords.parse("<bash-stdout>Processing triggers for libc-bin ...\r\nSetting up libgtk-3-bin ...\r\n</bash-stdout><bash-stderr></bash-stderr>"),
        )
        assertEquals(out("100%"), ShellRecords.parse("<bash-stdout>10%\r50%\r100%</bash-stdout><bash-stderr></bash-stderr>"))
        assertEquals(out("ok done"), ShellRecords.parse("<bash-stdout>\u001B[32mok\u001B[0m done</bash-stdout><bash-stderr></bash-stderr>"))
        assertEquals(out("  indented\nline"), ShellRecords.parse("<bash-stdout>\n\n  indented\nline\n\n</bash-stdout><bash-stderr></bash-stderr>"))
    }

    @Test
    fun `a persisted output loses its wrapper`() {
        assertEquals(
            out("Output too large (41KB). Full output saved to: /tmp/x.txt\n\nPreview:\nline 1"),
            ShellRecords.parse(
                "<bash-stdout><persisted-output>Output too large (41KB). Full output saved to: /tmp/x.txt\n\nPreview:\nline 1</persisted-output></bash-stdout><bash-stderr></bash-stderr>",
            ),
        )
    }

    @Test
    fun `only a whole record is a record`() {
        assertNull(ShellRecords.parse("why does <bash-input>ls</bash-input> show up raw?"))
        assertNull(ShellRecords.parse("<bash-input>ls</bash-input> and then some"))
        assertNull(ShellRecords.parse("<bash-stdout>x</bash-stdout> trailing words"))
        assertNull(ShellRecords.parse("<bash-stdout>no closer"))
        assertNull(ShellRecords.parse("plain message"))
        assertNull(ShellRecords.parse(null))
        assertNull(ShellRecords.parse(""))
    }

    @Test
    fun `a quote takes the command, or the output stdout then stderr`() {
        assertEquals("pwd", ShellRecords.plain(ShellRecords.Input("pwd")))
        assertEquals("a\nb", ShellRecords.plain(out("a", "b")))
        assertEquals("b", ShellRecords.plain(out("", "b")))
    }

    @Test
    fun `a long output folds to its first lines and counts the rest`() {
        val forty = (1..40).joinToString("\n") { "line $it" }
        val folded = ShellRecords.fold(forty, 12)
        assertEquals((1..12).joinToString("\n") { "line $it" }, folded.shown)
        assertEquals(28, folded.hidden)
        assertEquals(ShellRecords.Folded("a\nb", 0), ShellRecords.fold("a\nb", 2), "exactly the cap is not folded")
        assertEquals(ShellRecords.Folded("a", 1), ShellRecords.fold("a\nb", 1))
        assertEquals(ShellRecords.Folded(forty, 0), ShellRecords.fold(forty, Int.MAX_VALUE))
        assertEquals(ShellRecords.Folded("", 0), ShellRecords.fold("", 3))
    }
}
