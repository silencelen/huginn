package com.silencelen.huginn

import com.silencelen.huginn.data.InFlight
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Concurrent asks for the same read put ONE request on the wire; asks that do
 * not overlap each fetch afresh. See [InFlight] for the duplicate it removes.
 *
 * kotlin.test order: `assertEquals(expected, actual, message)`.
 */
class InFlightTest {

    @Test
    fun `two asks while one fetch is in flight make one request and share its answer`() = runTest {
        val shared = InFlight<List<String>>(backgroundScope)
        var fetches = 0
        val answer = CompletableDeferred<List<String>>()
        val fetch: suspend () -> List<String> = { fetches++; answer.await() }

        // The list's poll starting up, and the screen's own refresh, together.
        val poll = async { shared.get(fetch) }
        val refresh = async { shared.get(fetch) }
        runCurrent()
        assertEquals(1, fetches, "the second ask rode on the first request")

        answer.complete(listOf("alpha", "beta"))
        assertEquals(listOf("alpha", "beta"), poll.await())
        assertEquals(listOf("alpha", "beta"), refresh.await())
        assertEquals(1, fetches)
    }

    @Test
    fun `an ask after the answer landed fetches again, so nothing is served stale`() = runTest {
        val shared = InFlight<Int>(backgroundScope)
        var fetches = 0
        assertEquals(1, shared.get { ++fetches })
        assertEquals(2, shared.get { ++fetches })
        assertEquals(2, fetches)
    }

    @Test
    fun `a failure reaches every caller that waited for it, and the next ask tries again`() = runTest {
        val shared = InFlight<Int>(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        var fetches = 0
        val failing: suspend () -> Int = { fetches++; gate.await(); error("daemon said no") }
        val a = async { runCatching { shared.get(failing) } }
        val b = async { runCatching { shared.get(failing) } }
        runCurrent()
        gate.complete(Unit)
        assertEquals("daemon said no", a.await().exceptionOrNull()?.message)
        assertEquals("daemon said no", b.await().exceptionOrNull()?.message)
        assertEquals(1, fetches)
        assertEquals(7, shared.get { fetches++; 7 }, "a failed fetch is not remembered")
    }

    @Test
    fun `one caller giving up does not cancel the answer another is waiting for`() = runTest {
        val shared = InFlight<String>(backgroundScope)
        val answer = CompletableDeferred<String>()
        var fetches = 0
        val fetch: suspend () -> String = { fetches++; answer.await() }
        // The session screen closing while the list was asking.
        val leaving = async { shared.get(fetch) }
        val staying = async { shared.get(fetch) }
        runCurrent()
        leaving.cancel()
        runCurrent()
        answer.complete("rows")
        assertEquals("rows", staying.await())
        assertTrue(leaving.isCancelled)
        assertEquals(1, fetches)
        assertFailsWith<kotlinx.coroutines.CancellationException> { leaving.await() }
    }
}
