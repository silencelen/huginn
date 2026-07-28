package com.silencelen.huginn.notify

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.silencelen.huginn.data.HuginnClient
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

    /**
     * Applies an observation, optionally fetching the pending question.
     *
     * [client] is what lets a notification found by the alarm or the stream carry the
     * same question — and the same answer buttons — as one delivered by push. Without
     * it the two paths would say different things about the same event depending purely
     * on which noticed first, which is the drift this shared cycle exists to prevent.
     * Optional, so a caller with no client still works and simply gets the plain text.
     */
    suspend fun apply(
        context: Context,
        settings: SettingsStore,
        watch: Watch,
        client: HuginnClient? = null,
    ): Outcome {
        val needing = watch.sessions.filterValues { it == "attention" }.keys
        val running = watch.chats.filterValues { it.running }.keys
        val runsNow = watch.chats.mapValues { it.value.finishedRuns }

        // Recorded on EVERY observation, before anything else, and deliberately
        // outside the seeding shortcut below: the heartbeat re-arms itself from this
        // number the moment its own tick returns, so a cycle that skipped it would
        // re-arm on a stale tally. Every path that reaches an observation — stream,
        // alarm, worker, push reconcile — comes through here, which is the point.
        settings.notePushesSent(watch.pushesSent)

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

        // Questions that stopped waiting since the last look — answered in tmux,
        // from another device, or the session died. Their notifications come down,
        // here as well as on the push, because this is the path that runs when the
        // resolution push could not be delivered (app dead at the time) — the alarm
        // rediscovers the truth and the shade catches up.
        for (name in previouslyNeeding - needing) {
            runCatching {
                NotificationManagerCompat.from(context)
                    .cancel(SessionWatchWorker.notificationIdFor(name))
            }
        }

        val fresh = needing - previouslyNeeding
        // Withheld when the one session in question is the screen the reader has
        // open — its prompt is already rendered in front of them, buttons and all.
        // The baseline update below still runs either way, so the suppressed alert
        // is consumed rather than deferred: navigating away later must not make an
        // already-seen question suddenly buzz.
        val watching = fresh.size == 1 && Foreground.showsSession(fresh.first())
        if (fresh.isNotEmpty() && !watching) {
            val only = fresh.singleOrNull()
            // Only for a single session: with several waiting there is no one question
            // to show, and guessing which to offer buttons for would be worse than
            // offering none.
            val prompt = if (only != null && client != null) {
                runCatching { client.screen(only).prompt }.getOrNull()
            } else null

            SessionWatchWorker.post(
                context,
                if (only != null) "$only needs you" else "${fresh.size} sessions need you",
                prompt?.question?.takeIf { it.isNotBlank() }
                    ?: if (only != null) "Waiting for your answer" else fresh.sorted().joinToString(", "),
                fresh.first(),
                prompt?.options?.map { SessionWatchWorker.Companion.AnswerOption(it.number, it.label) }
                    ?: emptyList(),
                prompt?.fingerprint,
            )
            posted++
        }
        if (needing != previouslyNeeding) settings.setNotifiedSessions(needing)

        val previouslyRunning = settings.runningChats.first()
        val runsBefore = settings.chatRuns.first()

        val finished = finishedSince(runsBefore, runsNow, previouslyRunning, running)

        // Same rule for a chat whose finish the reader is already watching stream in.
        val watchingChat = finished.size == 1 && Foreground.showsChat(finished.first())
        if (finished.isNotEmpty() && !watchingChat) {
            // A chat that finished and was then deleted is no longer in the snapshot,
            // so a missing title is ordinary rather than a fault.
            val only = finished.singleOrNull()
            val chat = only?.let { watch.chats[it] }
            SessionWatchWorker.post(
                context,
                // Titled by the chat and bodied by its answer, matching what the push
                // path sends for the same event — the two must not describe the same
                // finish differently depending on which noticed it first.
                chat?.title?.take(60) ?: if (only != null) "Chat finished" else "${finished.size} chats finished",
                chat?.snippet ?: "huginn has answered",
                null,
                // Only when there is exactly one: a reply box has to know where the
                // reply goes, and with several finished at once there is no answer
                // to that which is not a guess.
                replyChat = only,
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
