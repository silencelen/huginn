package com.silencelen.huginn.desktop.notify

import com.silencelen.huginn.data.PanePrompt
import com.silencelen.huginn.ui.HeadroomRules
import com.silencelen.huginn.data.Watch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Turns watch digests into OS notifications, and takes them down again when the
 * world moves on.
 *
 * The RULES are pure and live in [NotifyRules]; this is the half that has a
 * clock, a network and a tray — and it exists as a separate object so those rules
 * can be tested without any of them.
 *
 * The one piece of logic that could not be pure is the ENRICHMENT: an attention
 * notification is worth much more carrying the actual question, which means a
 * one-shot `/screen` read, which means the notification is posted some
 * milliseconds after the digest that caused it. In that gap the question can be
 * answered — in tmux, from the phone, from another window — and its withdraw edge
 * then arrives BEFORE the post it was meant to cancel. The result is a "needs
 * you" that nothing will ever take down, sitting on the owner's screen pointing
 * at a question that no longer exists.
 *
 * The generation counter is the guard. Every digest bumps it; an enrichment
 * captures it before the fetch and, on return, refuses to post if the world has
 * moved on AND the session is no longer waiting. Both halves: a bumped generation
 * alone is ordinary (digests arrive constantly), and a stale session state alone
 * cannot happen without one.
 */
class NotifyRouter(
    private val scope: CoroutineScope,
    /** Where notifications go. Re-read per call so a failed backend can be swapped. */
    private val notifier: () -> Notifier,
    /** One-shot pane read for the question text and its answer buttons. */
    private val fetchPrompt: suspend (String) -> PanePrompt?,
    private val enabled: () -> Boolean,
    /** What the reader is looking at right now, or null when the window is not focused. */
    private val focusedTarget: () -> NavTarget?,
) {

    private val lock = Any()
    private var baseline = WatchBaseline()
    private var generation = 0L
    private var latestSessions: Map<String, String?> = emptyMap()

    /** For the status/diagnostics surfaces: which backend is live. */
    val backendName: String get() = notifier().name

    fun onDigest(watch: Watch) {
        val plan: NotifyPlan
        val gen: Long
        synchronized(lock) {
            generation += 1
            gen = generation
            latestSessions = watch.sessions
            plan = NotifyRules.plan(baseline, watch, focusedTarget(), enabled())
            baseline = plan.baseline
        }

        // Bounded, like the phone's PROMPT_FETCH_CAP: a burst of waiting sessions
        // should not become a burst of pane captures on the host. Past the cap the
        // notifications still arrive, just without their question and buttons.
        val attentionCount = plan.decisions.count { it is NotifyDecision.Attention }
        val enrich = attentionCount in 1..PROMPT_FETCH_CAP

        for (decision in plan.decisions) {
            when (decision) {
                is NotifyDecision.Withdraw -> notifier().withdraw(decision.key)

                is NotifyDecision.Finished -> notifier().post(
                    NotifyRequest(
                        key = NotifyRules.chatKey(decision.chatId),
                        title = decision.title?.take(60) ?: "Chat finished",
                        body = decision.snippet?.take(200) ?: "huginn has answered",
                        urgent = false,
                        target = NavTarget(TargetKind.CHATS, decision.chatId),
                    )
                )

                is NotifyDecision.Attention -> scope.launch { attention(decision.session, gen, enrich) }

                // --- headroom. All plain news except the downgrade, which is the
                // only one of the four the reader may want to reverse.

                is NotifyDecision.LimitHit -> notifier().post(
                    NotifyRequest(
                        key = NotifyRules.limitKey(decision.session),
                        title = "${decision.session} hit the usage limit",
                        // The instant is the daemon's; the countdown is arithmetic
                        // on two instants, which is the only time statement this
                        // client is allowed to make. See HeadroomRules.
                        body = HeadroomRules.shortUntil(decision.resetsAt, System.currentTimeMillis())
                            ?.let { "Resets in $it" }
                            ?: "Waiting for the window to reset",
                        // NEWS, not "needs you". Nothing is being asked of the
                        // reader — the daemon will pick it back up — and an urgent
                        // toast that stays on screen for something nobody has to
                        // act on is how the urgent ones stop being believed.
                        urgent = false,
                        target = NavTarget(TargetKind.SESSIONS, decision.session),
                    )
                )

                is NotifyDecision.Resumed -> notifier().post(
                    NotifyRequest(
                        key = RESUMED_KEY,
                        title = "Usage limit reset",
                        body = "Resumed: " + decision.sessions.joinToString(", ").take(180),
                        urgent = false,
                        target = NavTarget(TargetKind.SESSIONS, decision.sessions.first()),
                    )
                )

                is NotifyDecision.Downgraded -> {
                    val target = NavTarget(TargetKind.SESSIONS, decision.session)
                    notifier().post(
                        NotifyRequest(
                            key = "ladder:${decision.session}",
                            title = "${decision.session} moved to ${decision.to}",
                            body = "Its Fable window ran out. Undo puts it back and stops huginn moving it again.",
                            urgent = false,
                            target = target,
                            // BOUNDED, and exactly two: put it back, or accept it.
                            // No free text ever reaches a toast — the owner's rule
                            // for answering from a lock screen or a notification.
                            actions = listOf(
                                ToastAction("Undo", Activations.undoUrl(decision.session)),
                                ToastAction("OK", Activations.ackUrl("ladder:${decision.session}")),
                            ),
                        )
                    )
                }

                is NotifyDecision.LadderUp -> notifier().post(
                    NotifyRequest(
                        key = "ladder:${decision.session}",
                        title = "${decision.session} is back on its own model",
                        body = "Its window reset.",
                        urgent = false,
                        target = NavTarget(TargetKind.SESSIONS, decision.session),
                    )
                )
            }
        }
    }

    /** Opening a target reads as acknowledgement — the same rule the phone uses. */
    fun onViewed(target: NavTarget) {
        notifier().withdraw(target.key)
    }

    private suspend fun attention(session: String, gen: Long, enrich: Boolean) {
        val prompt = if (enrich) runCatching { fetchPrompt(session) }.getOrNull() else null

        // THE GUARD. See the class header: posting after our own withdraw edge
        // strands a notification nothing will take down.
        synchronized(lock) {
            if (gen != generation && latestSessions[session] != NotifyRules.ATTENTION) return
        }

        val target = NavTarget(TargetKind.SESSIONS, session)
        val backend = notifier()
        // Buttons only where they render AND where the host gave us a fingerprint
        // to stamp on them. A multi-select dialog gets none at all: the toast
        // contract is bounded single taps, and offering one checkbox of a set as a
        // button would submit an answer nobody chose.
        val options = when {
            !backend.supportsActions -> emptyList()
            prompt?.fingerprint.isNullOrEmpty() -> emptyList()
            prompt?.multiSelect == true -> emptyList()
            else -> prompt?.options.orEmpty().map { AnswerOption(it.number, it.label) }
        }

        backend.post(
            NotifyRequest(
                key = target.key,
                title = "$session needs you",
                body = prompt?.question?.takeIf { it.isNotBlank() } ?: "Waiting for your answer",
                urgent = true,
                target = target,
                options = options,
                fingerprint = prompt?.fingerprint,
            )
        )
    }

    companion object {
        /** How many freshly-waiting sessions still get their question fetched. */
        const val PROMPT_FETCH_CAP: Int = 3

        /**
         * One key for every resume, because a window resetting is ONE event
         * however many sessions came back with it — and a second reset later
         * replaces the first notice rather than stacking on it.
         */
        const val RESUMED_KEY: String = "headroom:resumed"
    }
}
