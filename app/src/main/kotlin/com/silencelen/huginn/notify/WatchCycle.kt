package com.silencelen.huginn.notify

import android.content.Context
import com.silencelen.huginn.data.SettingsStore
import com.silencelen.huginn.data.Watch
import kotlinx.coroutines.flow.first

/**
 * Turns one observation of huginn into notifications, if anything changed.
 *
 * Shared deliberately by the streaming watcher and the Doze-proof alarm. They are
 * two ways of *arriving* at an observation and there is no reason for them to
 * disagree about what it means — but when each had its own copy of this logic they
 * did, and a bug fixed in one stayed alive in the other. There is one place to be
 * wrong now.
 *
 * The comparison baseline is PERSISTED rather than kept in memory, which is what
 * makes an alarm-only cycle useful: if the watcher was killed while the phone slept,
 * the alarm still sees "this session was not waiting last time I looked, and is
 * now". Held in memory, that transition happened to a process that no longer exists
 * and would be lost.
 */
object WatchCycle {

    data class Outcome(
        val sessionsNeeding: Set<String>,
        val chatsRunning: Set<String>,
        val notified: Int,
        /** True when this was the very first look and everything was absorbed silently. */
        val seeded: Boolean,
    )

    /**
     * Which chats have finished since the previous observation.
     *
     * Two ways to notice, because each misses what the other catches. The run counter
     * sees a chat that began and ended entirely inside one gap — invisible to the
     * running set, and with a ten-minute background check that is ordinary rather
     * than exotic. The edge covers a chat whose count was never recorded.
     *
     * A chat with no previous count contributes nothing: absent a baseline, its
     * counter says only how many times it has ever run, and announcing that would
     * turn a first look into a burst of notifications about history. Pure, and
     * separate from [apply], so the rule can be tested without an Android context.
     */
    fun finishedSince(
        runsBefore: Map<String, Long>,
        runsNow: Map<String, Long>,
        previouslyRunning: Set<String>,
        running: Set<String>,
    ): Set<String> {
        val ranAgain = runsNow.filter { (id, now) -> (runsBefore[id] ?: now) < now }.keys
        return ranAgain + (previouslyRunning - running)
    }

    suspend fun apply(context: Context, settings: SettingsStore, watch: Watch): Outcome {
        val needing = watch.sessions.filterValues { it == "attention" }.keys
        val running = watch.chats.filterValues { it.running }.keys
        val runsNow = watch.chats.mapValues { it.value.finishedRuns }

        // Nothing has ever been observed, so there is no transition to speak of —
        // only a list of things that were already true. Announcing those would mean
        // switching the feature on produces a burst of notifications about the past.
        if (!settings.watchSeeded.first()) {
            settings.setNotifiedSessions(needing)
            settings.setRunningChats(running)
            settings.setChatRuns(runsNow)
            settings.setWatchSeeded(true)
            return Outcome(needing, running, notified = 0, seeded = true)
        }

        var posted = 0

        val previouslyNeeding = settings.notifiedSessions.first()
        val fresh = needing - previouslyNeeding
        if (fresh.isNotEmpty()) {
            SessionWatchWorker.post(
                context,
                if (fresh.size == 1) "${fresh.first()} needs you" else "${fresh.size} sessions need you",
                if (fresh.size == 1) "Waiting for your answer" else fresh.sorted().joinToString(", "),
                fresh.first(),
            )
            posted++
        }
        if (needing != previouslyNeeding) settings.setNotifiedSessions(needing)

        val previouslyRunning = settings.runningChats.first()
        val runsBefore = settings.chatRuns.first()

        val finished = finishedSince(runsBefore, runsNow, previouslyRunning, running)

        if (finished.isNotEmpty()) {
            // A chat that finished and was then deleted is no longer in the snapshot,
            // so a missing title is ordinary rather than a fault.
            val title = watch.chats[finished.first()]?.title
            SessionWatchWorker.post(
                context,
                if (finished.size == 1) "Chat finished" else "${finished.size} chats finished",
                title?.take(80) ?: "huginn has answered",
                null,
            )
            posted++
        }
        if (running != previouslyRunning) settings.setRunningChats(running)
        // Assigned wholesale rather than merged, so a deleted chat drops out instead
        // of accumulating forever.
        if (runsNow != runsBefore) settings.setChatRuns(runsNow)

        return Outcome(needing, running, notified = posted, seeded = false)
    }
}
