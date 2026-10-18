package com.silencelen.huginn

import com.silencelen.huginn.notify.AppLock
import com.silencelen.huginn.notify.ChatReplyAction
import com.silencelen.huginn.notify.ReplyGate
import com.silencelen.huginn.notify.ReplyReceiver
import com.silencelen.huginn.notify.chatReplyAction
import com.silencelen.huginn.notify.heldDraft
import com.silencelen.huginn.notify.replyGate
import com.silencelen.huginn.ui.HuginnViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shade's reply box and the app lock.
 *
 * The box's own `setAuthenticationRequired(true)` asks for the DEVICE to be
 * unlocked, never Huginn, so a phone handed over unlocked could send free text to
 * Claude past the app lock. These are the decisions ReplyReceiver and the
 * notification builder now make, without a Context.
 */
class ReplyLockTest {

    private val now = 1_800_000_000_000L

    private fun gate(
        enabled: Boolean = true,
        lockedNow: Boolean = false,
        onScreen: Boolean = false,
        awayAt: Long = now - 5_000,
    ) = replyGate(lockEnabled = enabled, lockedNow = lockedNow, onScreen = onScreen, awayAt = awayAt, now = now)

    @Test
    fun `with the lock off a reply sends, whatever else is true`() {
        assertEquals(ReplyGate.SEND, gate(enabled = false))
        assertEquals(ReplyGate.SEND, gate(enabled = false, lockedNow = true, awayAt = 0L))
    }

    @Test
    fun `a locked app sends nothing from the shade`() {
        // "Lock now", or a return past the grace that MainActivity already locked.
        assertEquals(ReplyGate.HOLD, gate(lockedNow = true))
        assertEquals(ReplyGate.HOLD, gate(lockedNow = true, onScreen = true))
    }

    @Test
    fun `away past the grace is locked even though nothing has drawn the lock yet`() {
        // The app was backgrounded and nobody has opened it since, so lockedNow is
        // still false — but opening it now would ask for the unlock, and so does this.
        assertEquals(ReplyGate.HOLD, gate(awayAt = now - AppLock.GRACE_MS))
        assertEquals(ReplyGate.HOLD, gate(awayAt = now - 3_600_000L))
    }

    @Test
    fun `a process with no memory of an unlock is locked`() {
        // The usual case: the reply's broadcast is what started the process.
        assertEquals(ReplyGate.HOLD, gate(awayAt = 0L))
    }

    @Test
    fun `a clock reading earlier than the stamp is locked`() {
        assertEquals(ReplyGate.HOLD, gate(awayAt = now + 1))
    }

    @Test
    fun `inside the grace, or open and unlocked on screen, a reply sends`() {
        // The same answer the app's own door gives: no unlock is asked for there,
        // and whoever holds the phone can already type into the app itself.
        assertEquals(ReplyGate.SEND, gate(awayAt = now - AppLock.GRACE_MS + 1))
        assertEquals(ReplyGate.SEND, gate(onScreen = true, awayAt = now - 3_600_000L))
    }

    @Test
    fun `a held reply becomes the chat's draft under the composer's own key`() {
        val held = heldDraft(emptyMap(), "c9", "delete the build dir")
        assertEquals(mapOf(HuginnViewModel.chatDraftKey("c9") to "delete the build dir"), held)
    }

    @Test
    fun `a held reply is appended to a draft already there, never over it`() {
        val drafts = mapOf("chat:c9" to "half a thought", "sess:main" to "untouched", "chat:other" to "also untouched")
        val held = heldDraft(drafts, "c9", "and the rest")
        assertEquals("half a thought\n\nand the rest", held["chat:c9"])
        assertEquals("untouched", held["sess:main"])
        assertEquals("also untouched", held["chat:other"])
    }

    @Test
    fun `no reply box is offered while the app lock is on`() {
        assertEquals(ChatReplyAction.OPEN_TO_REPLY, chatReplyAction(lockOn = true))
        assertEquals(ChatReplyAction.REPLY_BOX, chatReplyAction(lockOn = false))
        assertTrue(ReplyReceiver.OPEN_TO_REPLY.startsWith("Open Huginn"))
    }
}
