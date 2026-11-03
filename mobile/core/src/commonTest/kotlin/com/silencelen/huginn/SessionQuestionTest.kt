package com.silencelen.huginn

import com.silencelen.huginn.data.QuestionAnswerResult
import com.silencelen.huginn.data.QuestionAnswers
import com.silencelen.huginn.data.SessionQuestion
import com.silencelen.huginn.data.SessionQuestions
import com.silencelen.huginn.ui.PromptGate
import com.silencelen.huginn.ui.QuestionDraft
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `session` question kind (appd 3.17): Claude's `start_session` tool asking
 * for a new session, as a session's or a chat's `questions` list carries it.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class SessionQuestionTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val wire = """
        {"questions":[{"id":"sq-1","kind":"session","toolName":"start_session",
          "input":{"directory":"/srv/work/lora","prompt":"Audit the LoRa node firmware.\n\nStart with power.","name":"lora-audit"},
          "fingerprint":"fp-1","since":1000,"bound":true,
          "requester":{"sessionName":"planner","chatId":null},"expiresAt":4600000}]}
    """.trimIndent()

    private fun q(): SessionQuestion = json.decodeFromString<SessionQuestions>(wire).questions[0]

    @Test
    fun `a session request decodes with its directory, prompt, name, requester and expiry`() {
        val q = q()
        assertTrue(q.isSession)
        assertFalse(q.isAsk); assertFalse(q.isPermission); assertFalse(q.isPlan)
        val r = assertNotNull(q.sessionRequest())
        assertEquals("/srv/work/lora", r.directory)
        assertEquals("Audit the LoRa node firmware.\n\nStart with power.", r.prompt, "the prompt is VERBATIM, newlines kept")
        assertEquals("lora-audit", r.name)
        assertEquals("planner", q.requester?.sessionName)
        assertNull(q.requester?.chatId)
        assertEquals(4600000L, q.expiresAt)
        assertEquals("Claude wants to start a session in /srv/work/lora", q.headline())
        assertEquals("asked by planner", q.requesterWords())
        assertEquals("expires in 1h", q.expiryWords(nowMs = 1_000_000))
        assertEquals("expired", q.expiryWords(nowMs = 5_000_000))
    }

    @Test
    fun `a request with no suggested name decodes null, and a chat requester is named as such`() {
        val q = json.decodeFromString<SessionQuestions>(
            """{"questions":[{"id":"sq-2","kind":"session","toolName":"start_session",
                "input":{"directory":"/srv","prompt":"go","name":null},"fingerprint":"fp","since":1,"bound":true,
                "requester":{"sessionName":null,"chatId":"c-9"}}]}""",
        ).questions[0]
        assertNull(q.sessionRequest()?.name)
        assertEquals("asked from a chat", q.requesterWords())
        assertNull(q.expiryWords(0), "no deadline, no words")
        assertEquals("", QuestionDraft.of(q).name, "the name field starts blank when Claude left the choice open")
    }

    @Test
    fun `the card's question is the bound session request like any other kind`() {
        assertEquals("sq-1", PromptGate.cardQuestion(listOf(q()))?.id)
    }

    @Test
    fun `the draft seeds the editable name from the request`() {
        val d = QuestionDraft.of(q())
        assertEquals("lora-audit", d.name)
        assertEquals("lora-2", d.copy(name = "lora-2").name)
        assertTrue(d.stepper.specs.isEmpty(), "a session request has no ask steps")
    }

    @Test
    fun `Start sends allow with start true and the question id, then the edited name, and a blank name is omitted`() {
        // ⚠ THE FAIL-FIRST (fourth security scan 2026-11-03, F1): the daemon
        // refuses a session start without `start: true` and the echoed id —
        // the shape that only this card sends — so a bare allow is no longer
        // an approval of a prompt the person may not have seen whole.
        assertEquals("""{"behavior":"allow","start":true,"id":"sq-1","name":"lora-2"}""", QuestionAnswers.sessionBody(q(), allow = true, name = " lora-2 ").toString())
        assertEquals("""{"behavior":"allow","start":true,"id":"sq-1"}""", QuestionAnswers.sessionBody(q(), allow = true, name = "  ").toString())
        val other = json.decodeFromString<SessionQuestions>(
            """{"questions":[{"id":"sq-7","kind":"session","toolName":"start_session",
                "input":{"directory":"/srv","prompt":"go","name":null},"fingerprint":"fp","since":1,"bound":true}]}""",
        ).questions[0]
        assertEquals("""{"behavior":"allow","start":true,"id":"sq-7"}""", QuestionAnswers.sessionBody(other, allow = true).toString(), "the id is the question's own")
    }

    @Test
    fun `Decline sends deny with the words only when there are any and nothing of the start shape`() {
        assertEquals("""{"behavior":"deny","message":"not now"}""", QuestionAnswers.sessionBody(q(), allow = false, message = "not now").toString())
        assertEquals("""{"behavior":"deny"}""", QuestionAnswers.sessionBody(q(), allow = false, message = "").toString())
        assertEquals("""{"behavior":"deny"}""", QuestionAnswers.sessionBody(q(), allow = false, name = "ignored").toString(), "a name rides only an allow")
    }

    @Test
    fun `a create refusal decodes with the still-waiting question`() {
        val r = json.decodeFromString<QuestionAnswerResult>(
            """{"error":"a session called lora-audit already exists","question":{"id":"sq-1","kind":"session",
                "toolName":"start_session","input":{"directory":"/srv","prompt":"go","name":"lora-audit"},
                "fingerprint":"fp-1","since":1000,"bound":true}}""",
        )
        assertFalse(r.ok)
        assertEquals("a session called lora-audit already exists", r.error)
        assertEquals("sq-1", r.question?.id)
        assertTrue(r.question?.isSession == true)
    }

    @Test
    fun `a started answer carries the name the session got`() {
        val r = json.decodeFromString<QuestionAnswerResult>(
            """{"ok":true,"via":"daemon","started":true,"name":"lora-audit","cwd":"/srv/work/lora","delivered":false,"queued":1}""",
        )
        assertTrue(r.ok)
        assertEquals(true, r.started)
        assertEquals("lora-audit", r.name)
    }
}
