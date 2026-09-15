package com.silencelen.huginn

import com.silencelen.huginn.data.Watch
import com.silencelen.huginn.data.WatchHeadroom
import com.silencelen.huginn.notify.Foreground
import com.silencelen.huginn.notify.HeadroomNotices
import com.silencelen.huginn.notify.SessionWatchWorker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the phone actually puts on the shade when a usage limit bites.
 *
 * This host has no device and no emulator, so a notification cannot be built and
 * inspected here — which is why the DECISION and the ARGUMENTS are pure and the
 * only Android left in `SessionWatchWorker.post` is the drawing. Every rule below
 * is one that would otherwise only be checkable by holding a phone.
 */
class HeadroomNoticesTest {

    @After
    fun clearForeground() {
        Foreground.resumed = false
        Foreground.session = null
        Foreground.chat = null
    }

    private fun watch(
        sessions: Map<String, String?> = emptyMap(),
        stalled: List<String> = emptyList(),
        stalls: Map<String, String?> = emptyMap(),
        laddered: Map<String, String> = emptyMap(),
    ) = Watch(
        sessions = sessions,
        headroom = WatchHeadroom(stalled = stalled, stalls = stalls, laddered = laddered),
    )

    // -------------------------------------------------------- the four kinds

    /**
     * Each kind, and the buttons it is allowed to carry.
     *
     * The downgrade is the only one of the four the reader may want to reverse,
     * so it is the only one that gets buttons from the digest — and it gets
     * exactly two, both bounded, neither of them free text. That last part is the
     * owner's rule and the reason a notification button works on a locked phone
     * at all, so it is asserted structurally: `PostArgs.replyChat` is what makes
     * `post` attach a `RemoteInput` and the AppLock gate behind it, and a
     * headroom notice must never carry one.
     */
    @Test
    fun `each headroom kind builds its own notice, and the downgrade's two buttons are bounded`() {
        val limit = HeadroomNotices.limitNotice("btclab", "2026-09-15T10:30:00Z", nowMs = 1_789_460_000_000L)
        assertEquals(HeadroomNotices.Kind.LIMIT, limit.kind)
        assertTrue(limit.title.startsWith("btclab"))
        assertTrue("a limit notice with no reset time answers nothing", limit.text.isNotBlank())
        assertTrue("nothing to undo about a stall", limit.actions.isEmpty())

        val resumed = HeadroomNotices.resumedNotice(listOf("btclab", "w1kcore"))
        assertEquals(HeadroomNotices.Kind.RESUMED, resumed.kind)
        assertEquals("one event, one key, however many came back", HeadroomNotices.RESUMED_KEY, resumed.key)
        assertTrue(resumed.text.contains("btclab") && resumed.text.contains("w1kcore"))
        assertTrue(resumed.actions.isEmpty())

        val down = HeadroomNotices.downgradedNotice("w1kcore", "opus")
        assertEquals(HeadroomNotices.Kind.DOWNGRADED, down.kind)
        assertTrue(down.title.contains("opus"))
        assertEquals("exactly two: put it back, or accept it", 2, down.actions.size)
        assertEquals(listOf("Undo", "OK"), down.actions.map { it.label })
        assertEquals(
            listOf(HeadroomNotices.VERB_UNDO, HeadroomNotices.VERB_ACK),
            down.actions.map { it.verb },
        )
        assertTrue(
            "every verb must be one of the bounded three",
            down.actions.all { it.verb in HeadroomNotices.VERBS },
        )
        assertTrue("both buttons reach the same named session", down.actions.all { it.session == "w1kcore" })

        // The offer, which is the only ladder-up that asks anything.
        val offer = HeadroomNotices.ladderUpNotice("w1kcore", offered = true)
        assertEquals(HeadroomNotices.Kind.LADDER_UP, offer.kind)
        assertEquals(listOf("Back to Fable", "Stay"), offer.actions.map { it.label })
        assertEquals(
            listOf(HeadroomNotices.VERB_BACK, HeadroomNotices.VERB_ACK),
            offer.actions.map { it.verb },
        )
        val done = HeadroomNotices.ladderUpNotice("w1kcore", offered = false)
        assertTrue("appd moving a session back is news, not a question", done.actions.isEmpty())

        // NO FREE TEXT, on any of them. `replyChat` is the field that makes
        // `post` attach a RemoteInput; `answers`/`fingerprint` are the pane-answer
        // pair. A headroom notice carries none of the three.
        for (n in listOf(limit, resumed, down, offer, done)) {
            val a = HeadroomNotices.postArgs(n)
            assertNull("a headroom notice must never grow a reply box", a.replyChat)
            assertTrue(a.answers.isEmpty())
            assertNull(a.fingerprint)
            assertEquals("news, not 'needs you'", true, a.isResult)
            assertEquals(n.key, a.key)
        }

        // And the push path lands on the same notices, so the two ways of
        // noticing the same event cannot describe it differently.
        val pushed = HeadroomNotices.fromPush(
            kind = "headroom_downgraded",
            title = "Moved w1kcore to opus",
            text = "Its Fable week ran out.",
            subject = "w1kcore",
            payload = "{\"session\":\"w1kcore\",\"to\":\"opus\",\"from\":\"fable\"}",
            options = "[\"Undo\",\"OK\"]",
        )
        assertNotNull(pushed)
        assertEquals(HeadroomNotices.Kind.DOWNGRADED, pushed!!.kind)
        assertEquals("Moved w1kcore to opus", pushed.title)
        assertEquals("w1kcore", pushed.session)
        assertEquals(listOf("Undo", "OK"), pushed.actions.map { it.label })
        assertEquals(
            listOf(HeadroomNotices.VERB_UNDO, HeadroomNotices.VERB_ACK),
            pushed.actions.map { it.verb },
        )
        assertNull(
            "not a headroom kind",
            HeadroomNotices.fromPush("chat_finished", "t", "x", "c1"),
        )
    }

