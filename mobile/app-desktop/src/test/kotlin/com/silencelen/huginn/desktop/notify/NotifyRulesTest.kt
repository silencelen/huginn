package com.silencelen.huginn.desktop.notify

import com.silencelen.huginn.data.Watch
import com.silencelen.huginn.data.WatchChat
import com.silencelen.huginn.data.WatchHeadroom
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
        headroom: WatchHeadroom? = null,
    ) = Watch(hash = "h", sessions = sessions, chats = chats, headroom = headroom)

    private fun hr(
        stalled: List<String> = emptyList(),
        stalls: Map<String, String?> = emptyMap(),
        laddered: Map<String, String> = emptyMap(),
        mode: String = "ok",
    ) = WatchHeadroom(mode = mode, stalled = stalled, stalls = stalls, laddered = laddered)

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

    // ------------------------------------------------------------- headroom
    //
    // Four new decisions, all of them EDGES. The digest is a snapshot: every one
    // of these would re-fire on every poll for as long as its condition lasted if
    // it were read as a state, which is the failure mode "3 sessions need you"
    // already taught this file once.

    private val live = mapOf("a" to "idle", "b" to "idle")

    @Test
    fun `a session that has just stalled is announced with its reset time`() {
        // The most valuable notice in the wave: a stalled session looks exactly
        // like an idle one, and work sat untouched overnight after a cap that
        // cleared at half past midnight.
        val base = seed(watch(sessions = live, headroom = hr()))
        val plan = step(
            base,
            watch(
                sessions = live,
                headroom = hr(stalled = listOf("a"), stalls = mapOf("a" to "2026-09-15T10:30:00Z")),
            ),
        )
        assertEquals(listOf(NotifyDecision.LimitHit("a", "2026-09-15T10:30:00Z")), plan.decisions)

        // And NOT again on the next digest saying the same thing.
        assertEquals(emptyList(), step(plan.baseline, watch(sessions = live, headroom = hr(stalled = listOf("a")))).decisions)
    }

    @Test
    fun `a limit notice is withdrawn when the stall clears`() {
        // A "hit the limit" that outlives its stall is the same failure as an
        // attention that outlives its question: the reader opens it to find a
        // session that has been working again for an hour.
        val base = step(
            seed(watch(sessions = live, headroom = hr())),
            watch(sessions = live, headroom = hr(stalled = listOf("a"))),
        ).baseline
        val plan = step(base, watch(sessions = live, headroom = hr()))
        assertTrue(NotifyDecision.Withdraw("sess:a") in plan.decisions)
        // Filed under the SESSION key, so opening the session takes it down like
        // any other notice about that session.
        assertEquals("sess:a", NotifyRules.limitKey("a"))
    }

    @Test
    fun `sessions that come back together are one notice, not three`() {
        // A window resetting is ONE event. Three toasts about it is the same news
        // three times, at whatever hour the window happened to reset.
        val base = step(
            seed(watch(sessions = mapOf("a" to "idle", "b" to "idle", "c" to "idle"), headroom = hr())),
            watch(
                sessions = mapOf("a" to "idle", "b" to "idle", "c" to "idle"),
                headroom = hr(stalled = listOf("a", "b", "c")),
            ),
        ).baseline
        val plan = step(base, watch(sessions = mapOf("a" to "idle", "b" to "idle", "c" to "idle"), headroom = hr()))
        assertEquals(1, plan.decisions.filterIsInstance<NotifyDecision.Resumed>().size)
        assertEquals(
            listOf("a", "b", "c"),
            plan.decisions.filterIsInstance<NotifyDecision.Resumed>().single().sessions,
        )
    }

    @Test
    fun `a session that ended while stalled is withdrawn, never announced as resumed`() {
        // "Resumed: jtyper" about a session that was killed is a notification
        // pointing at nothing, and the target it carries no longer exists.
        val base = step(
            seed(watch(sessions = mapOf("a" to "idle"), headroom = hr())),
            watch(sessions = mapOf("a" to "idle"), headroom = hr(stalled = listOf("a"))),
        ).baseline
        val plan = step(base, watch(sessions = emptyMap(), headroom = hr()))
        assertTrue(plan.decisions.none { it is NotifyDecision.Resumed })
        assertTrue(NotifyDecision.Withdraw("sess:a") in plan.decisions)
    }

    @Test
    fun `a downgrade carries the session the undo button has to name`() {
        // The whole reason this decision holds a NAME rather than a count: the
        // toast's Undo posts to /v1/sessions/:name/headroom/undo, and a decision
        // that only said "something was downgraded" could not build that button.
        val base = seed(watch(sessions = live, headroom = hr()))
        val plan = step(base, watch(sessions = live, headroom = hr(laddered = mapOf("a" to "opus"))))
        assertEquals(listOf(NotifyDecision.Downgraded("a", "opus")), plan.decisions)
    }

    @Test
    fun `a second rung is its own event and going back up is the reverse`() {
        val first = step(
            seed(watch(sessions = live, headroom = hr())),
            watch(sessions = live, headroom = hr(laddered = mapOf("a" to "opus"))),
        )
        // fable → opus → sonnet is TWO moves. Reporting only the first leaves the
        // reader believing the session is still on opus.
        val second = step(first.baseline, watch(sessions = live, headroom = hr(laddered = mapOf("a" to "sonnet"))))
        assertEquals(listOf(NotifyDecision.Downgraded("a", "sonnet")), second.decisions)

        val up = step(second.baseline, watch(sessions = live, headroom = hr()))
        assertEquals(listOf(NotifyDecision.LadderUp("a")), up.decisions)

        // A session that left the digest entirely did not ladder up — it ended.
        val gone = step(second.baseline, watch(sessions = emptyMap(), headroom = hr()))
        assertTrue(gone.decisions.none { it is NotifyDecision.LadderUp })
    }

    @Test
    fun `the first look at a red host announces none of it`() {
        // The seeding rule, at the place it matters most: launching the app while
        // two sessions are stalled and one is laddered must not fire three toasts
        // about things that happened before it started.
        val plan = step(
            WatchBaseline(),
            watch(
                sessions = live,
                headroom = hr(
                    mode = "red",
                    stalled = listOf("a", "b"),
                    laddered = mapOf("a" to "opus"),
                ),
            ),
        )
        assertEquals(emptyList(), plan.decisions)
        // And the baseline absorbed it, so the next identical digest is silent too.
        assertEquals(setOf("a", "b"), plan.baseline.stalled)
        assertEquals(mapOf("a" to "opus"), plan.baseline.laddered)
    }

    @Test
    fun `a daemon with no headroom block decides nothing new`() {
        // The compat answer. `watch.headroom` is null on 2.85.0, every headroom
        // baseline stays empty, and nothing here can ever appear or disappear —
        // the file behaves exactly as it did before this section existed.
        val base = seed(watch(sessions = live))
        val plan = step(base, watch(sessions = mapOf("a" to "attention", "b" to "idle")))
        assertEquals(listOf(NotifyDecision.Attention("a")), plan.decisions)
        assertEquals(emptySet(), plan.baseline.stalled)
        assertEquals(emptyMap(), plan.baseline.laddered)
    }
}
