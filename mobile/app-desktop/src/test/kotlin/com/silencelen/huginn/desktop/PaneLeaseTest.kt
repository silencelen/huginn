package com.silencelen.huginn.desktop

import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.PaneLease
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE QUIT GESTURE, ON A ROUTE THAT HAS GONE QUIET.
 *
 * `releaseBlocking` runs on the UI thread from `onCloseRequest` and the tray's
 * Quit, and its whole contract is in its own KDoc: "a daemon that is not
 * answering must delay quitting by two seconds, not forever". That promise was
 * not deliverable — the bound sat OUTSIDE a `withContext(NonCancellable)` block,
 * and a timeout cannot cancel what declares itself uncancellable, so the window
 * froze for as long as the HTTP tier took (measured 30.5 s against a listener
 * that accepts and never answers) and only then closed.
 *
 * The bound therefore has to be INSIDE the non-cancellable region, around the
 * wire call itself, which is what these tests pin.
 */
class PaneLeaseTest {

    /** A daemon that accepts the connection and never answers it. */
    private fun blackHole() = MockEngine {
        delay(BLACK_HOLE_MS)
        respond("{}", HttpStatusCode.OK)
    }

    private fun client(engine: MockEngine) = HuginnClient(
        baseUrlProvider = { "http://127.0.0.1:1" },
        tokenProvider = { "t" },
        engine = engine,
    )

    @Test
    fun `quitting against a black-holed daemon is bounded, not merely intended`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val holder = PaneLeaseHolder(client(blackHole()), scope)
            runBlocking { holder.reconcile(PaneLease.Want("jtyper", 120, 40)) }
            assertEquals("jtyper", holder.heldSession, "the lease must be held before it can be released")

            val started = System.nanoTime()
            holder.releaseBlocking(timeoutMs = 300)
            val tookMs = (System.nanoTime() - started) / 1_000_000

            assertTrue(
                tookMs < BLACK_HOLE_MS / 2,
                "releaseBlocking must honour its own bound: took ${tookMs}ms",
            )
            // And it still counts as released: `held` is cleared before the wire
            // call, so a release that never lands must not leave this process
            // believing it still owns the owner's tmux geometry.
            assertNull(holder.heldSession)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `holding nothing costs nothing at all`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val holder = PaneLeaseHolder(client(blackHole()), scope)
            val started = System.nanoTime()
            holder.releaseBlocking(timeoutMs = 300)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 200)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a release that times out does not strand the next take`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val holder = PaneLeaseHolder(client(blackHole()), scope)
            runBlocking { holder.reconcile(PaneLease.Want("jtyper", 120, 40)) }
            holder.releaseBlocking(timeoutMs = 300)
            // The mutex must be free and the holder usable afterwards: a quit
            // that is cancelled (close-to-tray) is followed by ordinary work.
            runBlocking { holder.reconcile(PaneLease.Want("other", 100, 30)) }
            assertEquals("other", holder.heldSession)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        /**
         * How long the fake daemon holds a request. Longer than any bound under
         * test, short enough that a regression fails the suite rather than
         * hanging it.
         */
        const val BLACK_HOLE_MS = 8_000L
    }
}