    /**
     * The wire may name a BUTTON. It may never name an ACTION.
     *
     * The daemon sends the labels — `["Undo","OK"]`, `["Back to Fable","Stay"]` —
     * and those are drawn as they came, because the host is what knows whether it
     * moved a session or is merely offering to move it back. Which verb each one
     * runs is decided here and only here, by POSITION: a payload that could name
     * `undo` would be a request arriving over the network to change a session's
     * model, and the entire reason these buttons work on a locked phone is that
     * no such request exists.
     */
    @Test
    fun `the push names the labels, never the verbs`() {
        val offer = HeadroomNotices.fromPush(
            kind = "headroom_ladder_up",
            title = "Back to Fable?",
            text = "The Fable week has reset.",
            subject = "w1kcore",
            payload = "{\"session\":\"w1kcore\",\"from\":\"opus\",\"to\":\"fable\"}",
            options = "[\"Back to Fable\",\"Stay\"]",
        )!!
        assertEquals(listOf("Back to Fable", "Stay"), offer.actions.map { it.label })
        assertEquals(
            listOf(HeadroomNotices.VERB_BACK, HeadroomNotices.VERB_ACK),
            offer.actions.map { it.verb },
        )

        // A payload naming a verb changes nothing: verbs are positional, and the
        // count is this client's.
        val hostile = HeadroomNotices.fromPush(
            kind = "headroom_downgraded",
            title = "Moved w1kcore to opus",
            text = "…",
            subject = "w1kcore",
            payload = "{\"session\":\"w1kcore\",\"to\":\"opus\",\"verb\":\"undo\"}",
            options = "[\"undo\",\"undo\",\"undo\"]",
        )!!
        assertEquals("still exactly two buttons", 2, hostile.actions.size)
        assertEquals(
            "and the second still only dismisses",
            HeadroomNotices.VERB_ACK,
            hostile.actions[1].verb,
        )

        // `payload.session` wins over `subject` — it is the field the daemon
        // fills on purpose, and it survives a rename between send and delivery.
        val renamed = HeadroomNotices.fromPush(
            kind = "headroom_limit",
            title = "",
            text = "",
            subject = "old-name",
            payload = "{\"session\":\"new-name\"}",
        )!!
        assertEquals("new-name", renamed.session)
        assertTrue("a blank title falls back to this client's own", renamed.title.contains("new-name"))

        // Malformed or absent options keep this client's own words rather than
        // dropping the buttons: a notice with no way to undo is worse than one
        // whose labels are in the app's voice.
        val garbled = HeadroomNotices.fromPush(
            kind = "headroom_downgraded",
            title = "t", text = "x", subject = "w1kcore",
            options = "not json at all",
        )!!
        assertEquals(listOf("Undo", "OK"), garbled.actions.map { it.label })
    }

