package com.silencelen.huginn.desktop

import com.silencelen.huginn.mockTransport
import com.silencelen.huginn.data.HuginnClient
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
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The agent strip's poll, at the pace the strip can move.
 *
 * `agents?all=1` reads the tail of every agent the session ever ran (2.9 s, in
 * one synchronous pass on today's daemon, for a session with ~160), and the
 * strip asked for it every ten seconds for as long as the session was open,
 * idle or not. Idle - nothing on the strip live, the session not working - it
 * now waits for the history to say the session works again (or a minute).
 *
 * The poll interval is shortened through the controller's seam, so "it asked
 * again" shows within the test's time rather than ten seconds later.
 */
class AgentStripIdlePollTest {

    private val base = "https://h:1"

    private class Rig(val c: SessionController, val presence: Presence, val seen: MutableList<String>, val scope: CoroutineScope)

    @Volatile private var sessionState = "idle"

    private fun rig(agents: String): Rig {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val client = HuginnClient(
            baseUrlProvider = { base },
            tokenProvider = { "t" },
            transport = mockTransport { request ->
                val p = request.url.toString().removePrefix(base)
                seen += p
                val (code, body) = when {
                    "/transcript" in p -> HttpStatusCode.OK to
                        """{"events":[{"seq":1,"kind":"assistant","text":"hi"}],"nextOffset":10,"windowStart":0,""" +
                        """"state":"$sessionState","lastActivityTs":$NOW}"""
                    "/agents" in p -> HttpStatusCode.OK to agents
                    "/graph" in p -> HttpStatusCode.OK to """{"name":"s1","cursor":{"size":10,"agentBytes":0}}"""
                    "/overview" in p -> HttpStatusCode.OK to """{"name":"s1"}"""
                    "/screen" in p && "hash=" in p -> {
                        delay(60_000)
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
        val c = SessionController(client, "s1", presence, PaneLeaseHolder(client, scope), meta, scope, agentsPollMs = 100)
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

    private fun MutableList<String>.agentReads() = synchronized(this) { count { "/agents?all=1" in it } }

    @Test
    fun `an idle strip is not polled again until the session works`() {
        sessionState = "idle"
        val r = rig("""{"agents":[{"id":"a1","task":"done earlier","active":false,"status":"completed","updatedAt":${NOW - 600}}]}""")
        try {
            r.presence.setVisible(true)
            r.c.start()
            assertTrue(waitFor(5_000) { r.seen.agentReads() >= 1 }, "the strip was never read: ${r.seen}")
            Thread.sleep(1_500) // fifteen of the shortened polls
            assertEquals(1, r.seen.agentReads(), "an idle strip was read again and again")
            // The session starts working: the history's next read says so, and
            // the strip follows it again.
            sessionState = "running"
            assertTrue(
                waitFor(8_000) { r.seen.agentReads() >= 3 },
                "the strip did not follow the session back to work: ${r.seen.agentReads()} reads",
            )
        } finally {
            r.c.close()
            r.scope.cancel()
        }
    }

    @Test
    fun `a live agent keeps the strip polled while the session itself is idle`() {
        sessionState = "idle"
        val r = rig("""{"agents":[{"id":"a1","task":"still going","active":true,"updatedAt":$NOW}]}""")
        try {
            r.presence.setVisible(true)
            r.c.start()
            assertTrue(waitFor(5_000) { r.seen.agentReads() >= 1 }, "the strip was never read")
            assertTrue(waitFor(3_000) { r.seen.agentReads() >= 5 }, "a live agent's strip stopped: ${r.seen.agentReads()} reads")
        } finally {
            r.c.close()
            r.scope.cancel()
        }
    }

    private companion object {
        val NOW: Long = System.currentTimeMillis() / 1000
    }
}
