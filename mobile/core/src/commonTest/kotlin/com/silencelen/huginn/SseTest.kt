package com.silencelen.huginn

import com.silencelen.huginn.data.ChatEvent
import com.silencelen.huginn.data.HuginnClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import io.ktor.utils.io.writer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the client's SSE reader with byte-for-byte the frames huginn-appd emitted
 * on a real run (captured with curl against the live daemon on 2026-07-27), plus
 * the failure shapes the phone will actually hit: a 401, and a stream that dies
 * mid-answer because the link dropped.
 *
 * SHARED, and it took replacing OkHttp with Ktor to get here: this used to need
 * MockWebServer, which is a JVM library, which is why it was one of the nine
 * suites stranded in :app when :core was extracted. Ktor's MockEngine is itself
 * multiplatform, so these run against BOTH targets now.
 *
 * NOTE for anyone porting an assertion in or out of here: kotlin.test reverses
 * JUnit's argument order — `assertEquals(expected, actual, message)`. See the
 * header of TerminalGridTest.kt.
 */
class SseTest {

    private val seen = mutableListOf<HttpRequestData>()

    /** A client whose every request is answered with [body] as an event stream. */
    private fun client(body: String) = client { _ ->
        respond(
            content = ByteReadChannel(body),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
        )
    }

