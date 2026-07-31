package com.silencelen.huginn.notify

/**
 * What one observation of huginn MEANS — the rules, with no Android and no I/O.
 *
 * Every client watches the daemon the same way and must agree about what it saw;
 * the platform-specific part is only how the observation arrives and what gets
 * done about it. On Android that half is [WatchNotifier] (which needs a Context
 * to post notifications); on the desktop it is the SSE watch loop and the toast
 * router. The name is deliberate: the desktop client already calls this same rule
 * `watchCycle` (desktop/src/shared/core/watchCycle.ts, ported from here), so
 * there is one word for one concept across both codebases.
 *
 * Shared deliberately: the streaming watcher and the Doze-proof alarm are two
 * ways of *arriving* at an observation and there is no reason for them to
 * disagree about what it means — but when each had its own copy of this logic
 * they did, and a bug fixed in one stayed alive in the other. There is one place
 * to be wrong now.
 */
object WatchCycle {

    /** What an applied observation turned out to contain. */
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
     * separate from [WatchNotifier.apply], so the rule can be tested without an
     * Android context — which is now enforced rather than merely intended, since
     * this module cannot see Android at all.
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
}
