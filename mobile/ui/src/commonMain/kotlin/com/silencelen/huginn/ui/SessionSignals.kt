package com.silencelen.huginn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The "context used" reading (the statusline's `ctx N%`) and the "Compacting…"
 * marker, shared by both shells. Kept deliberately small and in-vernacular — no
 * left accent bars, no loud fills; the bar stays muted until context is nearly
 * full, then tints to error so a compaction-imminent session draws the eye.
 *
 * `percent` is the fraction of the context window USED (0 empty, 100 full);
 * null means the host didn't report it (no statusline / not a Claude pane), and
 * every composable here renders nothing in that case so callers can place it
 * unconditionally.
 */

/** Above this fraction used, the meter tints to error (compaction is near). */
private const val CONTEXT_HIGH = 80

/**
 * A thin context-usage bar with its percentage, for a session/chat header or
 * detail row. Renders nothing when [percent] is null.
 */
@Composable
fun ContextMeter(percent: Int?, modifier: Modifier = Modifier) {
    if (percent == null) return
    val p = percent.coerceIn(0, 100)
    val high = p >= CONTEXT_HIGH
    val fill = if (high) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(44.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(p / 100f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(fill),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            "$p%",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (high) FontWeight.SemiBold else FontWeight.Normal,
            color = if (high) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The compact "ctx N%" text alone, no bar — for a dense session-list row where a
 * per-row bar would be noise. Renders nothing when [percent] is null.
 */
@Composable
fun ContextBadge(percent: Int?, modifier: Modifier = Modifier) {
    if (percent == null) return
    val p = percent.coerceIn(0, 100)
    val high = p >= CONTEXT_HIGH
    Text(
        "ctx $p%",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (high) FontWeight.SemiBold else FontWeight.Normal,
        color = if (high) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * The "Compacting…" marker: a small pill with an inline spinner, shown while a
 * session is rewriting its context. Placed in a list row or header; the spinner
 * makes it read as in-progress rather than a static state.
 */
@Composable
fun CompactingChip(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(6.dp),
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                Modifier.height(11.dp).width(11.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Compacting…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
