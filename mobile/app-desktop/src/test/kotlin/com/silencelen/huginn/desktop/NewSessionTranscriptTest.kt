package com.silencelen.huginn.desktop

import com.silencelen.huginn.mockTransport
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.Session
import com.silencelen.huginn.data.SessionMeta
import com.silencelen.huginn.data.SessionMetaSaver
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A new session's conversation appears within a tick of its first prompt.
 *
 * ⚠ THE BUG (desktop 2.10.0, 2026-11-03, a real core and daemon): a session made
 * from New session, its first prompt sent, the daemon serving the transcript
 * within seconds — and the Conversation tab still "No conversation yet" for about
 * 70 s, because every 409 before the prompt had pushed the tail further up the
 * backoff ladder. The rule is [TranscriptRetry] ([TranscriptRetryTest]); this is
 * the loop that has to obey it, so it runs the real loops on the controller's own
 * dispatcher (like [SessionHistoryFirstTest]) and measures in wall-clock time,
 * with bounds a tick wide on one side and a ladder step away on the other.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class NewSessionTranscriptTest {

    private val base = "https://h:1"

    private class Rig(
        val c: SessionController,
        val presence: Presence,
        val reads: MutableList<Long>,
        val scope: CoroutineScope,
    )

    /**
     * @param written whether the transcript is on disk yet: until it is, the
     *   daemon answers the read with its 409.
     */
    private fun rig(rows: MutableStateFlow<List<Session>>, written: () -> Boolean): Rig {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val reads = Collections.synchronizedList(mutableListOf<Long>())
        val client = HuginnClient(
            baseUrlProvider = { base },
            tokenProvider = { "t" },
            transport = mockTransport { request ->
                val p = request.url.toString().removePrefix(base)
                val (code, body) = when {
                    "/transcript" in p -> {
                        reads += System.currentTimeMillis()
                        if (written()) HttpStatusCode.OK to PAGE else HttpStatusCode.Conflict to NOT_YET
                    }
                    "/agents" in p -> HttpStatusCode.OK to """{"agents":[]}"""
                    "/graph" in p -> HttpStatusCode.OK to """{"name":"s1","cursor":{"size":10,"agentBytes":0}}"""
                    "/overview" in p -> HttpStatusCode.OK to """{"name":"s1"}"""
                    "/screen" in p && "hash=" in p -> {
                        delay(60_000) // a parked long poll; the scope ends it
                        HttpStatusCode.OK to """{"unchanged":true,"hash":"h1"}"""
                    }
                    "/screen" in p -> HttpStatusCode.OK to """{"hash":"h1","lines":["$ "]}"""
                    else -> HttpStatusCode.NotFound to """{"error":"nope"}"""
                }
                respond(body, code, headersOf("Content-Type", listOf("application/json")))
            },
        )
        val presence = Presence()
        val meta = SessionMetaSaver(scope, { _, _, _ -> SessionMeta() })
        val c = SessionController(client, "s1", presence, PaneLeaseHolder(client, scope), meta, scope, sessions = rows)
        return Rig(c, presence, reads, scope)
    }

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }

    /** Opens the view (visible, loops started) for [block], and always closes it. */
    private fun Rig.opened(block: Rig.() -> Unit) {
        try {
            presence.setVisible(true)
            c.start()
            block()
        } finally {
            c.close()
            scope.cancel()
        }
    }

    @Test
    fun `the first prompt's transcript lands within a tick of being written`() {
        val written = AtomicBoolean(false)
        val rows = MutableStateFlow(listOf(Session(name = "s1", state = "idle", claudeSessionId = "cs-1")))
        rig(rows) { written.get() }.opened {
            // Two reads before the prompt: the tail is now two failures deep, where
            // the ladder waits 10 s.
            assertTrue(waitFor(15_000) { reads.size >= 2 }, "the tail never retried: ${reads.size} reads")
            written.set(true)
            val at = System.currentTimeMillis()
            assertTrue(
                waitFor(4_500) { c.page.value != null },
                "the conversation was still empty ${System.currentTimeMillis() - at} ms after the transcript was written",
            )
        }
    }

    /** The other half: a plain shell is not asked again before the ladder's first step. */
    @Test
    fun `a plain shell is not hammered`() {
        val rows = MutableStateFlow(listOf(Session(name = "s1")))
        rig(rows) { false }.opened {
            assertTrue(waitFor(5_000) { reads.isNotEmpty() }, "the tail never read")
            Thread.sleep(3_500)
            assertEquals(1, reads.size, "a plain shell was asked again inside the ladder's first 5 s")
        }
    }

    /**
     * A view opened before its Claude started (the New session dialog opens it at
     * once) is on the ladder; the list saying Claude is up ends that wait.
     */
    @Test
    fun `a laddered wait ends when the list says Claude is up`() {
        val rows = MutableStateFlow(listOf(Session(name = "s1")))
        rig(rows) { false }.opened {
            assertTrue(waitFor(5_000) { reads.isNotEmpty() }, "the tail never read")
            Thread.sleep(300)
            assertEquals(1, reads.size, "asked again before anything changed")
            rows.value = listOf(Session(name = "s1", state = "idle", claudeSessionId = "cs-1"))
            val at = System.currentTimeMillis()
            assertTrue(
                waitFor(2_000) { reads.size >= 2 },
                "the tail sat out the ladder: no read ${System.currentTimeMillis() - at} ms after Claude came up",
            )
        }
    }

    private companion object {
        const val NOT_YET = """{"error":"no transcript recorded for this session yet — the Claude hook fires on the first prompt"}"""
        const val PAGE = """{"events":[{"seq":1,"kind":"assistant","text":"hello"}],""" +
            """"nextOffset":10,"windowStart":0,"claudeSessionId":"cs-1"}"""
    }
}
