package com.silencelen.huginn.desktop

import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.SessionMeta
import com.silencelen.huginn.data.SessionMetaSaver
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Arriving on Overview must show the server's goals and notes (2026-10-02).
 *
 * 3.9.0 started the overview loop from session open, so by the time somebody
 * clicks Overview the graph cursor is already stored and an idle session's
 * graph poll answers `{"unchanged":true}` with no meta. The arrival header was
 * the only delivery left, and it was dropped (the saver's open() stamped a
 * generation newer than the one captured in the same breath): the editors
 * stayed blank 20/20 times and the first keystroke PATCHed the typed text over
 * the saved goals.
 *
 * Real dispatchers and the real saver lane on purpose: the race is between the
 * caller's thread and the lane, and a test dispatcher serialises it away.
 */
class OverviewMetaArrivalTest {

    private val base = "http://h:1"

    private class Rig(
        val c: SessionController,
        val presence: Presence,
        val seen: MutableList<String>,
    )

    private fun rig(
        name: String,
        meta: SessionMetaSaver,
        scope: CoroutineScope,
        headerFails: () -> Boolean = { false },
    ): Rig {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val metaJson = """{"goals":"SERVER GOALS","notes":"SERVER NOTES"}"""
        val client = HuginnClient(
            baseUrlProvider = { base },
            tokenProvider = { "t" },
            engine = MockEngine { request ->
                val p = request.url.toString().removePrefix(base)
                seen += p
                val (code, body) = when {
                    "/overview" in p && headerFails() -> HttpStatusCode.ServiceUnavailable to """{"error":"busy"}"""
                    "/overview" in p -> HttpStatusCode.OK to """{"name":"$name","meta":$metaJson}"""
                    // A stored cursor on an idle session: nothing new, and no meta.
                    "/graph?" in p -> HttpStatusCode.OK to """{"unchanged":true}"""
                    "/graph" in p -> HttpStatusCode.OK to
                        """{"name":"$name","cursor":{"size":10,"agentBytes":0},"meta":$metaJson}"""
                    else -> HttpStatusCode.NotFound to """{"error":"nope"}"""
                }
                respond(body, code, headersOf("Content-Type", listOf("application/json")))
            },
        )
        val presence = Presence()
        val c = SessionController(client, name, presence, PaneLeaseHolder(client, scope), meta, scope)
        return Rig(c, presence, seen)
    }

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }

    /** Session open, the background pass stores its cursor, THEN the person clicks Overview. */
    private fun arriveAfterBackgroundPass(r: Rig) {
        r.presence.setVisible(true)
        r.c.start()
        check(waitFor(5000) { r.seen.any { "/graph" in it } }) { "no background graph poll" }
        Thread.sleep(100)
        r.c.openTab(SessionTab.OVERVIEW)
    }

    @Test
    fun `first visit to Overview shows the saved goals, and typing appends to them`() {
        val saves = Collections.synchronizedList(mutableListOf<String>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val meta = SessionMetaSaver(scope, { n, g, no -> saves += "$n goals=$g notes=$no"; SessionMeta(g ?: "", no ?: "") })
        try {
            // A fresh session name each run is a first visit each run: the saver
            // is app-level and only (re)opens for a session it is not holding.
            repeat(5) { i ->
                saves.clear()
                val r = rig("s$i", meta, scope)
                arriveAfterBackgroundPass(r)
                waitFor(3000) { meta.session.value == "s$i" && meta.goals.value == "SERVER GOALS" }
                assertEquals("SERVER GOALS", meta.goals.value, "run $i: editors blank, paths=${r.seen}")
                assertEquals("SERVER NOTES", meta.notes.value, "run $i")
                meta.setGoals(meta.goals.value + " +more")
                assertTrue(waitFor(3000) { saves.isNotEmpty() }, "run $i: nothing saved")
                assertEquals(listOf("s$i goals=SERVER GOALS +more notes=null"), saves.toList(), "run $i")
                r.c.close()
            }
        } finally {
            scope.cancel()
        }
    }

    /**
     * The header is the arrival's only meta when the cursor answers unchanged, so
     * a header that FAILS must not leave the editors blank until the transcript
     * next grows: the graph poll goes uncursored until the meta has arrived.
     */
    @Test
    fun `a failed arrival header still fills the editors from the graph`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val meta = SessionMetaSaver(scope, { _, g, no -> SessionMeta(g ?: "", no ?: "") })
        try {
            var arrived = false
            val r = rig("solo", meta, scope, headerFails = { arrived })
            r.presence.setVisible(true)
            r.c.start()
            check(waitFor(5000) { r.seen.any { "/graph" in it } }) { "no background graph poll" }
            Thread.sleep(100)
            arrived = true
            r.c.openTab(SessionTab.OVERVIEW)
            waitFor(4000) { meta.goals.value == "SERVER GOALS" }
            assertEquals("SERVER GOALS", meta.goals.value, "paths=${r.seen}")
            r.c.close()
        } finally {
            scope.cancel()
        }
    }
}
