package com.silencelen.huginn.desktop

import com.silencelen.huginn.mockTransport
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.SessionMeta
import com.silencelen.huginn.data.SessionMetaSaver
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Opening a session reads its HISTORY first.
 *
 * The strip's `agents?all=1` and the map used to go out beside the transcript
 * read, and a daemon that answers `agents?all=1` in one synchronous pass (2.9 s
 * for a session that had run ~160 subagents) answered the history only after
 * it. The strip and the map now wait for the history's first answer, a page or
 * a failure; the pane, which carries the question, does not.
 *
 * Real dispatchers, like [OverviewMetaArrivalTest]: the loops run on the
 * controller's own scope. The transcript read is HELD, so "nothing else went
 * while it was on its way" is a fact about the code, not about timing.
 */
class SessionHistoryFirstTest {

    private val base = "https://h:1"

    private class Rig(val c: SessionController, val presence: Presence, val seen: MutableList<String>, val scope: CoroutineScope)

    private fun rig(history: suspend () -> Pair<HttpStatusCode, String>): Rig {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val client = HuginnClient(
            baseUrlProvider = { base },
            tokenProvider = { "t" },
            transport = mockTransport { request ->
                val p = request.url.toString().removePrefix(base)
                seen += p
                val (code, body) = when {
                    "/transcript" in p -> history()
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
        val c = SessionController(client, "s1", presence, PaneLeaseHolder(client, scope), meta, scope)
        return Rig(c, presence, seen, scope)
    }

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }

    private fun MutableList<String>.anyOf(vararg parts: String) = synchronized(this) { any { p -> parts.any { it in p } } }

    private val page = """{"events":[{"seq":1,"kind":"assistant","text":"hello"}],"nextOffset":10,"windowStart":0}"""

    @Test
    fun `the strip and the map wait for the history`() {
        val answer = CompletableDeferred<Unit>()
        val r = rig {
            answer.await()
            HttpStatusCode.OK to page
        }
        try {
            r.presence.setVisible(true)
            r.c.start()
            assertTrue(waitFor(5_000) { r.seen.anyOf("/transcript") }, "the history was never asked for: ${r.seen}")
            // The history is on its way. Neither the strip nor the map may go
            // before it answers, however long that is.
            val early = waitFor(1_500) { r.seen.anyOf("/agents", "/graph", "/overview") }
            assertFalse(early, "the strip or the map went out before the history answered: ${r.seen}")
            answer.complete(Unit)
            assertTrue(
                waitFor(5_000) { r.seen.anyOf("/agents") && r.seen.anyOf("/graph") },
                "the strip and the map never followed the history: ${r.seen}",
            )
        } finally {
            answer.complete(Unit)
            r.c.close()
            r.scope.cancel()
        }
    }

    /** A session with no transcript yet answers 409, and that is an answer: the rest still loads. */
    @Test
    fun `a history that fails still lets the strip and the map load`() {
        val r = rig { HttpStatusCode.Conflict to """{"error":"no transcript recorded for this session yet"}""" }
        try {
            r.presence.setVisible(true)
            r.c.start()
            assertTrue(
                waitFor(5_000) { r.seen.anyOf("/agents") && r.seen.anyOf("/graph") },
                "a failed history held the strip and the map back: ${r.seen}",
            )
        } finally {
            r.c.close()
            r.scope.cancel()
        }
    }
}
