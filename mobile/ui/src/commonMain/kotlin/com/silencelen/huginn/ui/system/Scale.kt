package com.silencelen.huginn.ui.system

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * THE ONE SPACING AND SHAPE SCALE (UI-AUDIT.md section 3, "Shape and space").
 * Spacing on 4/8/12/16/20/24; three radii; two dots; two icon sizes. The audit
 * counted 9 radii, 12 horizontal and 14 vertical paddings and 4 dot sizes for
 * the same kinds of thing; every new value comes from here.
 */
object Scale {
    val s4: Dp = 4.dp
    val s8: Dp = 8.dp
    val s12: Dp = 12.dp
    val s16: Dp = 16.dp
    val s20: Dp = 20.dp
    val s24: Dp = 24.dp

    /** Chips. */
    val radiusChip: Dp = 6.dp
    /** Cards and rows. */
    val radiusCard: Dp = 8.dp
    /** Sheets and dialogs. */
    val radiusSheet: Dp = 12.dp

    /** The live dot; the quiet dot is for waiting, offline and settled things. */
    val dot: Dp = 8.dp
    val dotQuiet: Dp = 6.dp

    val icon: Dp = 16.dp
    val iconLarge: Dp = 20.dp
}

/**
 * How a row sits on its platform: the leading inset and the vertical padding.
 * Phone 16/10, desktop 12/6 (UI-AUDIT.md section 3, "Row"), and nothing else.
 */
data class RowMetrics(val inset: Dp, val vertical: Dp, val headerVertical: Dp)

val PhoneRowMetrics: RowMetrics = RowMetrics(inset = 16.dp, vertical = 10.dp, headerVertical = 8.dp)
val DesktopRowMetrics: RowMetrics = RowMetrics(inset = 12.dp, vertical = 6.dp, headerVertical = 4.dp)

val LocalRowMetrics = staticCompositionLocalOf { PhoneRowMetrics }
