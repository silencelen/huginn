package com.silencelen.huginn

import com.silencelen.huginn.notify.ReplyStep
import com.silencelen.huginn.notify.WatchNotifier
import com.silencelen.huginn.notify.replyStep
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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

/**
 * THE OBSERVATION GATE.
 *
 * `WatchNotifier.apply` itself needs a Context; what it needed and did not have
 * is mutual exclusion, and that is assertable on its own. Five in-process
 * callers reach the cycle — the watch service, the heartbeat, the session
 * worker, the widget worker and the push reconcile — and each one reads three
 * persisted baselines, makes network calls, then writes them back.
 */
class WatchNotifierTest {

    @Test
    fun `two cycles observing the same transition do not interleave`() = runTest {
        // A baseline read-modify-write with a suspension in the middle, exactly
        // like the real one: read notifiedSessions, fetch prompts, write it back.
        var baseline = 0
        var bothInsideAtOnce = false
        var inside = 0

        suspend fun cycle() = WatchNotifier.guarded {
            inside++
            if (inside > 1) bothInsideAtOnce = true
            val seen = baseline
            yield()
            yield()
            baseline = seen + 1
            inside--
        }

        launch { cycle() }
        launch { cycle() }

        runCurrent()
        assertEquals("both cycles must land", 2, baseline)
        assertEquals(false, bothInsideAtOnce)
    }

    @Test
    fun `the gate is not held once a cycle is done`() = runTest {
        var ran = 0
        WatchNotifier.guarded { ran++ }
        WatchNotifier.guarded { ran++ }
        assertEquals(2, ran)
    }
}
