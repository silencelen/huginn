package com.silencelen.huginn.desktop

import com.silencelen.huginn.data.DraftBook
import com.silencelen.huginn.data.HuginnClient
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
import kotlin.test.assertTrue

/**
 * "Kill 5 sessions" and "Wrap up 5 sessions" act on all five.
 *
 * The daemon refuses one session for ordinary reasons: it ended while the
 * confirm dialog was open (404), it is waiting on a question or is a plain
 * shell (409). That refusal is one session's answer, not the batch's, so the
 * sessions after it still get the kill or the wrap-up, and the status line says
 * WHICH session refused.
 *
 * The daemon calls arrive through the store's seams; the store is never
 * started and its route book is empty, so nothing here can reach a daemon.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class SessionBatchTest {

    private val dirs = mutableListOf<File>()
    private val scopes = mutableListOf<CoroutineScope>()

    private fun store(): AppStore {
        val dir = Files.createTempDirectory("huginn-batch-test").toFile()
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

    private val five = listOf("k1", "k2", "k3", "k4", "k5")

    @Test
    fun `a session that is already gone does not keep the rest of a kill running`() = runBlocking {
        val s = store()
        five.forEach { s.drafts.set(DraftBook.sessionKey(it), "half a thought for $it") }
        val killed = mutableListOf<String>()

        val e = runCatching {
            s.killSessions(five) { n ->
                if (n == "k2") throw HuginnClient.HuginnException(404, "no such session")
                killed += n
            }
        }.exceptionOrNull()

        assertEquals(listOf("k1", "k3", "k4", "k5"), killed, "every session after the refusal still gets the kill")
        assertTrue(e is IllegalStateException, "one summary for the batch, got $e")
        assertTrue("k2: no such session" in e?.message.orEmpty(), "the refusal names its session: ${e?.message}")
        for (n in killed) assertEquals("", s.drafts[DraftBook.sessionKey(n)], "$n is gone, and its draft with it")
        assertEquals("half a thought for k2", s.drafts[DraftBook.sessionKey("k2")], "a session not killed keeps its draft")
    }

    @Test
    fun `the open session is closed only when it was really killed`() = runBlocking {
        val s = store()
        s.openSession("k3")
        runCatching { s.killSessions(listOf("k1", "k3")) { n -> if (n == "k1") error("tmux did not answer") } }
        assertEquals(null, s.sessionName.value, "k3 was killed, so its pane closes")

        s.openSession("k4")
        runCatching { s.killSessions(listOf("k4")) { throw HuginnClient.HuginnException(503, "tmux did not answer") } }
        assertEquals("k4", s.sessionName.value, "k4 is still running, so its pane stays")
    }

    @Test
    fun `a session waiting on a question does not keep the wrap-up from the rest`() = runBlocking {
        val s = store()
        val asked = mutableListOf<String>()

        val e = runCatching {
            s.softEndSessions(five) { n ->
                asked += n
                if (n == "k2") throw HuginnClient.HuginnException(409, "answer the waiting question first, then end the session")
            }
        }.exceptionOrNull()

        assertEquals(five, asked, "every session was asked to wrap up")
        assertTrue(e is IllegalStateException, "one summary for the batch, got $e")
        assertTrue(
            "k2: answer the waiting question first" in e?.message.orEmpty(),
            "the refusal names its session: ${e?.message}",
        )
    }

    @Test
    fun `a batch with no refusal says nothing`() = runBlocking {
        val s = store()
        s.killSessions(five) { }
        s.softEndSessions(five) { }
    }
}
