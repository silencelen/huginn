package com.silencelen.huginn.desktop

import com.silencelen.huginn.mockTransport
import com.silencelen.huginn.data.HuginnClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Opening a chat reads its conversation without waiting for the chat's record.
 *
 * The record (`GET /v1/chats/:id`, which also carries the chat's whole message
 * digest) used to be read first and the transcript only after it: two round
 * trips, one after the other, before the conversation could draw. The record
 * is HELD here, so "the conversation came first" is a fact about the code.
 */
class ChatHistoryFirstTest {

    private val base = "https://h:1"
    private val id = "11111111-2222-3333-4444-555555555555"

    private class Rig(val c: ChatController, val seen: MutableList<String>, val scope: CoroutineScope)

    private fun rig(record: CompletableDeferred<Unit>, turns: Int, transcript: Pair<HttpStatusCode, String>): Rig {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val client = HuginnClient(
            baseUrlProvider = { base },
            tokenProvider = { "t" },
            transport = mockTransport { request ->
                val p = request.url.toString().removePrefix(base)
                seen += p
                val (code, body) = when {
                    p.startsWith("/v1/chats/$id/transcript") -> transcript
                    p == "/v1/chats/$id" -> {
                        record.await()
                        HttpStatusCode.OK to """{"id":"$id","title":"Budget","mode":"ask","turns":$turns,"running":false}"""
                    }
                    p.startsWith("/v1/models") -> HttpStatusCode.OK to """{"models":[]}"""
                    else -> HttpStatusCode.NotFound to """{"error":"nope"}"""
                }
                respond(body, code, headersOf("Content-Type", listOf("application/json")))
            },
        )
        return Rig(ChatController(client, id, scope), seen, scope)
    }

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }

    private val page = HttpStatusCode.OK to
        """{"events":[{"seq":1,"kind":"user","text":"How much?"},{"seq":2,"kind":"assistant","text":"A third."}],"nextOffset":40,"windowStart":0}"""

    @Test
    fun `the conversation is drawn without waiting for the chat's record`() {
        val record = CompletableDeferred<Unit>()
        val r = rig(record, turns = 1, transcript = page)
        try {
            r.c.start()
            assertTrue(
                waitFor(3_000) { r.c.page.value?.events?.size == 2 },
                "the conversation waited for the chat's record: ${r.seen}",
            )
            record.complete(Unit)
            assertTrue(waitFor(3_000) { r.c.detail.value?.title == "Budget" }, "the record never landed: ${r.seen}")
        } finally {
            record.complete(Unit)
            r.c.close()
            r.scope.cancel()
        }
    }

    /**
     * A refused transcript is the one answer that needs the record: whether the
     * chat ever ran tells a conversation that is gone from one that never began.
     */
    @Test
    fun `a refused transcript still waits for the record to say which empty it is`() {
        val refused = HttpStatusCode.Conflict to """{"error":"transcript not found for this chat"}"""
        for ((turns, gone) in listOf(3 to true, 0 to false)) {
            val record = CompletableDeferred<Unit>()
            val r = rig(record, turns = turns, transcript = refused)
            try {
                r.c.start()
                assertTrue(waitFor(3_000) { r.seen.any { it.startsWith("/v1/chats/$id/transcript") } }, "no transcript read")
                Thread.sleep(200)
                assertFalse(r.c.messagesGone.value, "turns=$turns: decided before the record answered")
                record.complete(Unit)
                assertTrue(waitFor(3_000) { r.c.page.value != null }, "turns=$turns: the empty page never settled")
                assertEquals(gone, r.c.messagesGone.value, "turns=$turns")
            } finally {
                record.complete(Unit)
                r.c.close()
                r.scope.cancel()
            }
        }
    }
}
