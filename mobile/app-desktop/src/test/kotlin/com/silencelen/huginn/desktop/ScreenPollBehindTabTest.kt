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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The pane poll behind the Screen tab.
 *
 * appd's `/screen` long poll answers as soon as the pane's hash differs, and a
 * working session's pane (spinner, timer) differs on every tick, so a poll that
 * asks again at once captures the pane several times a second for as long as
 * the session is open — 3.6 a second, measured, on the Conversation tab, where
 * the pane is not drawn. Behind the Screen tab it now waits a second after each
 * new frame; on the Screen tab it still follows every frame.
 *
 * Every answer here is a NEW frame, the busy pane. Gaps are measured between
 * request arrivals, whose lower bound is the wait itself: a slow machine only
 * makes them longer.
 */
class ScreenPollBehindTabTest {

    private val base = "https://h:1"

    private class Rig(val c: SessionController, val presence: Presence, val screens: MutableList<Long>, val scope: CoroutineScope)

    private fun rig(): Rig {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val screens = Collections.synchronizedList(mutableListOf<Long>())
        val frame = AtomicInteger()
        val client = HuginnClient(
            baseUrlProvider = { base },
            tokenProvider = { "t" },
            transport = mockTransport { request ->
                val p = request.url.toString().removePrefix(base)
                val (code, body) = when {
                    "/screen" in p -> {
                        screens += System.nanoTime()
                        delay(20) // a capture takes time on the host too
                        val n = frame.incrementAndGet()
                        HttpStatusCode.OK to """{"hash":"h$n","lines":["✻ Working… ($n s)"],"spinner":"Working… ($n s)"}"""
                    }
                    "/transcript" in p -> HttpStatusCode.OK to
                        """{"events":[{"seq":1,"kind":"assistant","text":"hi"}],"nextOffset":10,"windowStart":0,"state":"running"}"""
                    "/agents" in p -> HttpStatusCode.OK to """{"agents":[]}"""
                    "/graph" in p -> HttpStatusCode.OK to """{"name":"s1","cursor":{"size":10,"agentBytes":0}}"""
                    "/overview" in p -> HttpStatusCode.OK to """{"name":"s1"}"""
                    else -> HttpStatusCode.NotFound to """{"error":"nope"}"""
                }
                respond(body, code, headersOf("Content-Type", listOf("application/json")))
            },
        )
        val presence = Presence()
        val meta = SessionMetaSaver(scope, { _, _, _ -> SessionMeta() })
        val c = SessionController(client, "s1", presence, PaneLeaseHolder(client, scope), meta, scope)
        return Rig(c, presence, screens, scope)
    }

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }

    @Test
    fun `behind the Screen tab a busy pane is asked for at most once a second`() {
        val r = rig()
        try {
            r.presence.setVisible(true)
            r.c.start() // the Conversation tab
            assertTrue(waitFor(10_000) { r.screens.size >= 4 }, "the pane poll stopped: ${r.screens.size} requests")
            val at = synchronized(r.screens) { r.screens.take(4) }
            val gapsMs = at.zipWithNext { a, b -> (b - a) / 1_000_000 }
            assertTrue(
                gapsMs.all { it >= SessionController.SCREEN_BEHIND_MS - 5 },
                "new frames were asked for ${gapsMs}ms apart behind the Screen tab; want each at least ${SessionController.SCREEN_BEHIND_MS}ms",
            )
        } finally {
            r.c.close()
            r.scope.cancel()
        }
    }

    @Test
    fun `on the Screen tab every new frame is followed at once`() {
        val r = rig()
        try {
            r.c.openTab(SessionTab.SCREEN)
            r.presence.setVisible(true)
            r.c.start()
            assertTrue(waitFor(10_000) { r.screens.isNotEmpty() }, "no pane poll at all")
            val first = r.screens.size
            Thread.sleep(2_000)
            val asked = r.screens.size - first
            assertTrue(asked >= 5, "the drawn pane was asked for $asked times in 2 s; it must follow every frame")
        } finally {
            r.c.close()
            r.scope.cancel()
        }
    }
}
