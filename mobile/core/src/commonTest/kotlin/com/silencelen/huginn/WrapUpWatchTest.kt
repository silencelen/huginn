package com.silencelen.huginn

import com.silencelen.huginn.data.Session
import com.silencelen.huginn.ui.WrapUpWatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ⚠⚠ P-19. A CANCELLED AUTO-END LOOKED EXACTLY LIKE A SUCCESSFUL ONE.
 *
 * The walker tapped Wrap up, it ran and finished, and the daemon logged
 * `soft-end: rv-phone-1 asked a question, auto-end cancelled` — and the app said
 * nothing at all: the winding-down badge simply went away. Somebody who put the
 * phone down would believe the session had ended.
 *
 * The daemon reports only `softEnding` going false, which is THREE endings
 * wearing one change, so every case here is about telling them apart. The one
 * that must never fire is the false positive: "your wrap-up did not finish" on a
 * session that ended perfectly well.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class WrapUpWatchTest {

    private fun session(name: String, softEnding: Boolean = false, state: String? = null) =
        Session(name = name, softEnding = softEnding, state = state)

    @Test
    fun `a wrap-up that turned into a question is reported`() {
        val before = WrapUpWatch.winding(listOf(session("rv-phone-1", softEnding = true, state = "running")))
        assertEquals(setOf("rv-phone-1"), before)
        val after = listOf(session("rv-phone-1", softEnding = false, state = "attention"))
        assertEquals(setOf("rv-phone-1"), WrapUpWatch.cancelled(before, after))
    }

    /**
     * ⚠ THE FALSE POSITIVE THAT MUST NEVER FIRE. A settled auto-end KILLS the
     * session, so it is gone from the list — and a rule that fired on "was
     * winding down, now is not" would announce a failure on every successful
     * wrap-up there has ever been.
     */
    @Test
    fun `a session that actually ended reports nothing`() {
        val before = setOf("rv-phone-1")
        assertEquals(emptySet(), WrapUpWatch.cancelled(before, emptyList()), "killed — it is not in the list")
    }

    /**
     * The third ending: `expire` — the session never started a run, so the
     * auto-end was dropped. Still alive, still not winding down, and NOT asking
     * anything, which is what separates it from a cancel.
     */
    @Test
    fun `an auto-end that expired is not a cancelled one`() {
        val before = setOf("rv-phone-1")
        val after = listOf(session("rv-phone-1", softEnding = false, state = "idle"))
        assertEquals(emptySet(), WrapUpWatch.cancelled(before, after))
    }

    @Test
    fun `a session still winding down says nothing yet`() {
        val before = setOf("rv-phone-1")
        val after = listOf(session("rv-phone-1", softEnding = true, state = "attention"))
        assertEquals(emptySet(), WrapUpWatch.cancelled(before, after), "the tick has not run")
    }

    /**
     * A session asking a question that nobody asked to wrap up is an ordinary
     * question. Without the carried set this would fire on every prompt in the
     * fleet.
     */
    @Test
    fun `a question on a session nobody wound down is just a question`() {
        assertEquals(
            emptySet(),
            WrapUpWatch.cancelled(emptySet(), listOf(session("huginnv20", state = "attention"))),
        )
    }

    @Test
    fun `several at once are all reported`() {
        val before = setOf("a", "b", "c")
        val after = listOf(
            session("a", state = "attention"),
            session("b", state = "running"),
            session("c", state = "attention"),
        )
        assertEquals(setOf("a", "c"), WrapUpWatch.cancelled(before, after))
    }

    @Test
    fun `winding records only the ones that are`() {
        val rows = listOf(
            session("a", softEnding = true),
            session("b"),
            session("c", softEnding = true),
        )
        assertEquals(setOf("a", "c"), WrapUpWatch.winding(rows))
    }

    /** It must never read as "retry the wrap-up" — the wrap-up worked. */
    @Test
    fun `the notice says the session is still there, and never that it failed`() {
        val words = WrapUpWatch.NOTICE.lowercase()
        assertTrue("still running" in words, WrapUpWatch.NOTICE)
        assertTrue("asked a question" in words, WrapUpWatch.NOTICE)
        assertFalse("failed" in words, WrapUpWatch.NOTICE)
        assertFalse("try again" in words, WrapUpWatch.NOTICE)
    }
}
