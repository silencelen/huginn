package com.silencelen.huginn

import com.silencelen.huginn.notify.AnswerReceiver
import com.silencelen.huginn.notify.AnswerReceiver.Step
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A notification's answer button, before anything is sent: an answer goes out
 * only bound to the question it was offered for.
 */
class AnswerReceiverDecisionTest {

    @Test
    fun `an answer with its fingerprint is sent`() {
        assertEquals(Step.SEND, AnswerReceiver.decide(2, "0123456789ab"))
    }

    @Test
    fun `A BLANK FINGERPRINT IS NEVER SENT AS NONE`() {
        // Sent without one, the host has nothing to check the pane against and
        // presses the digit on whatever dialog is there now.
        assertEquals(Step.OPEN_SESSION, AnswerReceiver.decide(1, ""))
        assertEquals(Step.OPEN_SESSION, AnswerReceiver.decide(1, "   "))
        assertEquals(Step.OPEN_SESSION, AnswerReceiver.decide(1, null))
    }

    @Test
    fun `no option is no answer at all`() {
        assertEquals(Step.IGNORE, AnswerReceiver.decide(0, "0123456789ab"))
        assertEquals(Step.IGNORE, AnswerReceiver.decide(-1, ""))
    }
}
