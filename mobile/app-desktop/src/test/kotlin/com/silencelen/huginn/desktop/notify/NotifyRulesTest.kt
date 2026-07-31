package com.silencelen.huginn.desktop.notify

import com.silencelen.huginn.data.Watch
import com.silencelen.huginn.data.WatchChat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The notification router's decisions — every one of them a rule that exists
 * because a client got it wrong in the field.
 *
 * Pure, so none of this needs a tray, a socket or a daemon. That was the point of
 * splitting [NotifyRules] out of [NotifyRouter]: the half with a clock and a
 * network cannot be tested, and it is not the half where the mistakes live.
 *
 * NOTE the assertion order: kotlin.test is `assertEquals(expected, actual, message)`,
 * the REVERSE of JUnit's.
 */
class NotifyRulesTest {

    private fun watch(
        sessions: Map<String, String?> = emptyMap(),
        chats: Map<String, WatchChat> = emptyMap(),
    ) = Watch(hash = "h", sessions = sessions, chats = chats)

    private fun chat(running: Boolean = false, runs: Long = 0, title: String? = null, snippet: String? = null) =
        WatchChat(running = running, finishedRuns = runs, title = title, snippet = snippet)

    /** Applies a digest to a baseline and hands back both halves. */
    private fun step(
        previous: WatchBaseline,
        w: Watch,
        focused: NavTarget? = null,
        enabled: Boolean = true,
    ): NotifyPlan = NotifyRules.plan(previous, w, focused, enabled)

    private fun seed(w: Watch): WatchBaseline = step(WatchBaseline(), w).baseline

    // -------------------------------------------------------------- seeding

    @Test
    fun `the first observation announces nothing`() {
        // Without this, launching the app fires a wave of alerts about the PAST:
        // every session already waiting, every chat that has ever finished.
        val plan = step(
            WatchBaseline(),
            watch(
                sessions = mapOf("a" to "attention", "b" to "running"),
                chats = mapOf("c1" to chat(running = true, runs = 12)),
            ),
        )
        assertEquals(emptyList(), plan.decisions)
        assertTrue(plan.baseline.seeded)
        assertEquals(mapOf("c1" to 12L), plan.baseline.runs)
    }

    @Test
    fun `seeding happens even with notifications turned off`() {
        // Otherwise switching them on months later replays the whole history at
        // once — the exact failure seeding exists to prevent, deferred.
        val plan = step(WatchBaseline(), watch(sessions = mapOf("a" to "attention")), enabled = false)
        assertTrue(plan.baseline.seeded)
        assertEquals(emptyList(), plan.decisions)
    }

    // ------------------------------------------------------------- attention

    @Test
    fun `a session entering attention is announced once`() {
        var base = seed(watch(sessions = mapOf("a" to "running")))
        val first = step(base, watch(sessions = mapOf("a" to "attention")))
        assertEquals(listOf(NotifyDecision.Attention("a")), first.decisions)

        // Still waiting on the next digest is not a new event.
        base = first.baseline
        assertEquals(emptyList(), step(base, watch(sessions = mapOf("a" to "attention"))).decisions)
    }

    @Test
    fun `a session leaving attention withdraws its notification`() {
        val base = seed(watch(sessions = mapOf("a" to "attention")))
        val plan = step(base, watch(sessions = mapOf("a" to "idle")))
        assertEquals(listOf(NotifyDecision.Withdraw("sess:a")), plan.decisions)
    }

    @Test
    fun `a session that vanishes from the digest withdraws too`() {
        // Killed, renamed, or the daemon restarted. A "needs you" pointing at a
        // session that no longer exists is worse than none.
        val base = seed(watch(sessions = mapOf("a" to "attention")))
        assertEquals(
            listOf(NotifyDecision.Withdraw("sess:a")),
            step(base, watch(sessions = emptyMap())).decisions,
        )
    }

    @Test
    fun `withdrawals are planned before posts`() {
        val base = seed(watch(sessions = mapOf("a" to "attention", "b" to "idle")))
        val plan = step(base, watch(sessions = mapOf("a" to "idle", "b" to "attention")))
        assertEquals(
            listOf(NotifyDecision.Withdraw("sess:a"), NotifyDecision.Attention("b")),
            plan.decisions,
        )
    }

