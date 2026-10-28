package com.silencelen.huginn.desktop

import com.silencelen.huginn.mockTransport
import com.silencelen.huginn.data.AnswerValue
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.SessionMeta
import com.silencelen.huginn.data.SessionMetaSaver
import com.silencelen.huginn.data.SessionQuestions
import com.silencelen.huginn.ui.QuestionDraft
import com.silencelen.huginn.ui.QuestionDraftBook
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A half-answered question card across a SESSION switch on the desktop.
 *
 * The session view builds one [SessionController] per session it shows and
 * closes it on the way out, so drafts kept on the controller survived a tab flip
 * and died on a switch to another session and back. The app store's
 * [QuestionDraftBook] is what the next controller is handed; these pin that, and
 * that an answer still drops the draft.
 *
 * NOTE the assertion order: kotlin.test is `assertEquals(expected, actual, message)`.
 */
class QuestionDraftSwitchTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val question = json.decodeFromString<SessionQuestions>(
        """
        {"questions":[{"id":"q-7","kind":"ask","toolName":"AskUserQuestion",
          "input":{"questions":[
            {"question":"Which colour?","header":"Colour","multiSelect":false,
             "options":[{"label":"Red"},{"label":"Blue"}]},
            {"question":"Which size?","header":"Size","multiSelect":false,
             "options":[{"label":"Small"},{"label":"Large"}]}]},
          "fingerprint":"fp-7","since":1,"bound":true}]}
        """.trimIndent(),
    ).questions[0]

    private fun controller(scope: CoroutineScope, book: QuestionDraftBook, name: String): SessionController {
        val client = HuginnClient(
            baseUrlProvider = { "http://daemon.test" },
            tokenProvider = { "t" },
            transport = mockTransport { _ ->
                respond("""{"ok":true,"via":"hook"}""", HttpStatusCode.OK, headersOf("Content-Type", listOf("application/json")))
            },
        )
        return SessionController(
            client, name, Presence(), PaneLeaseHolder(client, scope),
            SessionMetaSaver(scope, { _, _, _ -> SessionMeta() }), scope,
            questionDraftBook = book,
        )
    }

    @Test
    fun `a half-answered card survives a switch to another session and back`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val book = QuestionDraftBook()
            val half = QuestionDraft.of(question).answerCurrent(AnswerValue.One("Blue"))

            val first = controller(scope, book, "alex")
            first.setQuestionDraft(question.id, half)
            first.close()                                   // switched to another session
            controller(scope, book, "other").close()        // ...which had its own controller

            val back = controller(scope, book, "alex")      // and back: a NEW controller
            assertEquals(half, back.questionDrafts.value[question.id], "the part-done walk comes back")
            back.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an answer still drops the draft from the shared book`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val book = QuestionDraftBook()
            val c = controller(scope, book, "alex")
            c.setQuestionDraft(question.id, QuestionDraft.of(question))
            c.answerQuestion(question, JsonObject(emptyMap()))
            withTimeout(5_000) { while (c.answering.value || book.drafts.value.isNotEmpty()) delay(10) }
            assertNull(book.drafts.value[question.id], "answered: nothing left to come back to")
            c.close()
        } finally {
            scope.cancel()
        }
    }
}