    // ------------------------------------------------------- the edge rules

    /**
     * All four are EDGES, and the first look announces nothing.
     *
     * The digest is a snapshot: every one of these would otherwise re-fire on
     * every poll for as long as the condition lasted, and switching notifications
     * on would produce a burst of news about the past.
     */
    @Test
    fun `the decisions are edges against a persisted baseline`() {
        val w = watch(
            sessions = mapOf("btclab" to null, "w1kcore" to null),
            stalled = listOf("btclab"),
            stalls = mapOf("btclab" to "2026-09-15T10:30:00Z"),
            laddered = mapOf("w1kcore" to "opus"),
        )

        // First look ever: record, announce nothing.
        val seed = HeadroomNotices.plan(HeadroomNotices.Baseline(), w, seeded = false)
        assertTrue("the first look is a list of things already true", seed.notices.isEmpty())
        assertEquals(setOf("btclab"), seed.next.stalled)
        assertEquals(mapOf("w1kcore" to "opus"), seed.next.laddered)

        // Same digest again, now seeded from that first look: still nothing.
        val quiet = HeadroomNotices.plan(seed.next, w, seeded = true)
        assertTrue("an unchanged digest is not an event", quiet.notices.isEmpty())

        // The edges themselves, from an empty baseline.
        val fresh = HeadroomNotices.plan(HeadroomNotices.Baseline(), w, seeded = true)
        assertEquals(2, fresh.notices.size)
        val hit = fresh.notices.first { it.kind == HeadroomNotices.Kind.LIMIT }
        assertEquals("btclab", hit.session)
        assertTrue("the reset time is the point of the notice", hit.text.contains("Resets in"))
        assertNotNull(fresh.notices.firstOrNull { it.kind == HeadroomNotices.Kind.DOWNGRADED })

        // A change of RUNG counts too: fable → opus → sonnet is two moves, and
        // reporting only the first leaves the reader believing it is on opus.
        val lower = HeadroomNotices.plan(
            fresh.next,
            watch(sessions = mapOf("w1kcore" to null), laddered = mapOf("w1kcore" to "sonnet")),
            seeded = true,
        )
        assertEquals(
            "sonnet",
            lower.notices.first { it.kind == HeadroomNotices.Kind.DOWNGRADED }.title.substringAfterLast(' '),
        )

        // The stall clearing: ONE resume notice, and the limit notice withdrawn.
        val back = HeadroomNotices.plan(
            fresh.next,
            watch(sessions = mapOf("btclab" to null, "w1kcore" to null), laddered = mapOf("w1kcore" to "opus")),
            seeded = true,
        )
        assertEquals(listOf("btclab"), back.withdraw.map { it.key })
        val resumed = back.notices.first { it.kind == HeadroomNotices.Kind.RESUMED }
        assertTrue(resumed.text.contains("btclab"))

        // A push has already said so: the reconcile right behind it must not
        // repeat the downgrade. The claim survives exactly one pass.
        val claimed = HeadroomNotices.plan(
            HeadroomNotices.Baseline(laddered = mapOf("w1kcore" to HeadroomNotices.PUSHED)),
            watch(sessions = mapOf("w1kcore" to null), laddered = mapOf("w1kcore" to "opus")),
            seeded = true,
        )
        assertTrue("the push already announced this", claimed.notices.isEmpty())
        assertEquals("and the real family lands immediately", mapOf("w1kcore" to "opus"), claimed.next.laddered)

        // A laddered session that has left the digest entirely is not a
        // ladder-up: it ended, and "back on Fable" about something that no longer
        // exists is a notification with nowhere to go.
        val gone = HeadroomNotices.plan(fresh.next, watch(sessions = emptyMap()), seeded = true)
        assertTrue(gone.notices.none { it.kind == HeadroomNotices.Kind.LADDER_UP })
    }

