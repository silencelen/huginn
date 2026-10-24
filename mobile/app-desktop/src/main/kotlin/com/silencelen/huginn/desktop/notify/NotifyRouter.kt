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
 *
 * It also keeps [nonces] in step with the screen: every toast that carries links
 * gets a fresh nonce when it is posted, and loses it when it is replaced or taken
 * down, so a button that is no longer showing cannot be pressed by a link that
 * copied it. The buttons that act for the owner (answer, undo) need it, and so
 * do the ways a toast is taken down from outside the window (its OK, a click on
 * it); see [Activations.resolve].
 *
 * And it decides what counts as the reader having SEEN a notification: arriving
 * at its target inside the app does ([onViewed]), but not the one arrival an
 * unproven outside link caused ([arrivedUnproven]).
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
    /** This process's toast nonces; [Activations.resolve] redeems them. */
    private val nonces: ActivationNonces = ActivationNonces(),
) {

    private val lock = Any()
    private var baseline = WatchBaseline()
    private var generation = 0L
    private var latestSessions: Map<String, String?> = emptyMap()

    /**
     * The key of the target an outside link with no good nonce last put the
     * window on, until the arrival it caused has been seen. See [arrivedUnproven].
     */
    private var unacknowledged: String? = null

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
                is NotifyDecision.Withdraw -> {
                    nonces.revoke(decision.key)
                    notifier().withdraw(decision.key)
                }

                is NotifyDecision.Finished -> post(
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

                is NotifyDecision.LimitHit -> post(
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

                is NotifyDecision.Resumed -> post(
                    NotifyRequest(
                        key = RESUMED_KEY,
                        title = "Usage limit reset",
                        body = "Resumed: " + decision.sessions.joinToString(", ").take(180),
                        urgent = false,
                        target = NavTarget(TargetKind.SESSIONS, decision.sessions.first()),
                    )
                )

                is NotifyDecision.Downgraded -> {
                    val key = "ladder:${decision.session}"
                    val backend = notifier()
                    // Minted only where the buttons will be drawn: a nonce that no
                    // toast carries is one nobody can press, but also one nobody needs.
                    val toast = stamped(
                        backend,
                        NotifyRequest(
                            key = key,
                            title = "${decision.session} moved to ${decision.to}",
                            // "puts it back" only once the turn it is in has
                            // ended — the /model picker cannot open inside a
                            // running turn. Saying so here is cheaper than the
                            // reader finding out from the outcome toast.
                            body = "Its Fable window ran out. Undo puts it back at the next turn " +
                                "boundary and stops huginn moving it again.",
                            urgent = false,
                            target = NavTarget(TargetKind.SESSIONS, decision.session),
                        ),
                        ActivationNonces.Grant.Undo(decision.session),
                    )
                    val nonce = toast.nonce
                    backend.post(
                        toast.copy(
                            // BOUNDED, and exactly two: put it back, or accept it.
                            // No free text ever reaches a toast — the owner's rule
                            // for answering from a lock screen or a notification.
                            // ONE nonce for both: pressing either spends the toast.
                            actions = if (nonce == null) emptyList() else listOf(
                                ToastAction("Undo", Activations.undoUrl(decision.session, nonce)),
                                ToastAction("OK", Activations.ackUrl(key, nonce)),
                            ),
                        )
                    )
                }

                // Replaces the downgrade toast, and with it any Undo it offered:
                // its fresh nonce takes the old one's place under the same key.
                is NotifyDecision.LadderUp -> post(
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

    /**
     * [request] carrying a fresh nonce for its toast, issued with [grant], where
     * [backend] draws links (a toast's buttons and its body); elsewhere the key's
     * last nonce is revoked and none is stamped. Also for the notices posted
     * outside the digest (an answer's or an undo's outcome), so a click on those
     * is provable too.
     */
    fun stamped(
        backend: Notifier,
        request: NotifyRequest,
        grant: ActivationNonces.Grant = ActivationNonces.Grant.Notice,
    ): NotifyRequest =
        if (backend.supportsActions) {
            request.copy(nonce = nonces.issue(request.key, grant, request.target))
        } else {
            nonces.revoke(request.key)
            request.copy(nonce = null)
        }

    private fun post(request: NotifyRequest) {
        val backend = notifier()
        backend.post(stamped(backend, request))
    }

    /**
     * The reader arrived at [target] inside the app. That reads as acknowledgement
     * — the same rule the phone uses — EXCEPT for the one arrival an unproven
     * outside link caused (see [arrivedUnproven]): that one is skipped, and the
     * next arrival there counts again.
     */
    fun onViewed(target: NavTarget) {
        synchronized(lock) {
            if (unacknowledged == target.key) {
                unacknowledged = null
                return
            }
        }
        takeDown(target.key)
    }

    /**
     * A click this run can prove came from its own surface — a toast link with that
     * toast's nonce, or this app's own tray — put the window on [target]: take its
     * notification down.
     */
    fun acknowledge(target: NavTarget) {
        synchronized(lock) { if (unacknowledged == target.key) unacknowledged = null }
        takeDown(target.key)
    }

    /**
     * An outside link with no good nonce is about to put the window on [target].
     *
     * The window comes up, because opening is harmless and the owner may well
     * have clicked it. But that arrival is not the owner having read anything —
     * any web page or local process can fire a `huginn://open` — so it must not
     * take the "needs you" down, which is what arriving otherwise does. Called
     * BEFORE the selection moves, and only when it will.
     */
    fun arrivedUnproven(target: NavTarget) {
        synchronized(lock) { unacknowledged = target.key }
    }

    private fun takeDown(key: String) {
        nonces.revoke(key)
        notifier().withdraw(key)
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
            // Never the TUI's own rows ("Type something.", "Chat about this"): a
            // tap on one only focuses an input nobody is at.
            else -> prompt?.options.orEmpty().filter { !it.extra }.map { AnswerOption(it.number, it.label) }
        }
        // ONE NONCE PER TOAST, for exactly the question and the options it shows.
        // This toast replaces any earlier one under the same key, so that one's
        // nonce goes now. A toast without buttons still gets one, which can only
        // open the session and take the toast down — never answer.
        val fp = prompt?.fingerprint
        val grant = if (options.isEmpty() || fp.isNullOrEmpty()) {
            ActivationNonces.Grant.Notice
        } else {
            val offered = options.take(MAX_ANSWER_BUTTONS).map { it.number }.toSet()
            ActivationNonces.Grant.Answer(session, fp, offered)
        }

        backend.post(
            stamped(
                backend,
                NotifyRequest(
                    key = target.key,
                    title = "$session needs you",
                    body = prompt?.question?.takeIf { it.isNotBlank() } ?: "Waiting for your answer",
                    urgent = true,
                    target = target,
                    options = options,
                    fingerprint = fp,
                ),
                grant,
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
