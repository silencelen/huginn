package com.silencelen.huginn

import com.silencelen.huginn.data.PinBook
import com.silencelen.huginn.ui.gatedClient
import com.silencelen.huginn.ui.openChatReads
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠ OPENING A CHAT ASKS FOR ITS META AND ITS HISTORY TOGETHER, AND SHOWS THEM IN
 * THE ORDER IT ALWAYS DID.
 *
 * The history used to go out only once the meta had answered: two round trips
 * before any conversation appeared. [openChatReads] sends both at once and holds
 * the page, on this side of the wire, until the meta has been applied — so the
 * mode, model and title are over the page before it shows, and a 409 is judged
 * against whether the chat ever ran, exactly as before.
 *
 * Answered on the TEST dispatcher ([OrderedTransport]), so what is on the wire
 * while the meta is held is a fact, not a race.
 *
 * NOTE this module is on org.junit: `assertEquals(message, expected, actual)`.
 */
class ChatOpenOrderTest {

    private val meta = CompletableDeferred<Unit>()
    private var metaStatus = HttpStatusCode.OK
    private var historyStatus = HttpStatusCode.OK

    private fun TestScope.transport() = OrderedTransport(StandardTestDispatcher(testScheduler), TOKEN) { request ->
        val path = request.url.encodedPath
        val history = path.endsWith("/transcript")
        if (!history) meta.await()
        respond(
            content = when {
                history && historyStatus == HttpStatusCode.OK -> """{"events":[],"nextOffset":10,"windowStart":0,"title":"history"}"""
                history -> """{"error":"chat has not run yet"}"""
                metaStatus == HttpStatusCode.OK -> """{"id":"c1","title":"a chat","mode":"act","turns":2}"""
                else -> """{"error":"disk on fire"}"""
            },
            status = if (history) historyStatus else metaStatus,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    /** What the screen is handed, in the order it is handed it. */
    private val shown = mutableListOf<String>()

    private fun TestScope.open(wire: OrderedTransport) = backgroundScope.openChatReads(
        client = gatedClient({ BASE }, { TOKEN }, { "" }, MutableStateFlow(true), wire, PinBook()),
        id = "c1",
        applyMeta = { shown += "meta:${it?.mode}" },
        applyPage = { r -> shown += r.fold({ "page:${it.title}" }, { "failed:${it.message}" }) },
    )

    @Test
    fun `the history is on the wire while the meta is still being answered`() = runTest {
        val wire = transport()
        open(wire)
        runCurrent()
        assertEquals(
            "both asked at once, the meta first",
            listOf("/v1/chats/c1", "/v1/chats/c1/transcript?limit=400"),
            wire.wire,
        )
        assertEquals("but the page that came back waits for the meta", emptyList<String>(), shown)

        meta.complete(Unit)
        runCurrent()
        assertEquals("shown as it always was: the meta, then the page", listOf("meta:act", "page:history"), shown)
    }

    @Test
    fun `a meta that answers first is shown first, and the page after it`() = runTest {
        meta.complete(Unit)
        val wire = transport()
        open(wire)
        runCurrent()
        assertEquals(listOf("meta:act", "page:history"), shown)
    }

    @Test
    fun `an empty history is judged after the meta, which says whether the chat ever ran`() = runTest {
        historyStatus = HttpStatusCode.Conflict
        val wire = transport()
        open(wire)
        runCurrent()
        assertEquals(emptyList<String>(), shown)
        meta.complete(Unit)
        runCurrent()
        assertEquals(listOf("meta:act", "failed:chat has not run yet"), shown)
    }

    @Test
    fun `a meta that cannot be read does not hold the history`() = runTest {
        metaStatus = HttpStatusCode.InternalServerError
        meta.complete(Unit)
        val wire = transport()
        open(wire)
        runCurrent()
        assertEquals("the defaults, then the page", listOf("meta:null", "page:history"), shown)
    }

    @Test
    fun `a chat left before its meta answered shows no page`() = runTest {
        val wire = transport()
        val history = open(wire)
        runCurrent()
        history.cancel()             // detachStream, or another chat opened
        meta.complete(Unit)
        runCurrent()
        assertEquals("the meta lands as it always did; the page never does", listOf("meta:act"), shown)
    }

    @Test
    fun `the view model opens a chat through it`() {
        val vm = File(mobileRoot(), "app/src/main/kotlin/com/silencelen/huginn/ui/HuginnViewModel.kt").readText()
        val start = vm.indexOf("fun openChat(id: String)")
        val body = vm.substring(start, vm.indexOf("fun ensureChatOpen(", start))
        assertTrue("openChat no longer asks for both together", "chatPollJob = viewModelScope.openChatReads(" in body)
        assertTrue("openChat asks for the history after the meta again", "loadChatTranscript(" !in body)
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