    private fun client(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        HuginnClient(
            baseUrlProvider = { "http://appd.test" },
            tokenProvider = { "test-token" },
            engine = MockEngine { request -> seen += request; handler(request) },
        )

    @Test
    fun `decodes a full run in order`() = runTest {
        // Verbatim from the live daemon.
        val events = client(
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
        ).sendMessage("86ed1440-e7ad-4dc4-aa2d-1d2142c570a1", "hi").toList()

        assertEquals(5, events.size)
        assertTrue(events[0] is ChatEvent.Started)
        assertEquals("raven check ok", (events[1] as ChatEvent.Delta).text)
        assertEquals("raven check ok", (events[2] as ChatEvent.Assistant).text)
        val r = events[3] as ChatEvent.Result
        assertTrue(r.ok)
        assertEquals(5417L, r.durationMs)
        assertTrue(kotlin.math.abs(r.costUsd!! - 0.466) < 0.001)
        assertEquals(ChatEvent.Done, events[4])
    }

    @Test
    fun `passes the bearer token and the streaming query flag`() = runTest {
        client("event: done\ndata: {}\n\n").sendMessage("abc", "hi").toList()
        val req = seen.single()
        assertEquals("Bearer test-token", req.headers[HttpHeaders.Authorization])
        assertTrue(
            req.url.encodedPath + "?" + req.url.encodedQuery == "/v1/chats/abc/messages?stream=1",
            "unexpected target ${req.url}",
        )
        assertEquals("POST", req.method.value)
    }

    @Test
    fun `heartbeat comments are ignored`() = runTest {
        val events = client(": ping\n\nevent: delta\ndata: {\"text\":\"a\"}\n\n: ping\n\nevent: done\ndata: {}\n\n")
            .streamChat("abc").toList()
        assertEquals(2, events.size)
        assertEquals("a", (events[0] as ChatEvent.Delta).text)
        assertEquals(ChatEvent.Done, events[1])
    }

    @Test
    fun `tool frames carry name and digested input`() = runTest {
        val events = client(
            "event: tool_start\ndata: {\"name\":\"Bash\"}\n\n" +
                "event: tool\ndata: {\"type\":\"tool\",\"name\":\"Bash\",\"input\":\"df -h /\"}\n\n" +
                "event: done\ndata: {}\n\n"
        ).streamChat("abc").toList()
        assertEquals("Bash", (events[0] as ChatEvent.ToolStart).name)
        val t = events[1] as ChatEvent.Tool
        assertEquals("Bash", t.name)
        assertEquals("df -h /", t.input)
    }

    @Test
    fun `server error frame becomes a failure event`() = runTest {
        val events = client("event: error\ndata: {\"text\":\"claude exited 1\"}\n\nevent: done\ndata: {}\n\n")
            .streamChat("abc").toList()
        assertEquals("claude exited 1", (events[0] as ChatEvent.Failure).text)
    }

    @Test
    fun `a 401 surfaces the servers own error text, not a generic code`() = runTest {
        val events = client { _ ->
            respond(
                content = ByteReadChannel("""{"error":"unauthorized"}"""),
                status = HttpStatusCode.Unauthorized,
            )
        }.streamChat("abc").toList()
        assertEquals(1, events.size)
        assertEquals("unauthorized", (events[0] as ChatEvent.Failure).text)
    }

    @Test
    fun `a stream cut mid-answer ends the flow without losing earlier events`() = runTest {
        // No trailing blank line and no done frame: exactly what a dropped link
        // looks like to the reader. The truncated frame is discarded (it never
        // terminated) but the flow must complete rather than hang, must not throw
        // into the collector, and must SAY the stream broke rather than end as
        // quietly as a finished one.
        val events = client("event: delta\ndata: {\"text\":\"half an ans")
            .streamChat("abc").toList()
        assertTrue(events.all { it is ChatEvent.Delta || it is ChatEvent.Failure }, "unexpected $events")
        assertTrue(events.last() is ChatEvent.Failure, "a cut link must end in a failure, got $events")
    }

    @Test
    fun `an idle chat reports done immediately`() = runTest {
        val events = client("event: done\ndata: {\"idle\":true}\n\n").streamChat("abc").toList()
        assertEquals(listOf(ChatEvent.Done), events)
    }

    @Test
    fun `a burst replay arrives whole rather than up to the channel capacity`() = runTest {
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
        val events = client(body).streamChat("abc").toList()
        val deltas = events.filterIsInstance<ChatEvent.Delta>()
        assertEquals(n, deltas.size, "every replayed delta must survive the handoff")
        assertEquals("1 ", deltas.first().text)
        assertEquals("$n ", deltas.last().text)
        assertEquals(ChatEvent.Done, events.last())
    }

    @Test
    fun `the reader drains the socket at the sockets pace, not the collectors`() = runTest {
        // THIS is what pins Channel.UNLIMITED, and it needed a second test because
        // the port changed what an undersized buffer costs. Under OkHttp the reader
        // was a callback using trySend, so a full channel LOST frames and the test
        // above caught it by counting. Under Ktor the reader is a flow and `emit`
        // suspends instead, so a 64-deep buffer would still deliver all 4000 — just
        // one recomposition at a time, with the socket stalled behind the UI and the
        // backpressure travelling all the way to the daemon's writer.
        //
        // So the property to prove is not "nothing is lost" but "the collector does
        // not pace the reader". The collector below parks on the first event until
        // the ENTIRE body has been handed to the transport, and the frames are padded
        // past Ktor's 1 MiB in-channel limit so that limit is really reached: with a
        // bounded buffer the reader stops, the writer blocks on a full channel,
        // `allWritten` never completes, and the test hangs until runTest kills it.
        // The deadlock IS the assertion.
        val n = 4000
        val pad = "x".repeat(800)          // 4000 * ~840B ≈ 3.4 MiB, well past 1 MiB
        val allWritten = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Dispatchers.Default)

        val client = client { _ ->
            respond(
                content = scope.writer {
                    for (i in 1..n) channel.writeStringUtf8("id: $i\nevent: delta\ndata: {\"text\":\"$i $pad\"}\n\n")
                    channel.writeStringUtf8("event: done\ndata: {}\n\n")
                    channel.flushAndClose()
                    allWritten.complete(Unit)
                }.channel,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }

        var parked = false
        val events = mutableListOf<ChatEvent>()
        client.streamChat("abc").collect { ev ->
            events.add(ev)
            if (!parked) { parked = true; allWritten.await() }
        }

        assertTrue(parked, "the collector never got an event to park on")
        assertEquals(n, events.filterIsInstance<ChatEvent.Delta>().size)
        assertEquals(ChatEvent.Done, events.last())
    }
}