    // ------------------------------------------------- the focused-target rule

    /**
     * A limit notice about the session on screen says nothing the screen does not.
     *
     * The same rule the attention notices obey, and CONSUMED rather than
     * deferred: the baseline advances either way, so navigating away later must
     * not make an already-seen event suddenly buzz.
     */
    @Test
    fun `a limit for the session on screen is withheld but still consumed`() {
        val w = watch(
            sessions = mapOf("btclab" to null),
            stalled = listOf("btclab"),
            stalls = mapOf("btclab" to "2026-09-15T10:30:00Z"),
        )

        Foreground.resumed = true
        Foreground.session = "btclab"
        assertEquals("btclab", HeadroomNotices.focusedSession())

        val watching = HeadroomNotices.plan(
            HeadroomNotices.Baseline(), w, seeded = true, focused = HeadroomNotices.focusedSession(),
        )
        assertTrue("nothing to say about the screen in front of you", watching.notices.isEmpty())
        assertEquals(
            "consumed, not deferred — walking away must not make it buzz",
            setOf("btclab"),
            watching.next.stalled,
        )

        // Pocketed: the destination is still composed, but nobody is looking.
        Foreground.resumed = false
        assertNull(HeadroomNotices.focusedSession())
        val pocketed = HeadroomNotices.plan(
            HeadroomNotices.Baseline(), w, seeded = true, focused = HeadroomNotices.focusedSession(),
        )
        assertEquals(1, pocketed.notices.size)
        assertEquals(HeadroomNotices.Kind.LIMIT, pocketed.notices.first().kind)
    }

    // --------------------------------------------------------- read = dismissed

    /**
     * Opening the session takes its limit notice down, for free.
     *
     * `MainActivity` cancels `notificationIdFor(<session>)` on every navigation to
     * a session — read is dismissed — so a limit notice filed under the bare
     * session name needs no dismissal logic of its own. A LADDER notice is
     * deliberately filed elsewhere: sharing the slot would let "moved to opus"
     * replace a "needs you" that is still waiting for an answer.
     */
    @Test
    fun `a limit notice lands in the slot opening the session clears`() {
        val name = "btclab"
        val limit = HeadroomNotices.limitNotice(name, null)
        assertEquals("the session's own key", name, limit.key)
        assertEquals(
            "so the read-is-dismissed effect cancels it",
            SessionWatchWorker.notificationIdFor(name),
            SessionWatchWorker.notificationIdFor(limit.key),
        )

        val ladder = HeadroomNotices.downgradedNotice(name, "opus")
        assertEquals(HeadroomNotices.ladderKey(name), ladder.key)
        assertTrue(
            "a ladder notice must not evict a waiting question",
            SessionWatchWorker.notificationIdFor(ladder.key) !=
                SessionWatchWorker.notificationIdFor(name),
        )

        // And the withdrawal the digest raises when a stall clears uses that same
        // slot, so a session cannot end up with two standing limit notices.
        val w = watch(sessions = mapOf(name to null))
        val plan = HeadroomNotices.plan(
            HeadroomNotices.Baseline(stalled = setOf(name)), w, seeded = true,
        )
        assertEquals(listOf(name), plan.withdraw.map { it.key })
    }
}
