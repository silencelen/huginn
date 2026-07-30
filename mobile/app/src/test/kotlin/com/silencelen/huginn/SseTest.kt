package com.silencelen.huginn

import com.silencelen.huginn.data.ChatEvent
import com.silencelen.huginn.data.HuginnClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives the client's SSE reader with byte-for-byte the frames huginn-appd emitted
 * on a real run (captured with curl against the live daemon on 2026-07-27), plus
 * the failure shapes the phone will actually hit: a 401, and a stream that dies
 * mid-answer because the link dropped.
 */
class SseTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun client() = HuginnClient(
        baseUrlProvider = { server.url("/").toString().removeSuffix("/") },
        tokenProvider = { "test-token" },
    )

    private fun sse(body: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    @Test
    fun `decodes a full run in order`() = runTest {
        // Verbatim from the live daemon.
        server.enqueue(
            sse(
                """
                id: 1
                event: started
                data: {"chatId":"86ed1440-e7ad-4dc4-aa2d-1d2142c570a1","ts":1785138269}

                id: 2
                event: delta
                data: {"text":"raven check ok"}

                id: 3
                event: assistant
                data: {"text":"raven check ok"}

                id: 4
                event: result
                data: {"type":"result","ok":true,"durationMs":5417,"costUsd":0.46641999999999995,"turns":1,"ts":1785138276}

                id: 5
                event: done
                data: {"exitCode":0}

                """.trimIndent() + "\n"
            )
        )

        val events = client().sendMessage("86ed1440-e7ad-4dc4-aa2d-1d2142c570a1", "hi").toList()

        assertEquals(5, events.size)
        assertTrue(events[0] is ChatEvent.Started)
        assertEquals("raven check ok", (events[1] as ChatEvent.Delta).text)
        assertEquals("raven check ok", (events[2] as ChatEvent.Assistant).text)
        val r = events[3] as ChatEvent.Result
        assertTrue(r.ok)
        assertEquals(5417L, r.durationMs)
        assertEquals(0.466, r.costUsd!!, 0.001)
        assertEquals(ChatEvent.Done, events[4])
    }

    @Test
    fun `passes the bearer token and the streaming query flag`() = runTest {
        server.enqueue(sse("event: done\ndata: {}\n\n"))
        client().sendMessage("abc", "hi").toList()
        val req = server.takeRequest()
        assertEquals("Bearer test-token", req.getHeader("Authorization"))
        assertTrue(req.path!!.endsWith("/v1/chats/abc/messages?stream=1"))
        assertEquals("POST", req.method)
    }

    @Test
    fun `heartbeat comments are ignored`() = runTest {
        server.enqueue(
            sse(": ping\n\nevent: delta\ndata: {\"text\":\"a\"}\n\n: ping\n\nevent: done\ndata: {}\n\n")
        )
        val events = client().streamChat("abc").toList()
        assertEquals(2, events.size)
        assertEquals("a", (events[0] as ChatEvent.Delta).text)
        assertEquals(ChatEvent.Done, events[1])
    }

    @Test
    fun `tool frames carry name and digested input`() = runTest {
        server.enqueue(
            sse(
                "event: tool_start\ndata: {\"name\":\"Bash\"}\n\n" +
                    "event: tool\ndata: {\"type\":\"tool\",\"name\":\"Bash\",\"input\":\"df -h /\"}\n\n" +
                    "event: done\ndata: {}\n\n"
            )
        )
        val events = client().streamChat("abc").toList()
        assertEquals("Bash", (events[0] as ChatEvent.ToolStart).name)
        val t = events[1] as ChatEvent.Tool
        assertEquals("Bash", t.name)
        assertEquals("df -h /", t.input)
    }

    @Test
    fun `server error frame becomes a failure event`() = runTest {
        server.enqueue(
            sse("event: error\ndata: {\"text\":\"claude exited 1\"}\n\nevent: done\ndata: {}\n\n")
        )
        val events = client().streamChat("abc").toList()
        assertEquals("claude exited 1", (events[0] as ChatEvent.Failure).text)
    }

    @Test
    fun `a 401 surfaces the servers own error text, not a generic code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        val events = client().streamChat("abc").toList()
        assertEquals(1, events.size)
        assertEquals("unauthorized", (events[0] as ChatEvent.Failure).text)
    }

    @Test
    fun `a stream cut mid-answer ends the flow without losing earlier events`() = runTest {
        // No trailing blank line and no done frame: exactly what a dropped link
        // looks like to the reader.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: delta\ndata: {\"text\":\"half an ans")
        )
        val events = client().streamChat("abc").toList()
        // The truncated frame is discarded (it never terminated) but the flow must
        // complete rather than hang, and must not throw into the collector.
        assertTrue(events.all { it is ChatEvent.Delta || it is ChatEvent.Failure })
    }

    @Test
    fun `an idle chat reports done immediately`() = runTest {
        server.enqueue(sse("event: done\ndata: {\"idle\":true}\n\n"))
        val events = client().streamChat("abc").toList()
        assertEquals(listOf(ChatEvent.Done), events)
    }

    @Test
    fun `a burst replay arrives whole rather than up to the channel capacity`() {
        // Reattaching to a running chat replays the run's whole buffer at once (up
        // to 4000 frames). The reader is the socket thread and the collector is the
        // main thread, so with callbackFlow's default capacity of 64 and a trySend
        // whose result was ignored, frames were DROPPED silently — including, on a
        // bad boundary, the `done` that triggers the transcript reload.
        //
        // 4000 is the daemon's replay cap, and it is also the number this had to
        // reach to reproduce: at 500 frames every event arrived even with a slow
        // collector (the socket read paces the producer enough), so a smaller test
        // would have passed against the unfixed client and proven nothing. Measured
        // with the buffer removed: 2144 of 4000 arrived, 1856 lost.
        val n = 4000
        val body = buildString {
            for (i in 1..n) append("id: $i\nevent: delta\ndata: {\"text\":\"$i \"}\n\n")
            append("event: done\ndata: {}\n\n")
        }
        server.enqueue(sse(body))
        // NOT runTest, and a DELIBERATELY SLOW collector: the producer is the socket
        // reader thread and the real collector is the main thread rendering Compose.
        // A collector that drains as fast as the reader fills never overflows the
        // channel, so an eager one would hide exactly what this test exists to catch.
        val events = mutableListOf<ChatEvent>()
        kotlinx.coroutines.runBlocking {
            client().streamChat("abc").collect { ev ->
                events.add(ev)
                if (events.size % 20 == 0) Thread.sleep(1)   // a frame's worth of work
            }
        }
        val deltas = events.filterIsInstance<ChatEvent.Delta>()
        assertEquals("every replayed delta must survive the handoff", n, deltas.size)
        assertEquals("1 ", deltas.first().text)
        assertEquals("$n ", deltas.last().text)
        assertEquals(ChatEvent.Done, events.last())
    }
}
