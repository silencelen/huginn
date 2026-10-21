package com.silencelen.huginn

import com.silencelen.huginn.data.PinBook
import com.silencelen.huginn.ui.SessionLoads
import com.silencelen.huginn.ui.errorTextFor
import com.silencelen.huginn.ui.gatedClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠⚠ OPENING A SESSION ASKS FOR ITS HISTORY FIRST; THE STRIP AND THE MAP WAIT.
 *
 * The daemon answers one request at a time. Opening a session used to put the
 * transcript, the stream strip's `agents?all=1` and the overview map on the wire
 * together, and `agents?all=1` on a session that had run ~160 subagents took
 * ~3 s with nothing else answered meanwhile — on the owner's phone the history
 * appeared at ~5 s, after the strip. [SessionLoads] now holds the strip (and the
 * view model's overview poll, behind another tab) until the history has had its
 * first answer. What the screen holds once everything has loaded is unchanged.
 *
 * Every request here is answered on the TEST dispatcher ([OrderedTransport]), so
 * the order on the wire is a fact, not a race.
 *
 * NOTE this module is on org.junit: `assertEquals(message, expected, actual)`.
 */
class HistoryFirstTest {

    /** Held until a test lets the history's answer go; everything else answers at once. */
    private val history = CompletableDeferred<Unit>()
    private var historyStatus = HttpStatusCode.OK
    private var historyBody = """{"events":[],"nextOffset":10,"windowStart":0,"title":"history"}"""

    private fun TestScope.transport() = OrderedTransport(StandardTestDispatcher(testScheduler), TOKEN) { request ->
        val path = request.url.encodedPath
        if (path.endsWith("/transcript")) history.await()
        respond(
            content = when {
                path.endsWith("/transcript") -> historyBody
                path.endsWith("/agents") -> """{"agents":[{"id":"a1","active":true},{"id":"b2"}],"active":1}"""
                else -> "{}"
            },
            status = if (path.endsWith("/transcript")) historyStatus else HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    private fun TestScope.loads(wire: OrderedTransport) = SessionLoads(
        scope = backgroundScope,
        client = gatedClient({ BASE }, { TOKEN }, { "" }, MutableStateFlow(true), wire, PinBook()),
        errText = ::errorTextFor,
        toast = {},
    )

    @Test
    fun `the strip is not asked for until the history has answered`() = runTest {
        val wire = transport()
        val loads = loads(wire)
        // The session screen's lifecycle effect, in its order.
        loads.startHistory("s1")
        loads.startAgents("s1")
        runCurrent()
        assertEquals(
            "only the history is on the wire while it is being answered",
            listOf("/v1/sessions/s1/transcript?limit=400"),
            wire.wire,
        )

        history.complete(Unit)
        runCurrent()
        assertEquals(
            listOf("/v1/sessions/s1/transcript?limit=400", "/v1/sessions/s1/agents?all=1"),
            wire.wire,
        )
    }

    @Test
    fun `once everything has loaded the screen holds what it always did`() = runTest {
        val wire = transport()
        val loads = loads(wire)
        loads.startHistory("s1")
        loads.startAgents("s1")
        history.complete(Unit)
        runCurrent()
        assertEquals("history", loads.history.value?.title)
        assertNull(loads.historyError.value)
        assertEquals("the strip still lists every agent, `all=1`", listOf("a1", "b2"), loads.agents.value.map { it.id })
    }

    @Test
    fun `a history that fails does not hold the strip`() = runTest {
        historyStatus = HttpStatusCode.InternalServerError
        historyBody = """{"error":"disk on fire"}"""
        history.complete(Unit)
        val wire = transport()
        val loads = loads(wire)
        loads.startHistory("s1")
        loads.startAgents("s1")
        runCurrent()
        assertEquals("disk on fire", loads.historyError.value)
        assertTrue("the strip went ahead after the failure", "/v1/sessions/s1/agents?all=1" in wire.wire)
    }

    @Test
    fun `a session with no transcript yet does not hold the strip`() = runTest {
        // A plain shell, or a session whose first prompt has not landed: 409.
        historyStatus = HttpStatusCode.Conflict
        historyBody = """{"error":"no transcript recorded for this session yet"}"""
        history.complete(Unit)
        val wire = transport()
        val loads = loads(wire)
        loads.startHistory("s1")
        loads.startAgents("s1")
        runCurrent()
        assertTrue("/v1/sessions/s1/agents?all=1" in wire.wire)
    }

    @Test
    fun `nothing waits on a session that is not loading here`() = runTest {
        val wire = transport()
        val loads = loads(wire)
        var other = false
        var left = false
        loads.startHistory("s1")
        backgroundScope.launch { loads.awaitHistory("s2"); other = true }
        backgroundScope.launch { loads.awaitHistory("s1"); left = true }
        runCurrent()
        assertTrue("another session's wait returns at once", other)
        assertTrue("s1's history is still being answered", !left)
        // Leaving the session before its history answered: nothing is stranded.
        loads.stopHistory()
        runCurrent()
        assertTrue(left)
    }

    @Test
    fun `behind another tab the overview's first fetch waits for the history too`() {
        // The overview loop lives in the view model, which cannot be built here;
        // its wait is held against the source, as OverviewMetaOwedShapeTest holds
        // the rest of that loop.
        val vm = File(mobileRoot(), "app/src/main/kotlin/com/silencelen/huginn/ui/HuginnViewModel.kt").readText()
        val start = vm.indexOf("fun startOverviewPolling(")
        val body = vm.substring(start, vm.indexOf("fun overviewTabShown(", start))
        val wait = body.indexOf("if (!onTab) sessionLoads.awaitHistory(name)")
        assertTrue("the overview no longer waits for the history behind another tab", wait >= 0)
        assertTrue("it waits before its first fetch", wait < body.indexOf("client.sessionOverview(name)"))
        assertTrue("and before the map", wait < body.indexOf("client.sessionGraph(name"))
    }

    private fun mobileRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private companion object {
        const val BASE = "http://appd.test"
        const val TOKEN = "tok-1"
    }
}
