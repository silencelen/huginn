package com.silencelen.huginn.desktop.ui

import androidx.compose.ui.unit.dp
import com.silencelen.huginn.desktop.ui.common.CursorOverlays
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * TWO POPUPS AT ONE CURSOR.
 *
 * ⚠ THE LINK PEEK WAS DRAWN UNDERNEATH THE TIMESTAMP TOOLTIP. Both were
 * `TooltipPlacement.CursorPoint(DpOffset(12.dp, 16.dp))` — the same point — and
 * both are reachable at once: the transcript's row tip wraps every message row
 * (`DesktopRowTime`, installed at the window root) and the peek wraps a link
 * inside one. Hover a link in a message that has a timestamp, wait out the
 * peek's 220ms and then the tip's 400ms, and the tip lands on top of the URL
 * card. The peek is the one that matters — it says where a click is about to
 * send you — and it is the one that loses.
 *
 * The rule is that no two cursor overlays share a band, so they cannot cover
 * each other whichever order they appear in.
 */
class CursorOverlaysTest {

    @Test
    fun `the row tip and the link peek do not sit at the same point`() {
        assertTrue(
            CursorOverlays.ROW_TIP != CursorOverlays.LINK_PEEK,
            "identical offsets is how one popup ends up under the other",
        )
    }

    @Test
    fun `they are a whole card apart, so neither can cover the other`() {
        val gap = abs((CursorOverlays.ROW_TIP.y - CursorOverlays.LINK_PEEK.y).value)
        assertTrue(
            gap >= CursorOverlays.CARD_HEIGHT.value,
            "a $gap dp gap cannot hold a ${CursorOverlays.CARD_HEIGHT} card",
        )
        assertTrue(CursorOverlays.overlaps(CursorOverlays.ROW_TIP, CursorOverlays.ROW_TIP))
        assertTrue(!CursorOverlays.overlaps(CursorOverlays.ROW_TIP, CursorOverlays.LINK_PEEK))
    }

    @Test
    fun `the peek is the one that moves above the pointer`() {
        // It answers "where does this go", which is the question the click is
        // about to act on — so it gets the side nothing else is using, and the
        // explanatory tip keeps the position every other hover in the app uses.
        assertTrue(CursorOverlays.LINK_PEEK.y < 0.dp, "above the cursor: ${CursorOverlays.LINK_PEEK}")
        assertTrue(CursorOverlays.ROW_TIP.y > 0.dp, "below it, as every other tip in the app is")
    }
}
