package com.silencelen.huginn

import com.silencelen.huginn.data.AnswerValue
import com.silencelen.huginn.data.QuestionAnswers
import com.silencelen.huginn.data.Screen
import com.silencelen.huginn.data.SessionQuestion
import com.silencelen.huginn.data.SessionQuestions
import com.silencelen.huginn.data.waitingFor
import com.silencelen.huginn.ui.AskStepper
import com.silencelen.huginn.ui.PromptGate
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The answer bodies both apps send to `POST /v1/sessions/:name/questions/:id`,
 * and the models they are built from. The daemon refuses a half answer, a list
 * for a single choice and an invented permission rule, so the builder must never
 * produce one — and a label holding `, ` must travel as an array element, never
 * joined, which is the whole reason arrays exist on this wire.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class QuestionAnswerTest {

    private val json = Json { ignoreUnknownKeys = true }

    // A GET /questions body as the daemon serves it (Claude Code 2.1.306 shapes).
    private val wire = """
        {"questions":[
          {"id":"1761000000000000000-41","kind":"ask","toolName":"AskUserQuestion","agentId":null,"agentType":null,"mcpServer":null,
           "input":{"questions":[
             {"question":"Which colour?","header":"Colour","multiSelect":false,
              "options":[{"label":"Red","description":"Select Red","preview":"a swatch"},{"label":"Blue","description":"Select Blue"}]},
             {"question":"Which fruits?","header":"Fruit","multiSelect":true,
              "options":[{"label":"Apple"},{"label":"Pear, ripe"},{"label":"Plum"}]}]},
           "suggestions":null,"fingerprint":"b81f799475e5","since":1761000000000,"bound":true,"fallback":null},
          {"id":"1761000000001000000-42","kind":"permission","toolName":"Bash","agentId":"agent-7","agentType":"Explore","mcpServer":null,
           "input":{"command":"touch probe.txt","description":"Create empty file probe.txt"},
           "suggestions":[{"type":"addDirectories","directories":["/home/alex/project"],"destination":"session"},{"type":"setMode","mode":"acceptEdits","destination":"session"}],
           "fingerprint":"f962b2f0460f","since":1761000001000,"bound":true,"fallback":null},
          {"id":"plan-1761000002000","kind":"plan","toolName":"ExitPlanMode","agentId":null,"agentType":null,"mcpServer":null,
           "input":{"plan":"# Plan\n1. Create plan.txt\n2. Write hi","planFilePath":"/home/alex/.claude/plans/p.md"},
           "suggestions":null,"fingerprint":"e4cd04d21ddc","since":1761000002000,"bound":true,"fallback":null},
          {"id":"1761000000003000000-43","kind":"permission","toolName":"mcp__grafana__query","mcpServer":"grafana",
           "input":{"query":"up"},"fingerprint":"0000aaaa1111","since":1761000003000,"bound":false,"fallback":"screen","unknownField":1}
        ]}
    """.trimIndent()

    private fun questions() = json.decodeFromString<SessionQuestions>(wire).questions
    private fun ask() = questions()[0]
    private fun perm() = questions()[1]
    private fun plan() = questions()[2]

    @Test
    fun `the wire decodes, with the tool input typed where the card needs it`() {
        val qs = questions()
        assertEquals(4, qs.size)
        assertTrue(qs[0].isAsk && qs[1].isPermission && qs[2].isPlan)
        val specs = ask().askQuestions()
        assertEquals(listOf("Which colour?", "Which fruits?"), specs.map { it.question })
        assertEquals("a swatch", specs[0].options[0].preview)
        assertTrue(specs[1].multiSelect)
        assertTrue(specs[0].isChoice, "no kind given means a choice question")
        assertEquals("Bash: touch probe.txt", perm().headline())
        assertEquals(listOf("description: Create empty file probe.txt"), perm().permissionLines())
        assertEquals("# Plan\n1. Create plan.txt\n2. Write hi", plan().planText())
        assertEquals("/home/alex/.claude/plans/p.md", plan().planFilePath())
        assertEquals("grafana · query: up", qs[3].headline())
        assertEquals("Which colour?", ask().headline())
        assertEquals("Plan ready — approve to continue", plan().headline())
        // Unknown fields from a newer daemon are ignored; a Screen carries the list.
        val list = json.encodeToString(ListSerializer(SessionQuestion.serializer()), qs)
        val s = json.decodeFromString<Screen>("""{"width":80,"height":24,"questions":$list}""")
        assertEquals(4, s.questions.size)
        assertEquals(0, json.decodeFromString<Screen>("""{"width":80,"height":24}""").questions.size, "an older daemon: no list, no card")
    }

    @Test
    fun `an ask body carries every question, a string for one choice and an array for a multi-select`() {
        val body = QuestionAnswers.askBody(ask(), mapOf(
            "Which colour?" to AnswerValue.One("Red"),
            "Which fruits?" to AnswerValue.Many(listOf("Apple", "Pear, ripe")),
        ))
        assertNotNull(body)
        val answers = body!!["answers"]!!.jsonObject
        assertEquals("Red", answers["Which colour?"]!!.jsonPrimitive.content)
        // THE ARRAY IS THE POINT: a label with ", " in it cannot be told apart from
        // two labels once joined, so the labels travel as elements.
        assertEquals(listOf("Apple", "Pear, ripe"), answers["Which fruits?"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNull(body["response"])
        assertNull(body["fingerprint"], "the HTTP layer adds the fingerprint, once")
    }

    @Test
    fun `free text is a string answer, and a response rides beside the answers`() {
        val body = QuestionAnswers.askBody(ask(), mapOf(
            "Which colour?" to AnswerValue.Text("teal, actually"),
            "Which fruits?" to AnswerValue.Many(emptyList()),
        ), response = "also consider orange")
        assertNotNull(body)
        assertEquals("teal, actually", body!!["answers"]!!.jsonObject["Which colour?"]!!.jsonPrimitive.content)
        assertEquals(0, body["answers"]!!.jsonObject["Which fruits?"]!!.jsonArray.size, "none of a multi-select is an answer")
        assertEquals("also consider orange", body["response"]!!.jsonPrimitive.content)
        assertNull(QuestionAnswers.askBody(ask(), mapOf("Which colour?" to AnswerValue.One("Red")), response = "   ")?.get("response"))
    }

    @Test
    fun `a half answer is never sent, nor a list for a single choice`() {
        assertNull(QuestionAnswers.askBody(ask(), mapOf("Which colour?" to AnswerValue.One("Red"))), "one of two questions")
        assertNull(QuestionAnswers.askBody(ask(), mapOf(
            "Which colour?" to AnswerValue.Many(listOf("Red")),
            "Which fruits?" to AnswerValue.Many(listOf("Plum")),
        )), "a set for a single-select question")
        assertNull(QuestionAnswers.askBody(perm(), mapOf("x" to AnswerValue.One("y"))), "a permission has no questions")
        assertFalse(QuestionAnswers.complete(ask(), mapOf("Which colour?" to AnswerValue.One("Red"))))
        assertTrue(QuestionAnswers.complete(ask(), mapOf(
            "Which colour?" to AnswerValue.One("Red"), "Which fruits?" to AnswerValue.Many(emptyList()),
        )))
    }

    @Test
    fun `a permission body is allow, allow with the prompt's own suggestions, or deny with the words`() {
        assertEquals("allow", QuestionAnswers.permissionBody(perm(), allow = true)["behavior"]!!.jsonPrimitive.content)
        assertNull(QuestionAnswers.permissionBody(perm(), allow = true)["updatedPermissions"])
        val always = QuestionAnswers.permissionBody(perm(), allow = true, always = true)
        // Passed back verbatim: the app never composes a permission rule of its own.
        assertEquals(perm().suggestions, always["updatedPermissions"]!!.jsonArray.toList())
        val noSugg = QuestionAnswers.permissionBody(questions()[3], allow = true, always = true)
        assertNull(noSugg["updatedPermissions"], "nothing offered, nothing granted: a plain allow")
        val deny = QuestionAnswers.permissionBody(perm(), allow = false, message = "  use the existing file  ")
        assertEquals("deny", deny["behavior"]!!.jsonPrimitive.content)
        assertEquals("use the existing file", deny["message"]!!.jsonPrimitive.content)
        assertNull(QuestionAnswers.permissionBody(perm(), allow = false)["message"], "a bare No carries no words")
        assertNull(QuestionAnswers.permissionBody(perm(), allow = true, message = "ignored on a yes")["message"])
    }

    @Test
    fun `a plan is approved keeping the mode, or sent back with words`() {
        assertEquals("allow", QuestionAnswers.planBody(approve = true)!!["behavior"]!!.jsonPrimitive.content)
        assertNull(QuestionAnswers.planBody(approve = true)!!["mode"])
        assertEquals("auto", QuestionAnswers.planBody(approve = true, autoMode = true)!!["mode"]!!.jsonPrimitive.content)
        val fb = QuestionAnswers.planBody(approve = false, message = "add a rollback step")
        assertEquals("deny", fb!!["behavior"]!!.jsonPrimitive.content)
        assertEquals("add a rollback step", fb["message"]!!.jsonPrimitive.content)
        assertNull(QuestionAnswers.planBody(approve = false, message = "  "), "feedback needs words")
    }

    // ------------------------------------------------------------ the stepper

    @Test
    fun `the stepper walks one question at a time and submits the whole set`() {
        var s = AskStepper.of(ask())
        assertEquals(2, s.count)
        assertEquals("1 of 2", s.position)
        assertEquals("Which colour?", s.current!!.question)
        assertNull(s.body(ask()), "nothing answered yet")
        s = s.answer(AnswerValue.One("Blue"))
        assertEquals(1, s.index)
        assertEquals("2 of 2", s.position)
        assertFalse(s.complete)
        s = s.toggle("Apple").toggle("Plum").toggle("Apple")
        assertEquals(listOf("Plum"), s.currentChosen)
        assertNull(s.currentAnswer, "a tick is a draft")
        assertFalse(s.complete, "toggling is not answering")
        s = s.confirmMany()
        assertTrue(s.complete)
        assertTrue(s.isLast, "the last question stays put when answered")
        val body = s.body(ask())
        assertNotNull(body)
        assertEquals("Blue", body!!["answers"]!!.jsonObject["Which colour?"]!!.jsonPrimitive.content)
        assertEquals(listOf("Plum"), body["answers"]!!.jsonObject["Which fruits?"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `going back keeps what was chosen, and a single question needs no counter`() {
        var s = AskStepper.of(ask()).answer(AnswerValue.One("Red"))
        s = s.back()
        assertEquals(0, s.index)
        assertEquals(AnswerValue.One("Red"), s.currentAnswer)
        assertEquals(s, s.back(), "no earlier question")
        s = s.answer(AnswerValue.Text("teal")).confirmMany()
        assertEquals("teal", QuestionAnswers.summary(s.answers["Which colour?"]!!))
        assertEquals("none", QuestionAnswers.summary(s.answers["Which fruits?"]!!))
        val one = SessionQuestion(kind = "ask", input = json.decodeFromString(
            """{"questions":[{"question":"Q?","options":[{"label":"A"},{"label":"B"}]}]}""",
        ))
        assertNull(AskStepper.of(one).position)
        assertEquals(1, AskStepper.of(one).count)
    }

    // ------------------------------------------------------------ the gate

    @Test
    fun `the card draws the oldest bound question and never an unbound one`() {
        val qs = questions()
        assertEquals("1761000000000000000-41", PromptGate.cardQuestion(qs)!!.id)
        assertNull(PromptGate.cardQuestion(listOf(qs[3])), "fallback: screen keeps the link bar")
        assertNull(PromptGate.cardQuestion(emptyList()))
        assertNull(PromptGate.cardQuestion(listOf(qs[0].copy(fingerprint = ""))), "no fingerprint, no safe answer, no card")
        assertTrue(PromptGate.hasQuestion(null, null, null, listOf(qs[3])), "an unbound request is still a question (for the bar)")
        assertFalse(PromptGate.hasQuestion(null, null, null, emptyList()))
    }

    @Test
    fun `waiting-for words`() {
        val q = plan()
        assertEquals("just now", q.waitingFor(q.since + 30_000))
        assertEquals("5m", q.waitingFor(q.since + 5 * 60_000 + 1))
        assertEquals("2h", q.waitingFor(q.since + 2 * 3_600_000))
        assertEquals("just now", q.waitingFor(q.since - 5), "a clock behind the daemon's is not negative time")
    }

    @Test
    fun `a decoded result keeps the via and the refusal`() {
        val ok = json.decodeFromString<com.silencelen.huginn.data.QuestionAnswerResult>("""{"ok":true,"via":"hook"}""")
        assertTrue(ok.ok)
        assertEquals("hook", ok.via)
        val no = json.decodeFromString<com.silencelen.huginn.data.QuestionAnswerResult>("""{"ok":false,"reason":"unbound","error":"the hook did not take the answer"}""")
        assertFalse(no.ok)
        assertEquals("unbound", no.reason)
        // Belt and braces on the JSON helpers the builder leans on.
        assertEquals(JsonPrimitive("x"), JsonArray(listOf(JsonPrimitive("x")))[0])
    }
}
