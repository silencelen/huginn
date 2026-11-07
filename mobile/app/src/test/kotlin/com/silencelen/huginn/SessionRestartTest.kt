package com.silencelen.huginn

import com.silencelen.huginn.data.RestartResult
import com.silencelen.huginn.data.Session
import com.silencelen.huginn.data.SessionRuntime
import com.silencelen.huginn.ui.SessionRestart
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sessions pick up updates (R5, owner 2026-11-07).
 *
 * A Claude Code process loads its MCP servers, hooks, settings and binary at
 * START; a session launched before a deploy keeps the old wiring until it is
 * restarted, and nothing on the phone said so — one session launched on 10-27
 * still had no `start_session` tool on 11-07. The daemon (appd 3.19) stamps
 * each row with a runtime cell; the phone's row marks a stale one, draws a
 * restarting one as starting up, and the session's menu offers the restart.
 *
 * The rules are pure ([SessionRestart]) because `HuginnViewModel` is an
 * `AndroidViewModel` and this host has no device; the wiring is pinned by
 * reading the sources, as `PhoneSurfaceVerbsTest` does for the project verbs.
 *
 * NOTE this module is on org.junit: (message, expected, actual).
 */
class SessionRestartTest {

    private fun session(attached: Int = 0, runtime: SessionRuntime? = null) =
        Session(name = "alex-1", attachedClients = attached, runtime = runtime)

    private val stale = SessionRuntime(stale = true, gen = "a1", current = "b2")
    private val cycling = SessionRuntime(stale = true, gen = "a1", current = "b2", restarting = true)
    private val fresh = SessionRuntime(stale = false, gen = "b2", current = "b2")

    // ------------------------------------------------------------- the row

    @Test
    fun `a stale row is marked restart to update, and update where the column is narrow`() {
        assertEquals("restart to update", SessionRestart.rowMark(stale))
        assertEquals("update", SessionRestart.rowMark(stale, short = true))
    }

    @Test
    fun `a row being restarted reads restarting and carries no stale mark`() {
        assertTrue(SessionRestart.restarting(cycling))
        assertEquals("restarting", SessionRestart.titleWord("idle", backgroundWork = false, runtime = cycling))
        assertNull("the restart is the answer to stale; asking again is noise", SessionRestart.rowMark(cycling))
    }

    @Test
    fun `a row that is neither, or from a daemon without the cell, carries nothing`() {
        assertNull(SessionRestart.rowMark(fresh))
        assertFalse(SessionRestart.restarting(fresh))
        // ABSENT IS NOT STALE: an older daemon must not draw every row as behind.
        assertNull(SessionRestart.rowMark(null))
        assertFalse(SessionRestart.restarting(null))
        assertEquals("waiting", SessionRestart.titleWord("idle", backgroundWork = false, runtime = null))
    }

    @Test
    fun `the title word keeps the row's older rules under the restarting one`() {
        // "background work" over a non-running session with shells going (owner, 2026-10-13).
        assertEquals("background work", SessionRestart.titleWord("idle", backgroundWork = true, runtime = fresh))
        assertEquals("working", SessionRestart.titleWord("running", backgroundWork = true, runtime = null))
        assertEquals("needs you", SessionRestart.titleWord("attention", backgroundWork = false, runtime = stale))
        // ...and restarting wins over all of them: the session is being brought up.
        assertEquals("restarting", SessionRestart.titleWord("running", backgroundWork = true, runtime = cycling))
    }

    // ------------------------------------------------------------- the verb

    @Test
    fun `the three answers to a restart read differently`() {
        val now = SessionRestart.words("alex-1", RestartResult(queued = true))
        val held = SessionRestart.words("alex-1", RestartResult(queued = true, blockedBy = "running"))
        val again = SessionRestart.words("alex-1", RestartResult(queued = true, reason = "restarting"))
        assertEquals("Restarting alex-1…", now)
        assertTrue("the hold must be named: $held", held.contains("waiting: its turn to end"))
        assertTrue("already under way is not an error: $again", again.contains("already restarting"))
        assertEquals(3, setOf(now, held, again).size)
    }

