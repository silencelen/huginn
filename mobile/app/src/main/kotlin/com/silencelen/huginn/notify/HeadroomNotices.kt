package com.silencelen.huginn.notify

import com.silencelen.huginn.data.Watch
import com.silencelen.huginn.ui.HeadroomRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * What a usage limit is worth telling the owner about, decided without a Context.
 *
 * The phone's answer to the desktop's `NotifyRules`, and deliberately the same
 * shape: four decisions, all of them EDGES against a persisted baseline, every
 * one suppressed on the first look. What differs is only how they arrive and how
 * they are drawn, which is [WatchNotifier] and [SessionWatchWorker]'s business.
 *
 * ## Which path is authoritative
 *
 * TWO paths reach this object and they do not carry the same four kinds:
 *
 * - **The watch digest is authoritative for [Notice.Kind.LIMIT] and
 *   [Notice.Kind.RESUMED].** appd 3.0.0 emits no push for either: a stall and a
 *   resume are facts in `watch.headroom`, and `stalls`/`laddered` were added to
 *   the digest precisely so a notification could be built from them. [plan] is
 *   that path.
 * - **Push is authoritative for [Notice.Kind.DOWNGRADED] and
 *   [Notice.Kind.LADDER_UP].** The daemon calls `deliverPush` for both the moment
 *   the arbiter acts (`headroom_downgraded`, `headroom_ladder_up`), because both
 *   are offers with a deadline — a ladder move the reader may want reversed, and
 *   a one-shot question about a native switch. [fromPush] is that path, and the
 *   digest's `laddered` map then only advances the BASELINE so the reconcile does
 *   not say the same thing a second time.
 *
 * Both paths end in the same [Notice], so the two cannot describe the same event
 * differently depending on which noticed it — the drift the shared watch cycle
 * exists to stop.
 *
 * ## Buttons
 *
 * Every action here is a BOUNDED CHOICE with a fixed label: Undo, OK, Back to
 * Fable, Stay. None of them carries free text, so none of them needs
 * authentication — that is the whole reason the owner's rule allows buttons on a
 * session notification at all. A free-text path into a pane would need the
 * AppLock gate in front of it, and there is no such path here.
 */
object HeadroomNotices {

    // ------------------------------------------------------------- baseline

    /**
     * What was true last time anything looked.
     *
     * PERSISTED by the caller, like every other watch baseline on this client:
     * held in memory it would describe a process that no longer exists, and the
     * transition the alarm exists to catch is exactly the one that happened while
     * the app was dead.
     */
    data class Baseline(
        val stalled: Set<String> = emptySet(),
        val laddered: Map<String, String> = emptyMap(),
    )

    // -------------------------------------------------------------- notices

    /** A button, and never anything but one of these four. */
    data class Action(val label: String, val verb: String, val session: String)

    /**
     * One thing to put on the shade, fully decided.
     *
     * [key] is what the notification is FILED under, and it is load-bearing:
     * a limit notice is filed under the bare session name, which is the slot
     * `MainActivity`'s read-is-dismissed effect cancels when that session is
     * opened. A ladder notice is filed under `ladder:<name>` instead, so it
     * cannot replace a "needs you" for the same session.
     */
    data class Notice(
        val kind: Kind,
        val key: String,
        val title: String,
        val text: String,
        /** The session to open on tap, when there is one. */
        val session: String?,
        val actions: List<Action> = emptyList(),
    )

    enum class Kind { LIMIT, RESUMED, DOWNGRADED, LADDER_UP }

    /** Things to take DOWN rather than post. */
    data class Withdraw(val key: String)

    data class Plan(
        val notices: List<Notice> = emptyList(),
        val withdraw: List<Withdraw> = emptyList(),
        val next: Baseline = Baseline(),
    )

    // ----------------------------------------------------------- the verbs

    /** Put a laddered session back on the model appd moved it off. */
    const val VERB_UNDO = "undo"

    /** Accept the move (or the offer's refusal): take the notice down, do nothing. */
    const val VERB_ACK = "ack"

