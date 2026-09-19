package com.silencelen.huginn.desktop.ui.common

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * WHERE A CURSOR-ANCHORED POPUP SITS — one table, because two of them can be up
 * at the same time.
 *
 * ⚠ THE LINK PEEK WAS DRAWN UNDERNEATH THE TIMESTAMP TOOLTIP. Both were
 * `TooltipPlacement.CursorPoint(DpOffset(12.dp, 16.dp))`, chosen independently
 * in two files, and both are reachable at once: [com.silencelen.huginn.desktop
 * .ui.common.DesktopRowTime] wraps every transcript row and is installed at the
 * window root, and the peek wraps a link INSIDE one of those rows. Hover a link
 * in a message that has a timestamp, wait out the peek's 220ms and then the
 * tip's 400ms, and the tip covers the URL card — so the reader loses the one
 * fact a click is about to act on and keeps the one they could already see.
 *
 * Two independently-correct offsets that happen to be equal is exactly the kind
 * of fault that never throws and that nobody notices until they are looking for
 * it, so the offsets live together and [overlaps] is asserted.
 */
object CursorOverlays {

    /**
     * How tall one of these cards is, near enough.
     *
     * Not measured — it is a single line of 11sp text plus 8dp of padding top
     * and bottom, and what this number is for is keeping two popups a card
     * apart, where being generous costs nothing and being exact buys nothing.
     */
    val CARD_HEIGHT = 30.dp

    /**
     * The explanatory tooltip: below and right of the pointer, which is where
     * every hover in this app has always put it and where a reader expects the
     * answer to "what does this mean" to appear.
     */
    val ROW_TIP: DpOffset = DpOffset(12.dp, 16.dp)

    /**
     * The link peek: ABOVE the pointer.
     *
     * The peek moves rather than the tip because it is the one that must win —
     * "where does this link go" is the question the next click answers — and
     * because it is the rarer of the two, so the position everything else uses
     * stays where muscle memory left it.
     */
    val LINK_PEEK: DpOffset = DpOffset(12.dp, -(CARD_HEIGHT + 6.dp))

    /** Could a card at [a] cover a card at [b]? */
    fun overlaps(a: DpOffset, b: DpOffset): Boolean =
        abs((a.y - b.y).value) < CARD_HEIGHT.value
}