    // -------------------------------------------------------------- finishes

    @Test
    fun `A RUN THAT BEGAN AND ENDED BETWEEN TWO LOOKS IS STILL FOUND`() {
        // The whole reason finishes come from a COUNTER rather than a running-flag
        // edge: this chat was never observed running, so an edge detector sees
        // nothing at all.
        val base = seed(watch(chats = mapOf("c" to chat(running = false, runs = 4))))
        val plan = step(base, watch(chats = mapOf("c" to chat(running = false, runs = 5, title = "T", snippet = "S"))))
        assertEquals(listOf(NotifyDecision.Finished("c", "T", "S")), plan.decisions)
    }

    @Test
    fun `a run seen going from running to not running is found by the edge`() {
        val base = seed(watch(chats = mapOf("c" to chat(running = true, runs = 0))))
        val plan = step(base, watch(chats = mapOf("c" to chat(running = false, runs = 0))))
        assertEquals(listOf(NotifyDecision.Finished("c", null, null)), plan.decisions)
    }

    @Test
    fun `a chat with no previous count contributes nothing`() {
        // Absent a baseline its counter says only how many times it has EVER run,
        // and announcing that turns a first sighting into news about history.
        val base = seed(watch(chats = emptyMap()))
        assertEquals(emptyList(), step(base, watch(chats = mapOf("new" to chat(runs = 99)))).decisions)
    }

    @Test
    fun `a chat that merely keeps running says nothing`() {
        val base = seed(watch(chats = mapOf("c" to chat(running = true, runs = 1))))
        assertEquals(emptyList(), step(base, watch(chats = mapOf("c" to chat(running = true, runs = 1)))).decisions)
    }

    // ----------------------------------------------------------- suppression

    @Test
    fun `nothing fires for the target the reader is looking at`() {
        val base = seed(watch(sessions = mapOf("a" to "idle")))
        val focused = NavTarget(TargetKind.SESSIONS, "a")
        assertEquals(emptyList(), step(base, watch(sessions = mapOf("a" to "attention")), focused).decisions)

        val chatBase = seed(watch(chats = mapOf("c" to chat(runs = 1))))
        assertEquals(
            emptyList(),
            step(chatBase, watch(chats = mapOf("c" to chat(runs = 2))), NavTarget(TargetKind.CHATS, "c")).decisions,
        )
    }

    @Test
    fun `a suppressed alert is consumed, not deferred`() {
        // Navigating away later must not make an already-seen question buzz.
        val base = seed(watch(sessions = mapOf("a" to "idle")))
        val focused = NavTarget(TargetKind.SESSIONS, "a")
        val suppressed = step(base, watch(sessions = mapOf("a" to "attention")), focused)
        assertEquals(emptyList(), suppressed.decisions)
        // Same state, nobody looking now: still silent, because the edge is spent.
        assertEquals(emptyList(), step(suppressed.baseline, watch(sessions = mapOf("a" to "attention"))).decisions)
    }

    @Test
    fun `looking at one session does not suppress another`() {
        val base = seed(watch(sessions = mapOf("a" to "idle", "b" to "idle")))
        val plan = step(
            base,
            watch(sessions = mapOf("a" to "attention", "b" to "attention")),
            focused = NavTarget(TargetKind.SESSIONS, "a"),
        )
        assertEquals(listOf(NotifyDecision.Attention("b")), plan.decisions)
    }

    @Test
    fun `disabled suppresses posts but never withdrawals`() {
        // Turning notifications off must not strand whatever is already on screen.
        val base = seed(watch(sessions = mapOf("a" to "attention", "b" to "idle")))
        val plan = step(
            base,
            watch(sessions = mapOf("a" to "idle", "b" to "attention")),
            enabled = false,
        )
        assertEquals(listOf(NotifyDecision.Withdraw("sess:a")), plan.decisions)
    }

    @Test
    fun `keys name the list they belong to`() {
        assertEquals("sess:a", NotifyRules.sessionKey("a"))
        assertEquals("chat:a", NotifyRules.chatKey("a"))
    }
}
