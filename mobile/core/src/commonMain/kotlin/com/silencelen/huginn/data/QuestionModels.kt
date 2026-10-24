package com.silencelen.huginn.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One thing a session is asking, as the daemon's request registry serves it
 * (`GET /v1/sessions/:name/questions`, and `Screen.questions`).
 *
 * This is the REQUEST, not the pane: Claude Code's own PermissionRequest hook
 * parked it, so the card has the exact tool input — every question of an
 * AskUserQuestion with its options and descriptions, the command a Bash prompt
 * wants to run, the plan text — whatever the terminal happens to be wide enough
 * to draw. Answering goes back to that hook by [id]; the [fingerprint] is the
 * request's identity (its kind, tool and exact input) and travels with the answer
 * so the daemon can refuse one meant for a request that has since changed.
 *
 * @property kind `ask` (AskUserQuestion), `permission` (any other tool), `plan` (ExitPlanMode).
 * @property input the tool's exact input: `{questions: [...]}` for an ask, the
 *   tool's own fields for a permission, `{plan, planFilePath}` for a plan.
 * @property suggestions the CLI's own permission suggestions, what "Always allow"
 *   would write; passed back verbatim, never composed here.
 * @property bound answerable from the card (through the hook, or the keys for a
 *   plan's approval). False with [fallback] `screen` means: steer to the Screen tab.
 */
@Serializable
data class SessionQuestion(
    val id: String = "",
    val kind: String = "",
    val toolName: String = "",
    val agentId: String? = null,
    val agentType: String? = null,
    val mcpServer: String? = null,
    val input: JsonObject? = null,
    val suggestions: List<JsonElement>? = null,
    val fingerprint: String? = null,
    val since: Long = 0,
    val bound: Boolean = false,
    val fallback: String? = null,
) {
    val isAsk: Boolean get() = kind == KIND_ASK
    val isPermission: Boolean get() = kind == KIND_PERMISSION
    val isPlan: Boolean get() = kind == KIND_PLAN

    /** The questions of an ask, typed; empty for anything else or a malformed input. */
    fun askQuestions(): List<AskQuestionSpec> {
        if (!isAsk) return emptyList()
        val input = input ?: return emptyList()
        return runCatching { QuestionJson.decodeFromJsonElement(AskInputSpec.serializer(), input).questions }
            .getOrDefault(emptyList())
    }

    /** The plan's markdown, when the runtime shipped it. */
    fun planText(): String? = input?.get("plan")?.stringOrNull()?.takeIf { it.isNotBlank() }

    fun planFilePath(): String? = input?.get("planFilePath")?.stringOrNull()?.takeIf { it.isNotBlank() }

    /**
     * One line saying what is asked: the first question, "Plan ready", or the
     * tool and the one field a person looks at (the command, the file, the URL).
     */
    fun headline(): String = when {
        isAsk -> askQuestions().firstOrNull()?.question ?: "Claude is asking a question"
        isPlan -> "Plan ready — approve to continue"
        else -> {
            val detail = permissionDetail()
            val head = mcpServer?.let { "$it · ${toolName.removePrefix("mcp__${it}__")}" } ?: toolName
            if (detail.isNullOrBlank()) head else "$head: ${detail.replace(Regex("\\s+"), " ").take(200)}"
        }
    }

    /**
     * The field of a permission's input a person actually reviews: the command,
     * else the path, the URL, the pattern, else the first string field.
     */
    fun permissionDetail(): String? {
        val i = input ?: return null
        for (k in listOf("command", "file_path", "url", "pattern", "query", "prompt")) {
            i[k]?.stringOrNull()?.let { if (it.isNotBlank()) return it }
        }
        return i.values.firstNotNullOfOrNull { it.stringOrNull()?.takeIf { s -> s.isNotBlank() } }
    }

    /**
     * The rest of a permission's input, for the folded detail under the headline:
     * every field but the one [permissionDetail] already showed, as `key: value`
     * lines, long values clipped. An Edit's old/new text is what you want to read
     * before saying yes; a 40 KB field is not.
     */
    fun permissionLines(): List<String> {
        val i = input ?: return emptyList()
        val shown = permissionDetail()
        return i.entries
            .filter { (_, v) -> v.stringOrNull() != shown }
            .map { (k, v) -> "$k: ${(v.stringOrNull() ?: v.toString()).take(DETAIL_CLIP)}" }
    }

    companion object {
        const val KIND_ASK = "ask"
        const val KIND_PERMISSION = "permission"
        const val KIND_PLAN = "plan"
        const val DETAIL_CLIP = 2000
    }
}

private fun JsonElement.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private val QuestionJson = Json { ignoreUnknownKeys = true; isLenient = true }

@Serializable
private data class AskInputSpec(val questions: List<AskQuestionSpec> = emptyList())

/**
 * One question of an AskUserQuestion, as the tool was called (Claude Code
 * 2.1.306's schema). [kind] is `choice` unless the tool said `text` (a free
 * box, [placeholder]) or `number` ([min]..[max] by [step], [default], [unit]);
 * those two are gated in the CLI and arrive rarely, but a card must draw them
 * rather than drop the question.
 */
@Serializable
data class AskQuestionSpec(
    val question: String = "",
    val header: String? = null,
    val kind: String = "choice",
    val description: String? = null,
    val options: List<AskOptionSpec> = emptyList(),
    val multiSelect: Boolean = false,
    val placeholder: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val default: Double? = null,
    val unit: String? = null,
) {
    val isChoice: Boolean get() = kind == "choice" || kind.isBlank()
    val isText: Boolean get() = kind == "text"
    val isNumber: Boolean get() = kind == "number"
}

/** One option: its label, the explanation, and a preview shown when it is focused. */
@Serializable
data class AskOptionSpec(
    val label: String = "",
    val description: String? = null,
    val preview: String? = null,
)

/**
 * What a person chose for one question, collected by the card's stepper.
 * [One] a label (or "Other…" text — the daemon does not care which), [Many]
 * the labels of a multi-select, [Text] the words of a text or number question.
 */
sealed class AnswerValue {
    data class One(val label: String) : AnswerValue()
    data class Many(val labels: List<String>) : AnswerValue()
    data class Text(val text: String) : AnswerValue()
}

/** `GET /v1/sessions/:name/questions`. */
@Serializable
data class SessionQuestions(val questions: List<SessionQuestion> = emptyList())

/** What `POST /v1/sessions/:name/questions/:id` reports back; a 409 decodes here too. */
@Serializable
data class QuestionAnswerResult(
    val ok: Boolean = false,
    /** `hook` when Claude Code's own hook took the decision, `keys` when the pane was typed into. */
    val via: String? = null,
    /** `gone`, `changed` or `unbound` on a refusal; ordinary, never retried. */
    val reason: String? = null,
    val error: String? = null,
)

/**
 * The answer bodies, built in one place so both apps send the same bytes.
 *
 * Pure, and the rules are the daemon's (lib/questions.js): an ask is answered
 * WHOLE — every question present, a string for one choice or typed text, an
 * array only for a multi-select (labels holding `, ` are safe that way, which
 * is why nothing here joins them); a permission is `allow` (with the prompt's
 * own suggestions when "always" was asked) or `deny` with the person's words;
 * a plan is approved, or sent back with what should change.
 */
object QuestionAnswers {
    /**
     * The ask body, or null when a question has no answer yet — the card keeps
     * the stepper open rather than sending half an answer the daemon refuses.
     */
    fun askBody(question: SessionQuestion, answers: Map<String, AnswerValue>, response: String? = null): JsonObject? {
        val specs = question.askQuestions()
        if (specs.isEmpty()) return null
        val out = buildJsonObject {
            for (spec in specs) {
                val v = answers[spec.question] ?: return null
                when (v) {
                    is AnswerValue.One -> put(spec.question, JsonPrimitive(v.label))
                    is AnswerValue.Text -> put(spec.question, JsonPrimitive(v.text))
                    is AnswerValue.Many -> {
                        if (!spec.multiSelect) return null
                        put(spec.question, JsonArray(v.labels.map { JsonPrimitive(it) }))
                    }
                }
            }
        }
        return buildJsonObject {
            put("answers", out)
            response?.takeIf { it.isNotBlank() }?.let { put("response", JsonPrimitive(it)) }
        }
    }

    /**
     * A permission. [always] passes the CLI's own [SessionQuestion.suggestions]
     * back as `updatedPermissions`; with none offered it is a plain allow.
     * [message] turns a No into "No, tell Claude what to change".
     */
    fun permissionBody(question: SessionQuestion, allow: Boolean, always: Boolean = false, message: String? = null): JsonObject =
        buildJsonObject {
            put("behavior", JsonPrimitive(if (allow) "allow" else "deny"))
            if (allow && always) {
                question.suggestions?.takeIf { it.isNotEmpty() }?.let { put("updatedPermissions", JsonArray(it)) }
            }
            if (!allow) message?.trim()?.takeIf { it.isNotEmpty() }?.let { put("message", JsonPrimitive(it)) }
        }

    /** A plan: approve (keeping the mode unless [autoMode]), or feedback, which needs words. */
    fun planBody(approve: Boolean, message: String? = null, autoMode: Boolean = false): JsonObject? {
        if (approve) {
            return buildJsonObject {
                put("behavior", JsonPrimitive("allow"))
                if (autoMode) put("mode", JsonPrimitive("auto"))
            }
        }
        val words = message?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return buildJsonObject {
            put("behavior", JsonPrimitive("deny"))
            put("message", JsonPrimitive(words))
        }
    }

    /** True when every question of an ask has an answer, so the stepper may submit. */
    fun complete(question: SessionQuestion, answers: Map<String, AnswerValue>): Boolean =
        question.askQuestions().let { specs -> specs.isNotEmpty() && specs.all { answers.containsKey(it.question) } }

    /** How a chosen label reads back in the "1 of 2" summary. */
    fun summary(value: AnswerValue): String = when (value) {
        is AnswerValue.One -> value.label
        is AnswerValue.Text -> value.text
        is AnswerValue.Many -> if (value.labels.isEmpty()) "none" else value.labels.joinToString(", ")
    }
}

/** The one place a question's `since` becomes "waiting for 2m". */
fun SessionQuestion.waitingFor(nowMs: Long): String {
    val s = ((nowMs - since) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m"
        else -> "${s / 3600}h"
    }
}

/** A JSON object is what the daemon takes; the HTTP layer adds the fingerprint. */
typealias AnswerBody = JsonObject