    /**
     * Accept the daemon's offer to move a natively-switched session back to Fable.
     *
     * ⚠ Routed through the same `POST /v1/sessions/:name/headroom/undo` as
     * [VERB_UNDO] because appd 3.0.0 exposes no other. See [UndoReceiver] for
     * what that costs and why the refusal is shown rather than swallowed.
     */
    const val VERB_BACK = "back"

    /**
     * Exactly how a [Notice] is handed to [SessionWatchWorker.post].
     *
     * A value rather than four arguments spelled out at two call sites, because
     * three of the fields are load-bearing NULLS and a null is the easiest thing
     * in the world to add by accident later:
     *
     * - `replyChat` is null, and that is what keeps a free-text box off these
     *   notifications. `post` attaches a `RemoteInput` — and the AppLock gate in
     *   front of it — only when a chat is named, so a headroom notice cannot grow
     *   one without this field changing.
     * - `answers` is empty and `fingerprint` is null: an answer selects a ROW of a
     *   pane dialog and is refused without that dialog's fingerprint. A headroom
     *   button answers no dialog, so carrying either would be a fingerprint that
     *   means nothing.
     *
     * Asserted by test, because "these never get a reply box" is exactly the kind
     * of invariant that survives as a comment and dies in the code.
     */
    data class PostArgs(
        val title: String,
        val text: String,
        val session: String?,
        val key: String,
        val actions: List<Action>,
        val replyChat: String? = null,
        val answers: List<String> = emptyList(),
        val fingerprint: String? = null,
        /** NEWS, not "needs you": these ride the channel that can be silenced alone. */
        val isResult: Boolean = true,
    )

    fun postArgs(n: Notice): PostArgs = PostArgs(
        title = n.title,
        text = n.text,
        session = n.session,
        key = n.key,
        actions = n.actions,
    )

    /** The bounded verbs, and there are no others. */
    val VERBS: Set<String> get() = setOf(VERB_UNDO, VERB_ACK, VERB_BACK)

    /**
     * The session on screen in front of the reader right now, or null.
     *
     * Here rather than at the call site so the suppression rule is one function
     * that a test can drive, instead of an expression written out beside each
     * notifier.
     */
    fun focusedSession(): String? = Foreground.session?.takeIf { Foreground.showsSession(it) }

    // ------------------------------------------------------- the digest path

    /**
     * The four decisions, from one observation of the watch digest.
     *
     * @param seeded false on the very first look ever taken, which announces
     *   nothing: everything visible then is a list of things already true, and
     *   switching the feature on must not produce a burst of news about the past.
     * @param focused the session on screen in front of the reader, whose limit
     *   notice is withheld — the same rule the attention notices obey, and for
     *   the same reason. Its baseline still advances, so the suppressed notice is
     *   CONSUMED rather than deferred: navigating away later must not make an
     *   already-seen event suddenly buzz.
     */
    fun plan(
        previous: Baseline,
        watch: Watch,
        seeded: Boolean,
        focused: String? = null,
    ): Plan {
        // Hoisted rather than dereferenced repeatedly: `watch.headroom` is a
        // nullable property of another module and is not smart-cast across the
        // reads below (the `r.lastRun.copy(...)` shape).
        val hr = watch.headroom
        val stalledNow = hr?.stalled?.toSet().orEmpty()
        val ladderedNow = hr?.laddered.orEmpty()
        // A daemon with no headroom block leaves both EMPTY, which is the same
        // baseline this client has always had: nothing appears, nothing
        // disappears, and no headroom decision is ever reached.
        val next = Baseline(stalled = stalledNow, laddered = ladderedNow)
        if (!seeded) return Plan(next = next)

        val notices = ArrayList<Notice>()
        val withdraw = ArrayList<Withdraw>()

        // A session that stopped being stalled is one the reader must not still
        // be looking at a "hit the limit" for: it resumed, it was typed into, or
        // it ended. Filed under the session key, so a session that stalls,
        // resumes and stalls again cannot leave two notices standing.
        for (name in previous.stalled) {
            if (name !in stalledNow) withdraw += Withdraw(name)
        }

        val stalls = hr?.stalls.orEmpty()
        for (name in stalledNow) {
            if (name in previous.stalled) continue
            if (name == focused) continue
            notices += limitNotice(name, stalls[name])
        }

        // ONE notice for however many resumed. Three sessions coming back
        // together is one event — the window reset — and three buzzes about it is
        // the same news three times.
        val resumed = previous.stalled.filter { it !in stalledNow && watch.sessions.containsKey(it) }
        if (resumed.isNotEmpty()) notices += resumedNotice(resumed.sorted())

        for ((name, to) in ladderedNow) {
            val was = previous.laddered[name]
            // A CHANGE of rung counts as well as an arrival: fable → opus →
            // sonnet is two moves, and reporting only the first would leave the
            // reader believing a session is still on opus.
            if (was == to) continue
            // Unless a push has just said so. [PUSHED] is written by the FCM
            // handler the instant it announces a downgrade, and it matches
            // WHATEVER family the digest then reports, because the push did not
            // carry one. It survives exactly one pass: `next` is rebuilt from the
            // digest wholesale, so the real family lands immediately after and a
            // genuine second rung move is announced normally.
            if (was == PUSHED) continue
            notices += downgradedNotice(name, to)
        }

        for (name in previous.laddered.keys) {
            if (name in ladderedNow) continue
            // Gone from the digest entirely is not a ladder-up: the session
            // ended, and "back on Fable" about something that no longer exists is
            // a notification with nowhere to go.
            if (!watch.sessions.containsKey(name)) continue
            notices += ladderUpNotice(name, offered = false)
        }

        return Plan(notices, withdraw, next)
    }

