package com.silencelen.huginn.notify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A project's lead has proposed a cluster, decided without a Context.
 *
 * The sibling of [HeadroomNotices] and deliberately the same shape: one object
 * that turns a push into a fully-decided [Notice], one [PostArgs] that says
 * exactly how it reaches the shade, and nothing here that touches Android — which
 * is what makes the bound below testable rather than merely commented.
 *
 * ## The bound, and why it is absolute
 *
 * A proposal has THREE verbs on the card — Spawn · Edit · Discard — and only two
 * of them may ever be a notification button. Spawn and Discard are bounded
 * choices: each is one request about a plan the daemon has already written down,
 * and neither carries a keystroke, a digit or a word of free text, so neither
 * needs the phone unlocked. **Edit is not a choice, it is an editor** — roles,
 * first prompts, models — and there is no such thing as a bounded editor on a
 * lock screen. Tapping the notification BODY opens the project, where Edit lives
 * beside the plan it edits; that is the whole provision made for it here.
 *
 * ⚠⚠ THE WIRE DOES NOT NAME THESE BUTTONS. [HeadroomNotices.relabel] lets the
 * daemon supply labels positionally, which is safe when both verbs are ways of
 * saying "I have seen this". It is NOT safe here: a payload carrying
 * `["Edit","Discard"]` would draw a button reading Edit that spawns twelve
 * sessions. So the labels are this client's constants, the daemon's `options` are
 * read for nothing, and a third option cannot exist because only two are ever
 * built.
 */
object ProjectNotices {

    /** The push kind the daemon sends when a lead's tagged manifest lands. */
    const val KIND_PROPOSED: String = "project_proposed"

    /** Create the members the owner just looked at. `POST …/spawn`. */
    const val VERB_SPAWN: String = "spawn"

    /** Turn the proposal down; the manifest is kept. `POST …/discard`. */
    const val VERB_DISCARD: String = "discard"

    /** The bounded verbs, and there are no others. Notably there is no `edit`. */
    val VERBS: Set<String> get() = setOf(VERB_SPAWN, VERB_DISCARD)

    /** The fixed labels. Not overridable from the wire — see the header. */
    const val LABEL_SPAWN: String = "Spawn"
    const val LABEL_DISCARD: String = "Discard"

    /**
     * A button.
     *
     * [manifestRev] travels with it because a notification can sit on a lock
     * screen while the lead revises its plan, and the daemon refuses a spawn
     * quoting a rev that has moved. A button that silently created a cluster
     * nobody read the plan for is exactly what the rev guard exists to stop.
     */
    data class Action(
        val label: String,
        val verb: String,
        val projectId: String,
        val manifestRev: Int,
    )

    /**
     * One thing to put on the shade, fully decided.
     *
     * [key] is the slot. `project:<id>` rather than the bare id, so a proposal
     * cannot land in the notification slot a SESSION of the same name occupies —
     * the ladder notices' rule, for the same reason.
     */
    data class Notice(
        val key: String,
        val title: String,
        val text: String,
        val projectId: String,
        val manifestRev: Int,
        val actions: List<Action>,
    )

    /**
     * Exactly how a [Notice] is handed to [SessionWatchWorker.post].
     *
     * Four of these fields are load-bearing NULLS and EMPTIES, and each one is a
     * door held shut:
     *
     * - `replyChat` null ⇒ no `RemoteInput`, so no free text off the shade.
     * - `answers` empty and `fingerprint` null ⇒ no pane-answer buttons; a
     *   proposal answers no dialog, and a fingerprint here would identify nothing.
     * - `session` null ⇒ the tap opens the PROJECT, not some session whose name
     *   happened to be in the payload.
     *
     * Asserted by test, because "this one never grows a reply box" is the kind of
     * invariant that survives as a comment and dies in the code.
     */
    data class PostArgs(
        val title: String,
        val text: String,
        val key: String,
        val project: String,
        val projectActions: List<Action>,
        val session: String? = null,
        val replyChat: String? = null,
        val answers: List<SessionWatchWorker.Companion.AnswerOption> = emptyList(),
        val fingerprint: String? = null,
        /** NEWS, not "needs you": it rides the channel that can be silenced alone. */
        val isResult: Boolean = true,
    )

    /** The notification slot a project owns. */
    fun keyFor(projectId: String): String = "project:$projectId"

    /**
     * One push, as the daemon sent it, turned into a [Notice].
     *
     * The daemon's own `title` and `text` are used when it sent them — it knows
     * the project's name and what the lead proposed, and a client that rewrote
     * that sentence from a kind word alone would be guessing. What the wire never
     * decides is the KEY, the VERBS or the LABELS.
     *
     * @return null for anything that is not a proposal, and for a proposal with
     *   no project id — a card whose buttons would address nothing.
     */
    fun fromPush(
        kind: String,
        title: String,
        text: String,
        subject: String?,
        /** The daemon's `payload` field, a JSON object as a string. */
        payload: String? = null,
    ): Notice? {
        if (kind != KIND_PROPOSED) return null
        val p = parsePayload(payload)
        // `payload.projectId` first: `subject` is the generic field every push
        // carries, and the payload is the one the daemon fills on purpose.
        val id = p["projectId"]?.takeIf { it.isNotBlank() }
            ?: p["project"]?.takeIf { it.isNotBlank() }
            ?: subject?.takeIf { it.isNotBlank() }
            ?: return null
        // FCM data is string-to-string, so a number arrives as its own text. An
        // unreadable rev becomes 0, which the daemon refuses — far better than
        // guessing a rev and spawning a plan nobody read.
        val rev = p["manifestRev"]?.trim()?.toIntOrNull() ?: 0
        val name = p["name"]?.takeIf { it.isNotBlank() }
        val summary = p["summary"]?.takeIf { it.isNotBlank() }
        return Notice(
            key = keyFor(id),
            title = title.ifBlank { if (name != null) "$name has a proposal" else "A project has a proposal" },
            text = text.ifBlank {
                summary ?: "The lead has sized the work and proposed the sessions to create."
            },
            projectId = id,
            manifestRev = rev,
            actions = actionsFor(id, rev),
        )
    }

    /**
     * The two buttons, and it is not possible for this to be three.
     *
     * Written as a list literal rather than assembled from anything, so that
     * "only bounded actions reach the shade" is a property of the code's shape
     * and not of a filter somebody could widen.
     */
    fun actionsFor(projectId: String, manifestRev: Int): List<Action> = listOf(
        Action(LABEL_SPAWN, VERB_SPAWN, projectId, manifestRev),
        Action(LABEL_DISCARD, VERB_DISCARD, projectId, manifestRev),
    )

    fun postArgs(n: Notice): PostArgs = PostArgs(
        title = n.title,
        text = n.text,
        key = n.key,
        project = n.projectId,
        projectActions = n.actions,
    )

    /** `{"projectId":"…","manifestRev":"2","name":"…"}` — string fields only. */
    private fun parsePayload(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            pushJson.parseToJsonElement(raw).jsonObject
                .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { k to it.content } }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    private val pushJson = Json { ignoreUnknownKeys = true; isLenient = true }
}
