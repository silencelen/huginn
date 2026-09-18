package com.silencelen.huginn

import com.silencelen.huginn.notify.ReplyStep
import com.silencelen.huginn.notify.replyStep
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The notification layer's decisions, without a Context.
 *
 * Posting needs Android; what to post does not, and everything asserted here is
 * a branch that was taken wrongly in a receiver or a watch cycle. Same
 * arrangement the rest of this source set uses.
 */
class ReplyReceiverRulesTest {

    @Test
    fun `a whitespace-only reply is not a send, and the notification comes back`() {
        // SystemUI enables the send button on RAW length, so every one of these
        // is submittable from the shade — and each returned before goAsync() and
        // before any update(), leaving the spinner running for good.
        assertEquals(ReplyStep.RESTORE, replyStep(" "))
        assertEquals(ReplyStep.RESTORE, replyStep("\t"))
        assertEquals(ReplyStep.RESTORE, replyStep("\n"))
        assertEquals(ReplyStep.RESTORE, replyStep(" "))
        assertEquals(ReplyStep.RESTORE, replyStep("   \t \n "))
    }

    @Test
    fun `a missing RemoteInput result is the same dead end`() {
        assertEquals(ReplyStep.RESTORE, replyStep(null))
        assertEquals(ReplyStep.RESTORE, replyStep(""))
    }

    @Test
    fun `an ordinary reply sends`() {
        assertEquals(ReplyStep.SEND, replyStep("ok"))
        assertEquals(ReplyStep.SEND, replyStep("  ok  "))
    }
}
