package com.silencelen.huginn.desktop

import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.RestartResult
import com.silencelen.huginn.data.Session
import com.silencelen.huginn.data.SessionRuntime
import com.silencelen.huginn.desktop.ui.common.HuginnMenuItem
import com.silencelen.huginn.desktop.ui.common.RestartWords
import com.silencelen.huginn.desktop.ui.common.SessionVerbs
import com.silencelen.huginn.desktop.ui.common.labelsOf
import com.silencelen.huginn.desktop.ui.common.sessionMenu
import com.silencelen.huginn.ui.VerbTone
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Restart to pick up updates" (R5): the row's stale mark, the menu's verb, and
 * the hold the daemon answers with.
 *
 * A Claude Code process loads MCP servers, hooks, settings and its binary at
 * START, so a session launched before a deploy keeps the old wiring until it
 * is restarted — one launched on 10-27 still had no `start_session` tool on
 * 11-07. The daemon marks such rows (`runtime.stale`) and offers a restart that
 * is a send-queue job: queued at once or held on a reason. The desktop's half
 * is asserted here with the daemon calls arriving through the store's seams;
 * the store is never started and nothing reaches a daemon.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class SessionRestartTest {

    private val dirs = mutableListOf<File>()
    private val scopes = mutableListOf<CoroutineScope>()

    private fun store(): AppStore {
        val dir = Files.createTempDirectory("huginn-restart-test").toFile()
        dirs += dir
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scopes += scope
        return AppStore(DesktopSettings(File(dir, "settings.json")), Presence(), scope)
    }

    @AfterTest
    fun cleanup() {
        scopes.forEach { it.cancel() }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun session(name: String = "s1", runtime: SessionRuntime? = null, attached: Int = 0) =
        Session(name = name, state = "idle", runtime = runtime, attachedClients = attached)

    private fun verbs(restart: ((Session) -> Unit)? = {}) = SessionVerbs(
        open = {}, rename = {}, interrupt = {}, copyName = {}, compact = {},
        softEnd = {}, kill = {}, restart = restart,
    )

    private val plain = listOf("Open", "Rename…", "Interrupt (Esc)", "Copy session name", "Compact context", "Wrap up", "Kill session")

    // ---------------------------------------------------------------- menu

    @Test
    fun `the verb sits beside Compact context, only on a daemon that speaks runtime, never while cycling`() {
        // No runtime cell: an older daemon, which has no restart route either —
        // a verb whose only outcome is a 404 is worse than none.
        assertEquals(plain, labelsOf(sessionMenu(session(), emptySet(), verbs())))
        // The daemon speaks runtime: the verb, plain, after the other maintenance verb.
        val stale = session(runtime = SessionRuntime(stale = true))
        assertEquals(
            listOf("Open", "Rename…", "Interrupt (Esc)", "Copy session name", "Compact context", RestartWords.MENU, "Wrap up", "Kill session"),
            labelsOf(sessionMenu(stale, emptySet(), verbs())),
        )
        val item = sessionMenu(stale, emptySet(), verbs()).filterIsInstance<HuginnMenuItem>().first { it.label == RestartWords.MENU }
        assertEquals(VerbTone.PLAIN, item.tone, "a restart resumes the conversation in place; it loses nothing")
        // A fresh row is offered it too: whether a restart is needed is the daemon's call.
        assertTrue(labelsOf(sessionMenu(session(runtime = SessionRuntime(stale = false)), emptySet(), verbs())).contains(RestartWords.MENU))
        // Already cycling: a second restart is a 409, so the verb is not offered.
        assertFalse(labelsOf(sessionMenu(session(runtime = SessionRuntime(stale = true, restarting = true)), emptySet(), verbs())).contains(RestartWords.MENU))
        // No wiring (a test), no verb; a multi-selection has no restart.
        assertEquals(plain, labelsOf(sessionMenu(stale, emptySet(), verbs(restart = null))))
        assertFalse(labelsOf(sessionMenu(stale.copy(name = "b"), setOf("a", "b"), verbs())).contains(RestartWords.MENU))
    }

    @Test
    fun `the menu hands the row to the verb, which decides on the attached confirm`() {
        var asked: Session? = null
        val row = session(runtime = SessionRuntime(stale = true), attached = 1)
        sessionMenu(row, emptySet(), verbs { asked = it }).first { it.label == RestartWords.MENU }.onClick()
        assertEquals(row, asked, "the whole row, so the shell can read attachedClients before sending")
        assertTrue(RestartWords.offered(row))
        assertFalse(RestartWords.offered(session()))
        assertFalse(RestartWords.offered(session(runtime = SessionRuntime(restarting = true))))
    }

    // --------------------------------------------------------------- words

    @Test
    fun `the stale tip names the versions when they moved and says where the verb is`() {
        val moved = RestartWords.staleTip(SessionRuntime(stale = true, claude = "2.1.300", currentClaude = "2.1.316"))
        assertTrue(moved.contains("claude 2.1.300 → 2.1.316"), moved)
        assertTrue(moved.contains(RestartWords.MENU), moved)
        val same = RestartWords.staleTip(SessionRuntime(stale = true, claude = "2.1.316", currentClaude = "2.1.316"))
        assertFalse(same.contains("→"), "same binary: the MCP config or hooks moved, not the version — say nothing false: $same")
        assertFalse(RestartWords.staleTip(SessionRuntime(stale = true)).contains("→"))
    }

    @Test
    fun `the hold tip speaks the queued-send words for each reason the route has`() {
        assertEquals("Restart queued · due now", RestartWords.holdTip(null, alreadyQueued = false))
        assertEquals("Restart queued · will run when Claude finishes its turn", RestartWords.holdTip("running", false))
        assertEquals("Restart queued · a dialog is open on the screen", RestartWords.holdTip("dialog", false))
        assertEquals("Restart queued · unsent text in the live view", RestartWords.holdTip("draft", false))
        assertEquals("Restart queued · background work is still running", RestartWords.holdTip("background", false))
        assertTrue(RestartWords.holdTip("attached", false).contains("attached") && RestartWords.holdTip("attached", false).contains(RestartWords.MENU))
        assertEquals("Restart queued · waiting for the host to quieten", RestartWords.holdTip("load", false))
        assertEquals("Restart queued · waiting on something-new", RestartWords.holdTip("something-new", false), "a reason this client does not know is still said")
        assertEquals("Restart already under way", RestartWords.holdTip("running", alreadyQueued = true))
    }

    // --------------------------------------------------------------- store

    @Test
    fun `a 202 records the hold with what the daemon waits on, and force travels only when asked`() = runBlocking {
        val s = store()
        val calls = mutableListOf<Pair<String, Boolean>>()
        s.restartSession("alpha") { n, f -> calls += n to f; RestartResult(queued = true, blockedBy = "running") }
        s.restartSession("beta", force = true) { n, f -> calls += n to f; RestartResult(queued = true) }
        assertEquals(listOf("alpha" to false, "beta" to true), calls)
        assertEquals("running", s.restartHolds.value["alpha"]?.blockedBy)
        assertNull(s.restartHolds.value["beta"]?.blockedBy, "due now")
        assertFalse(s.restartHolds.value.getValue("alpha").alreadyQueued)
        assertNull(s.error.value, "nothing went wrong")
    }

    @Test
    fun `409 restarting is one already under way - a hold that says so, not a fault`() = runBlocking {
        val s = store()
        s.restartSession("alpha") { _, _ -> RestartResult(queued = true, reason = "restarting") }
        assertTrue(s.restartHolds.value.getValue("alpha").alreadyQueued)
        assertNull(s.error.value)
    }

    @Test
    fun `a refusal is the daemon's own sentence on the status line, and no hold`() = runBlocking {
        val s = store()
        s.restartSession("alpha") { _, _ -> throw HuginnClient.HuginnException(404, "no such session") }
        assertTrue(s.restartHolds.value.isEmpty())
        assertTrue(s.error.value.orEmpty().contains("no such session"), "verbatim: ${s.error.value}")
    }

    @Test
    fun `the row ends its own hold - cycling, come back fresh, or gone`() = runBlocking {
        val s = store()
        val rt = SessionRuntime(stale = true, gen = "a1", current = "b2")
        s.refreshSessions { listOf(session("alpha", rt), session("beta", rt), session("gamma", rt)) }
        for (n in listOf("alpha", "beta", "gamma")) s.restartSession(n) { _, _ -> RestartResult(queued = true, blockedBy = "running") }
        assertEquals(setOf("alpha", "beta", "gamma"), s.restartHolds.value.keys)

        // Still held on the next tick: the rows have not moved.
        s.refreshSessions { listOf(session("alpha", rt), session("beta", rt), session("gamma", rt)) }
        assertEquals(setOf("alpha", "beta", "gamma"), s.restartHolds.value.keys, "a hold outlives a poll that changed nothing")

        // alpha is cycling (the row says so now), beta came back fresh, gamma is gone.
        s.refreshSessions {
            listOf(
                session("alpha", rt.copy(restarting = true)),
                session("beta", SessionRuntime(stale = false, gen = "b2", current = "b2")),
            )
        }
        assertTrue(s.restartHolds.value.isEmpty(), "every hold ended for its own reason: ${s.restartHolds.value}")
    }

    @Test
    fun `a fresh row that was restarted anyway keeps its hold until the cycle shows`() = runBlocking {
        // Asked on a row that was NOT stale (the daemon decides whether a restart
        // is needed): "came back fresh" cannot end it, only the cycle can.
        val s = store()
        val fresh = SessionRuntime(stale = false)
        s.refreshSessions { listOf(session("alpha", fresh)) }
        s.restartSession("alpha") { _, _ -> RestartResult(queued = true) }
        s.refreshSessions { listOf(session("alpha", fresh)) }
        assertEquals(setOf("alpha"), s.restartHolds.value.keys)
        s.refreshSessions { listOf(session("alpha", fresh.copy(restarting = true))) }
        assertTrue(s.restartHolds.value.isEmpty())
    }
}
