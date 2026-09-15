package com.silencelen.huginn

import com.silencelen.huginn.data.Headroom
import com.silencelen.huginn.data.HeadroomAccount
import com.silencelen.huginn.data.HeadroomWindow
import com.silencelen.huginn.data.HeadroomWindows
import com.silencelen.huginn.data.StatusHeadroom
import com.silencelen.huginn.ui.HeadroomRules
import com.silencelen.huginn.ui.SessionUsageFill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The fill line under the Status icon.
 *
 * Every case here is a way of drawing a CONFIDENT WRONG NUMBER in a place nobody
 * would think to check it: the worst account's window instead of the live one's,
 * the Fable week's colour on a fresh session, a bar wider than its track, or a
 * bar at zero drawn by a daemon that has never looked.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class SessionUsageFillTest {

    private fun window(pct: Double, severity: String = "normal") =
        HeadroomWindow(percent = pct, severity = severity, label = "Current session")

    private fun headroom(
        liveSession: HeadroomWindow? = window(31.0),
        spareSession: HeadroomWindow? = window(4.0),
        mode: String = "red",
    ) = Headroom(
        mode = mode,
        accounts = mapOf(
            "spare" to HeadroomAccount(
                live = false,
                windows = HeadroomWindows(session = spareSession),
            ),
            "owner-max" to HeadroomAccount(
                live = true,
                windows = HeadroomWindows(
                    session = liveSession,
                    weeklyFable = HeadroomWindow(percent = 92.0, severity = "critical"),
                ),
            ),
        ),
    )

    @Test
    fun `the reading is the LIVE account's session window`() {
        // The whole point. Two saved logins, and the one work runs on is the one
        // the bar must report — reading the first row, or the worst one, gives a
        // number that is true about somebody else's account.
        val fill = SessionUsageFill.of(headroom(), null)
        assertNotNull(fill)
        assertEquals(0.31f, fill!!.fraction, 0.0001f)
    }

    @Test
    fun `the colour comes from the session window, not the worst one`() {
        // `headroom.mode` is "red" here because the FABLE WEEK is at 92 %. Painting
        // a 31 % session red because of it is the exact confusion this line exists
        // to clear up.
        assertEquals(HeadroomRules.OK, SessionUsageFill.of(headroom(), null)!!.modeKey)

        // And a session genuinely past the ladder threshold does go red.
        val hot = headroom(liveSession = window(94.0))
        assertEquals(HeadroomRules.RED, SessionUsageFill.of(hot, null)!!.modeKey)
    }

    @Test
    fun `an exceeded window is exhausted even while the number rounds under 100`() {
        // Claude's own word beats our arithmetic: `exceeded` arrives while the
        // percentage still reads 99.4, and a bar that says "nearly" about a window
        // that is spent is the one thing it must not say.
        val spent = headroom(liveSession = window(99.4, severity = "exceeded"))
        assertEquals(HeadroomRules.EXHAUSTED, SessionUsageFill.of(spent, null)!!.modeKey)
    }

    @Test
    fun `over a hundred clamps to a full bar`() {
        // A real reading — the endpoint reports a spent window as 103.4 — and an
        // unclamped fraction draws a fill wider than its track, which on the
        // phone's bottom bar overruns the item beside it.
        val over = headroom(liveSession = window(103.4, severity = "exceeded"))
        assertEquals(1.0f, SessionUsageFill.of(over, null)!!.fraction, 0.0001f)
        assertEquals(0.0f, SessionUsageFill.fractionOf(-5.0), 0.0001f, "and never negative")
    }

    @Test
    fun `no reading draws no line`() {
        // NULL IS THE IMPORTANT ANSWER, the same as the pill's: a daemon older than
        // 3.0.0 and one that has not read a window yet both reach here as nothing,
        // and a line at 0 % in either case claims somebody looked and found plenty.
        assertNull(SessionUsageFill.of(null, null), "no daemon answer at all")
        assertNull(SessionUsageFill.of(Headroom(), null), "a daemon with no accounts")
        assertNull(
            SessionUsageFill.of(headroom(liveSession = null), null),
            "an account whose session window was never read",
        )
    }

    @Test
    fun `the summary is the fallback, and only when the window is missing`() {
        // A daemon that serves a worst-window summary but no accounts map still has
        // something true to say, and the worst window is a smaller error than
        // silence. Its MODE is the daemon's own, not a second classification.
        val status = StatusHeadroom(worstPercent = 64.0, mode = HeadroomRules.WARN)
        val fill = SessionUsageFill.of(Headroom(), status)
        assertNotNull(fill)
        assertEquals(0.64f, fill!!.fraction, 0.0001f)
        assertEquals(HeadroomRules.WARN, fill.modeKey)

        // And it is NOT consulted when the real window is there — otherwise the
        // fallback would quietly become the reading.
        assertEquals(0.31f, SessionUsageFill.of(headroom(), status)!!.fraction, 0.0001f)
    }

    @Test
    fun `an account marked live with no windows falls back rather than reading zero`() {
        val status = StatusHeadroom(worstPercent = 70.0, mode = HeadroomRules.WARN)
        val blank = Headroom(accounts = mapOf("owner-max" to HeadroomAccount(live = true)))
        assertEquals(0.70f, SessionUsageFill.of(blank, status)!!.fraction, 0.0001f)
    }
}