    @Test
    fun `every hold word the daemon can send has words, and an unknown one passes through`() {
        for (w in listOf("running", "dialog", "draft", "background", "attached", "load")) {
            val words = SessionRestart.holdWords(w)
            assertTrue("$w has no reader's words", words.isNotBlank() && words != w)
        }
        assertEquals("quorum", SessionRestart.holdWords("quorum"))
    }

    @Test
    fun `an attached terminal is asked about, an unattached session is not`() {
        assertTrue(SessionRestart.needsConfirm(session(attached = 1, runtime = stale)))
        assertFalse(SessionRestart.needsConfirm(session(attached = 0, runtime = stale)))
        assertFalse("no row, no question — the daemon's own hold answers", SessionRestart.needsConfirm(null))
    }

    @Test
    fun `the verb is offered only on a row the daemon stamped`() {
        assertTrue(SessionRestart.offered(session(runtime = fresh)))
        assertTrue("stale or not, the restart is a verb", SessionRestart.offered(session(runtime = stale)))
        assertFalse("an older daemon has no route: a verb whose only outcome is a 404", SessionRestart.offered(session(runtime = null)))
        assertFalse(SessionRestart.offered(null))
    }

    // ------------------------------------------------------------- the wiring

    private fun mobileRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun read(relative: String): String {
        val f = File(mobileRoot(), "app/src/main/kotlin/com/silencelen/huginn/$relative")
        assertTrue("$relative not found at ${f.absolutePath}", f.isFile)
        return f.readText()
    }

    @Test
    fun `the session row draws the mark on its meta line and the restarting state on its title line`() {
        val row = read("ui/SessionsScreen.kt").substringAfter("private fun SessionRow(")
        assertTrue("the row does not read the runtime mark", row.contains("SessionRestart.rowMark(s.runtime, short = compact)"))
        assertTrue("the state word ignores restarting", row.contains("SessionRestart.titleWord(s.state, backgroundWork, s.runtime)"))
        assertTrue("a restarting row must breathe like a starting one", row.contains("if (restarting || s.state == \"running\" || backgroundWork)"))
        // The mark is on the EXISTING meta line (no new line, no new colour), in
        // the state language's info colour.
        assertTrue("the mark is not in the state language", row.contains("stateColor(StateKind.INFO)"))
        val screen = read("ui/SessionsScreen.kt")
        assertTrue("the two-pane column does not take the short form", screen.contains("compact = twoPane,"))
    }

    @Test
    fun `the session's action menu offers the restart and routes the attached case through the question`() {
        val text = read("MainActivity.kt")
        val menu = text.substringAfter("is Dest.SessionView -> buildList {").substringBefore("is Dest.Project ->")
        assertTrue("no restart verb on the session surface", menu.contains("SessionRestart.MENU_LABEL"))
        assertTrue("the verb is offered on a daemon that has no route", menu.contains("SessionRestart.offered(row)"))
        assertTrue(
            "an attached terminal is not asked about",
            menu.contains("if (com.silencelen.huginn.ui.SessionRestart.needsConfirm(row)) restartTarget = d.name"),
        )
        assertTrue("an unattached session must not be forced", menu.contains("else vm.restartSession(d.name)"))
        // The question's dialog sends force, and only it does.
        assertTrue("the dialog does not force past the attached hold", text.contains("vm.restartSession(name, force = true)"))
        assertEquals("force is sent from exactly one place", 1, Regex("""force = true""").findAll(text).count())
        assertTrue(text.contains("SessionRestart.ATTACHED_QUESTION"))
    }

    @Test
    fun `the view model sends the restart and says what the daemon answered`() {
        val vm = read("ui/HuginnViewModel.kt")
        val fn = vm.substringAfter("fun restartSession(name: String, force: Boolean = false)").substringBefore("\n    }\n")
        assertTrue("the view model has no restart verb", fn.isNotEmpty())
        assertTrue("force is not carried to the client", fn.contains("client.restartSession(name, force = force)"))
        assertTrue("the answer is not read into words", fn.contains("SessionRestart.words(name, r)"))
        assertTrue("errors must land on the usual surface", fn.contains(".onFailure { _toast.value = errText(it) }"))
        assertTrue("the row should re-read so `restarting` shows", fn.contains("refreshSessions()"))
    }
}
