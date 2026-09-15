package com.silencelen.huginn.desktop.notify

import com.silencelen.huginn.data.Watch
import com.silencelen.huginn.notify.WatchCycle

/**
 * What the app has already seen. Compared against the next digest, this is the
 * only thing that turns a snapshot ("these two sessions are waiting") into an
 * event ("this one just started waiting").
 */
data class WatchBaseline(
    /**
     * False until the very first digest has been absorbed. It exists because
     * without it, launching the app announces the PAST: every session already
     * waiting and every chat that has ever finished arrive as a wave of alerts
     * for things the reader has usually already dealt with. The phone learned
     * this as `watchSeeded`; the Electron client learned it again as `seeded`.
     */
    val seeded: Boolean = false,
    val sessions: Map<String, String?> = emptyMap(),
    val runs: Map<String, Long> = emptyMap(),
    val running: Set<String> = emptySet(),
    /** Sessions that were sitting on a usage limit last time. */
    val stalled: Set<String> = emptySet(),
    /** Sessions the ladder had moved, name → the family they were on. */
    val laddered: Map<String, String> = emptyMap(),
)

/** One thing to do about a digest. */
sealed interface NotifyDecision {
    /** A session just started waiting on a question. Blocking: sound, and it stays up. */
    data class Attention(val session: String) : NotifyDecision

    /** A chat finished a run. News: silent. */
    data class Finished(val chatId: String, val title: String?, val snippet: String?) : NotifyDecision

    /** The world moved on — take the notification down before the reader acts on it. */
    data class Withdraw(val key: String) : NotifyDecision

    /**
     * A session just stopped on a usage limit.
     *
     * The most valuable notification in this whole wave, because it is the one
     * nothing else reports: a stalled session looks exactly like an idle one, and
     * the owner's own account of the problem is work sitting untouched until
     * morning after a cap that cleared at half past midnight.
     *
     * [resetsAt] is the daemon's instant, never a clock this client formatted.
     */
    data class LimitHit(val session: String, val resetsAt: String?) : NotifyDecision

    /** One or more sessions picked themselves back up. One notice, not N. */
    data class Resumed(val sessions: List<String>) : NotifyDecision

    /**
     * A live session was moved down the ladder.
     *
     * [session] is the undo TARGET — `POST /v1/sessions/:name/headroom/undo` — so
     * the toast's button has everything it needs without a second lookup. That is
     * why this carries a name rather than a count.
     */
    data class Downgraded(val session: String, val to: String) : NotifyDecision

    /** A session was put back on the model it started on, its window having reset. */
    data class LadderUp(val session: String) : NotifyDecision
}

data class NotifyPlan(
    val decisions: List<NotifyDecision>,
    val baseline: WatchBaseline,
)

/**
 * The notification router's decisions, with no OS, no network and no clock.
 *
 * Every rule here was bought with a real failure, on one client or another:
 *
 * - **Finishes come from the `finishedRuns` COUNTER**, via [WatchCycle.finishedSince]
 *   in `:core` — the same function the phone uses, so the two clients cannot drift
 *   about what "finished" means. A running-flag edge misses a run that starts and
 *   ends between two observations, and with a watch stream that can be reconnecting
 *   that is ordinary rather than exotic.
 * - **The first observation seeds and notifies nothing** (see [WatchBaseline.seeded]).
 * - **Leaving `attention` withdraws**, whether it was answered here, answered in
 *   tmux, or the session died. A "needs you" that outlives its question is worse
 *   than no notification: the reader opens it to find nothing waiting.
 * - **The focused target is suppressed.** Its prompt is already on screen. The
 *   BASELINE STILL ADVANCES for a suppressed alert, so it is consumed rather than
 *   deferred — navigating away later must not make an already-seen question buzz.
 * - **`enabled = false` suppresses posts but never withdraws.** Turning
 *   notifications off must not strand whatever is currently on the screen.
 */
object NotifyRules {

    fun sessionKey(name: String): String = NavTarget(TargetKind.SESSIONS, name).key

    fun chatKey(id: String): String = NavTarget(TargetKind.CHATS, id).key

