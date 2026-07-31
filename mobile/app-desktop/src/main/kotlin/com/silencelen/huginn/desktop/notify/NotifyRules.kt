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
)

/** One thing to do about a digest. */
sealed interface NotifyDecision {
    /** A session just started waiting on a question. Blocking: sound, and it stays up. */
    data class Attention(val session: String) : NotifyDecision

    /** A chat finished a run. News: silent. */
    data class Finished(val chatId: String, val title: String?, val snippet: String?) : NotifyDecision

    /** The world moved on — take the notification down before the reader acts on it. */
    data class Withdraw(val key: String) : NotifyDecision
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
        val next = WatchBaseline(
            seeded = true,
            sessions = watch.sessions,
            runs = runsNow,
            running = runningNow,
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

        return NotifyPlan(decisions, next)
    }

    /** The daemon's word for "this session is waiting on a human". */
    const val ATTENTION: String = "attention"
}