    // --------------------------------------------------------- the push path

    /**
     * One push, as the daemon sent it, turned into the same [Notice].
     *
     * The daemon's own `title` and `text` are used when it sent them: it knows
     * which window ran out and what it did about it, and a client that rewrote
     * that sentence from a kind word alone would be guessing. Its `options` name
     * the BUTTONS for the same reason.
     *
     * What the wire never decides is the KEY or a VERB: the slot a notification
     * occupies and the request a button makes are this client's, always. See
     * [relabel].
     *
     * @return null for anything that is not a headroom kind.
     */
    fun fromPush(
        kind: String,
        title: String,
        text: String,
        subject: String?,
        /** The daemon's `payload` field, a JSON object as a string. */
        payload: String? = null,
        /** The daemon's `options` field, a JSON array of button LABELS. */
        options: String? = null,
    ): Notice? {
        val p = parsePayload(payload)
        // `payload.session` first: `subject` is the tmux name and normally the
        // same string, but the payload is the field the daemon fills on purpose
        // and the one that survives a rename between send and delivery.
        val session = p["session"]?.takeIf { it.isNotBlank() } ?: subject
        val built = when (kind) {
            "headroom_limit" -> limitNotice(session ?: return null, null)
            "headroom_resumed" -> resumedNotice(
                session?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            )
            "headroom_downgraded" -> downgradedNotice(session ?: return null, to = p["to"])
            "headroom_ladder_up" -> ladderUpNotice(session ?: return null, offered = true)
            else -> return null
        }
        // The daemon's own words win when it sent any. The key and the verbs
        // stay this client's — see [relabel].
        return built.copy(
            title = title.ifBlank { built.title },
            text = text.ifBlank { built.text },
            actions = relabel(built.actions, parseLabels(options)),
        )
    }

    /**
     * The daemon's labels on THIS CLIENT's verbs, positionally.
     *
     * The wire may say what a button is CALLED and nothing else. Which verb it
     * runs is decided here, by position — the first button acts, the second
     * dismisses — because a payload that could name `undo` would be a request
     * arriving over the network to change a session's model, and the whole reason
     * these buttons work on a locked phone is that no such request exists.
     *
     * Extra labels are dropped and missing ones keep this client's own word, so a
     * daemon that sends three, none, or something unrecognisable still produces
     * exactly the bounded pair.
     */
    fun relabel(actions: List<Action>, labels: List<String>): List<Action> {
        if (labels.isEmpty()) return actions
        return actions.mapIndexed { i, a ->
            val label = labels.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapIndexed a
            a.copy(label = label.take(28))
        }
    }

