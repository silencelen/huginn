package com.silencelen.huginn

import com.silencelen.huginn.ui.ChatListScroll
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where the chat list lands when you arrive on it.
 *
 * The rule has to distinguish two arrivals that a naive "reset on enter" cannot
 * tell apart — coming to Chats from somewhere else, and coming back out of a
 * chat you opened from the list — and getting the second one wrong is worse than
 * doing nothing at all: it throws the reader to the top on every step of a
 * drill-in-and-out they are in the middle of.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class ChatListScrollTest {

    private val chats = ChatListScroll.CHATS

    @Test
    fun `arriving from another destination snaps to the latest chat`() {
        assertTrue(ChatListScroll.shouldSnap("sessions", chats), "the phone's Sessions tab")
        assertTrue(ChatListScroll.shouldSnap("status", chats))
        assertTrue(ChatListScroll.shouldSnap("rounds", chats))
        assertTrue(ChatListScroll.shouldSnap("settings", chats))
        assertTrue(ChatListScroll.shouldSnap("settings:usage", chats), "a drawer is still not the chat list")
        assertTrue(ChatListScroll.shouldSnap("projects", chats))
        assertTrue(ChatListScroll.shouldSnap("session:andrev", chats), "an open SESSION is not an open chat")
    }

    @Test
    fun `coming back from a chat keeps the position`() {
        // The whole reason this is a rule rather than a reset. Opening a chat and
        // pressing back is one step of reading down a run of them; a list that
        // jumps to the top each time is a list you stop going back to.
        assertFalse(ChatListScroll.shouldSnap("chat:fc322e02-cf53-4b09-a28d-6fc291cd66fa", chats))
        assertFalse(ChatListScroll.shouldSnap("chat:", chats), "a malformed id is still a chat")
    }

    @Test
    fun `a chat id that merely starts with the word chats is not the list`() {
        // "chats" and "chat:…" are one character apart at the point the decision
        // is made, and a prefix test written the other way round would treat an
        // id beginning "s…" as the list itself.
        assertFalse(ChatListScroll.isChat(chats))
        assertTrue(ChatListScroll.isChat("chat:chats"))
        assertFalse(ChatListScroll.shouldSnap("chat:chats", chats))
    }

    @Test
    fun `staying put is not an arrival`() {
        // A recomposition, or the same destination re-delivered by a notification
        // tap on the screen it is already showing. Nobody navigated.
        assertFalse(ChatListScroll.shouldSnap(chats, chats))
    }

    @Test
    fun `an unknown origin never moves the list`() {
        // A cold start (already at the top, so there is nothing to gain) and the
        // first composition after a fold or a rotate (where moving would undo the
        // saved-destination fix that rebuild exists to have).
        assertFalse(ChatListScroll.shouldSnap(null, chats))
    }

    @Test
    fun `no other destination is told where to scroll`() {
        // The rule answers for ONE list. A shell that asked it about the sessions
        // pane must get "no" rather than a coincidental yes.
        assertFalse(ChatListScroll.shouldSnap(chats, "sessions"))
        assertFalse(ChatListScroll.shouldSnap("status", "sessions"))
        assertFalse(ChatListScroll.shouldSnap("chat:abc", "chat:def"))
        assertFalse(ChatListScroll.shouldSnap("sessions", "chat:abc"), "an open chat is not the list")
        assertFalse(ChatListScroll.shouldSnap("sessions", "Chats"), "the key is the shell's, exact")
    }
}