    fun plan(
        previous: WatchBaseline,
        watch: Watch,
        focused: NavTarget?,
        enabled: Boolean,
    ): NotifyPlan {
        val runsNow = watch.chats.mapValues { it.value.finishedRuns }
        val runningNow = watch.chats.filterValues { it.running }.keys
        // Hoisted rather than dereferenced repeatedly: `watch.headroom` is a
        // nullable property of another module and is not smart-cast across the
        // reads below (gotcha 13 — the `r.lastRun.copy(...)` shape).
        val hr = watch.headroom
        val stalledNow = hr?.stalled?.toSet().orEmpty()
        val ladderedNow = hr?.laddered.orEmpty()
        val next = WatchBaseline(
            seeded = true,
            sessions = watch.sessions,
            runs = runsNow,
            running = runningNow,
            // A daemon with no headroom block leaves both EMPTY, which is the same
            // baseline it has always had: nothing appears, nothing disappears, and
            // no headroom decision is ever reached.
            stalled = stalledNow,
            laddered = ladderedNow,
        )

        // Absorbed silently. Note this happens even with notifications DISABLED:
        // otherwise switching them on months later replays the whole history in
        // one burst, which is exactly the failure seeding exists to prevent.
        if (!previous.seeded) return NotifyPlan(emptyList(), next)

        val decisions = ArrayList<NotifyDecision>()

        // --- withdrawals first, so a key that is both taken down and re-posted in
        // one digest ends up posted. (One session cannot be in two states at once,
        // so today that is theoretical; the ORDER is not, and a later rule that
        // makes it possible should not have to rediscover it.)
        for ((name, was) in previous.sessions) {
            if (was != ATTENTION) continue
            // Not in the digest at all counts: killed, renamed, or the daemon
            // restarted. A "needs you" filed under a session that no longer exists
            // has to come down too.
            if (watch.sessions[name] != ATTENTION) {
                decisions += NotifyDecision.Withdraw(sessionKey(name))
            }
        }

        // A session that stopped being stalled is one the reader must not still be
        // looking at a "hit the limit" for: it resumed, it was typed into, or it
        // ended. Filed under the SESSION key, so opening the session takes it down
        // like any other — and so a session that stalls, resumes and stalls again
        // cannot leave two notices standing.
        for (name in previous.stalled) {
            if (name !in stalledNow) decisions += NotifyDecision.Withdraw(limitKey(name))
        }

        // --- attention edges
        for ((name, state) in watch.sessions) {
            if (state != ATTENTION) continue
            if (previous.sessions[name] == ATTENTION) continue
            if (!enabled) continue
            if (focused == NavTarget(TargetKind.SESSIONS, name)) continue
            decisions += NotifyDecision.Attention(name)
        }

        // --- finishes, by counter (see the class header)
        val finished = WatchCycle.finishedSince(
            runsBefore = previous.runs,
            runsNow = runsNow,
            previouslyRunning = previous.running,
            running = runningNow,
        )
        if (enabled) {
            for (chatId in finished) {
                if (focused == NavTarget(TargetKind.CHATS, chatId)) continue
                val chat = watch.chats[chatId]
                decisions += NotifyDecision.Finished(chatId, chat?.title, chat?.snippet)
            }
        }

        // --- headroom edges, all four of them EDGES rather than states: the
        // digest is a snapshot, and every one of these would otherwise re-fire on
        // every poll for as long as the condition lasted.
        if (enabled) {
            val stalls = hr?.stalls.orEmpty()
            for (name in stalledNow) {
                if (name in previous.stalled) continue
                decisions += NotifyDecision.LimitHit(name, stalls[name])
            }

            // ONE notice for however many resumed. Three sessions coming back
            // together is one event — the window reset — and three toasts about it
            // is the same news three times.
            val resumed = previous.stalled.filter { it !in stalledNow && watch.sessions.containsKey(it) }
            if (resumed.isNotEmpty()) decisions += NotifyDecision.Resumed(resumed.sorted())

            for ((name, to) in ladderedNow) {
                // A CHANGE of rung counts as well as an arrival: fable → opus →
                // sonnet is two moves, and only reporting the first would leave
                // the reader believing a session is still on opus.
                if (previous.laddered[name] == to) continue
                decisions += NotifyDecision.Downgraded(name, to)
            }

            for (name in previous.laddered.keys) {
                if (name in ladderedNow) continue
                // Gone from the digest entirely is not a ladder-up: the session
                // ended, and "back on Fable" about something that no longer exists
                // is a notification with nowhere to go.
                if (!watch.sessions.containsKey(name)) continue
                decisions += NotifyDecision.LadderUp(name)
            }
        }

        return NotifyPlan(decisions, next)
    }

    /**
     * The key a limit notice is filed under.
     *
     * The SESSION key, deliberately — the same one an attention notice uses — so
     * that opening the session takes both down and a session cannot end up with a
     * "needs you" and a "hit the limit" that outlive each other.
     */
    fun limitKey(name: String): String = sessionKey(name)

    /** The daemon's word for "this session is waiting on a human". */
    const val ATTENTION: String = "attention"
}