    /** `{"session":"…","to":"opus","from":"fable"}` — string fields only. */
    private fun parsePayload(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            pushJson.parseToJsonElement(raw).jsonObject
                .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content } }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    /** `["Undo","OK"]`. Malformed yields none, which keeps this client's labels. */
    private fun parseLabels(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            pushJson.parseToJsonElement(raw).jsonArray
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        }.getOrDefault(emptyList())
    }

    private val pushJson = Json { ignoreUnknownKeys = true }

    /** True for a kind this object owns, whether or not it can build a notice. */
    fun isHeadroomKind(kind: String): Boolean = kind.startsWith("headroom_")

    // ------------------------------------------------------------ the words

    fun limitNotice(session: String, resetsAt: String?, nowMs: Long = System.currentTimeMillis()): Notice {
        // The instant is the daemon's; the countdown is arithmetic on two
        // instants, which is the only time statement this client is allowed to
        // make. A name with no reset time is still stalled — the notice is just
        // shorter, and "hit the limit" with a wrong clock would be worse.
        val resets = HeadroomRules.shortUntil(resetsAt, nowMs)
        return Notice(
            kind = Kind.LIMIT,
            // The SESSION key, deliberately: opening the session takes it down
            // through the read-is-dismissed effect that already cancels this slot.
            key = session,
            title = "$session hit the usage limit",
            text = if (resets != null) "Resets in $resets" else "Waiting for the window to reset",
            session = session,
        )
    }

    fun resumedNotice(sessions: List<String>): Notice = Notice(
        kind = Kind.RESUMED,
        // ONE key for every resume, because a window resetting is one event
        // however many sessions came back with it — and a later reset replaces
        // this notice rather than stacking on it.
        key = RESUMED_KEY,
        title = "Usage limit reset",
        text = if (sessions.isEmpty()) "huginn picked its sessions back up"
        else "Resumed: " + sessions.joinToString(", ").take(180),
        session = sessions.firstOrNull(),
    )

    fun downgradedNotice(session: String, to: String?): Notice = Notice(
        kind = Kind.DOWNGRADED,
        key = ladderKey(session),
        title = if (to != null) "$session moved to $to" else "$session moved down the ladder",
        text = "Its Fable window ran out. Undo puts it back and stops huginn moving it again.",
        session = session,
        // BOUNDED, and exactly two: put it back, or accept it. No free text ever
        // reaches a notification — the owner's rule for answering from a lock
        // screen — which is also why neither of these needs authentication.
        actions = listOf(
            Action("Undo", VERB_UNDO, session),
            Action("OK", VERB_ACK, session),
        ),
    )

    /**
     * @param offered true for the daemon's one-shot question about a session
     *   Claude Code moved off Fable by itself. Only then are there buttons: a
     *   ladder-up appd performed is news, and a question nobody asked does not
     *   want an answer.
     */
    fun ladderUpNotice(session: String, offered: Boolean): Notice = Notice(
        kind = Kind.LADDER_UP,
        key = ladderKey(session),
        title = if (offered) "Back to Fable?" else "$session is back on its own model",
        text = if (offered) {
            "Claude Code moved $session off Fable when it ran out. The Fable week has reset."
        } else {
            "Its window reset."
        },
        session = session,
        actions = if (offered) listOf(
            Action("Back to Fable", VERB_BACK, session),
            Action("Stay", VERB_ACK, session),
        ) else emptyList(),
    )

    /**
     * A ladder notice's own slot, separate from the session's.
     *
     * Sharing the session key would let "moved to opus" replace a "needs you"
     * that is still waiting for an answer — two unrelated facts about one session
     * competing for one line of the shade.
     */
    fun ladderKey(session: String): String = "ladder:$session"

    const val RESUMED_KEY: String = "headroom:resumed"

    /**
     * Baseline value meaning "a push already announced this downgrade".
     *
     * Not a family name and never displayed: it is a placeholder the FCM handler
     * writes so the reconcile that follows a push does not say the same thing a
     * second time.
     */
    const val PUSHED: String = "\u0000pushed"
}
