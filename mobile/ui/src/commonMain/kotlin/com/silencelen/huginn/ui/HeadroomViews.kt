package com.silencelen.huginn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Headroom
import com.silencelen.huginn.data.SessionHeadroom
import com.silencelen.huginn.data.StatusHeadroom

/**
 * The always-on headroom mark: one chip that says how close the host is to the
 * limit that will stop work, wherever the reader happens to be looking.
 *
 * Usage used to be a DESTINATION — the desktop fetched the plan only while the
 * Status pane was open and the phone only from its Status screen — so the one
 * number that decides whether tonight's run finishes was the one number nobody
 * was looking at. This is the correction, and it is deliberately the smallest
 * possible: a chip, not a bar, with the full picture one click away.
 *
 * Every word it says comes from [HeadroomRules] in `:core`, which is where the
 * phone and the desktop are kept from disagreeing about the same reading — they
 * already did that once about a reset countdown.
 */

/** What a pill would draw, or null when it must not draw at all. */
data class HeadroomPillFace(
    val text: String,
    /** A [PlanLimit]-severity word, so the meter colours answer for this too. */
    val severity: String,
    /** The daemon's mode word, for a tooltip that wants to be more specific. */
    val mode: String,
    /** Spawns the hook gate is holding right now; 0 draws no count. */
    val paused: Int = 0,
)

/**
 * The pill's content, decided without a Compose runtime so it can be tested.
 *
 * NULL IS THE IMPORTANT ANSWER. It means one of two things and both want the
 * same treatment: the daemon is older than 3.0.0 and has no headroom subsystem at
 * all, or it has one and has not read a window yet. A pill drawn at 0 % in either
 * case is a claim that somebody looked and found plenty, which is the one thing
 * this surface must never say by accident.
 */
fun headroomPill(status: StatusHeadroom?, nowMs: Long): HeadroomPillFace? {
    val text = HeadroomRules.pillText(status, nowMs) ?: return null
    val s = status ?: return null
    // The daemon's own mode when it sent one, rather than a second classification
    // from the percentage: `exceeded` arrives while the number still reads 99.4.
    val mode = s.mode
    return HeadroomPillFace(
        text = text,
        severity = HeadroomRules.severityColorKey(mode),
        mode = mode,
        paused = s.paused.coerceAtLeast(0),
    )
}

/**
 * The pill's shape, from the FULL `/v1/headroom` answer.
 *
 * Both are on the wire: `/v1/status` carries a summary for the poll every client
 * already makes, and `/v1/headroom` carries the whole picture for the panes that
 * need it. A client polling the second one should not have to wait for the first
 * to catch up, and it must not build a second opinion either — so the full answer
 * is folded into the summary's shape here, once.
 */
fun statusHeadroomOf(headroom: Headroom?): StatusHeadroom? {
    val h = headroom ?: return null
    val worst = h.worst ?: return null
    return StatusHeadroom(
        worstPercent = worst.percent,
        worstLabel = worst.label.ifEmpty { HeadroomRules.windowKeyWords(worst.window) },
        nextResetAt = worst.resetsAt,
        mode = h.mode,
        // Armed only. A sentinel is present-as-null when it is not armed, and
        // counting those would arm the pill's own warning for nothing.
        sentinels = h.sentinels.filterValues { it != null }.keys.toList(),
        paused = h.held.size,
    )
}

/**
 * The chip itself.
 *
 * Drawn only when [headroomPill] has something to say, so a caller can place it
 * unconditionally and an older daemon simply costs no width.
 *
 * ⚠ The two Compose traps documented on the desktop's list pane apply to anything
 * with a fixed width inside an animating parent. This chip has none: it wraps its
 * text, so it can sit in a status line or a top bar without either of them.
 */
@Composable
fun HeadroomPill(
    status: StatusHeadroom?,
    nowMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val face = headroomPill(status, nowMs) ?: return
    val tint = meterColor(face.severity, 0.0)
    Surface(
        color = tint.copy(alpha = 0.14f),
        contentColor = tint,
        shape = RoundedCornerShape(6.dp),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The dot carries the severity on its own, so the chip still reads as
            // urgent to somebody who cannot separate the tints.
            Spacer(Modifier.size(6.dp).clip(CircleShape).background(tint))
            Spacer(Modifier.width(5.dp))
            Text(
                face.text,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                softWrap = false,
            )
            // Held spawns are the other half of "why is nothing happening": the
            // gate is holding them and no session is failing, so nothing else on
            // screen would say so.
            if (face.paused > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "${face.paused} held",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * The severity key a [UsageFill] paints with, or null when there is no line.
 *
 * Pure, and it exists so the ONE colour decision is asserted rather than inferred
 * from a screenshot: the fill borrows the headroom vocabulary
 * ([HeadroomRules.severityColorKey] → [meterColor]) rather than growing a second
 * scale, which is how one surface calls 92 % red while the bar under it calls it
 * amber. Null in, null out: no reading, no line.
 */
fun usageFillSeverity(fill: UsageFill?): String? =
    fill?.let { HeadroomRules.severityColorKey(it.modeKey) }

/**
 * How much of the current 5-hour session window is spent, as a hairline under the
 * Status destination's icon.
 *
 * A LINE AND NOT A NUMBER, deliberately. This sits in a navigation bar under an
 * icon that already carries a word; a percentage there would be a second reading
 * competing with the headroom pill in the top bar, and the two answer different
 * questions (this one is the session, the pill is the worst window anywhere). What
 * a glance needs from a nav item is "roughly how much is left", which is a length.
 *
 * Drawn only when [SessionUsageFill.of] had something to say, so a caller may
 * place it unconditionally and an older daemon simply costs 2dp of nothing.
 */
@Composable
fun UsageFillLine(
    fill: UsageFill?,
    modifier: Modifier = Modifier,
) {
    val severity = usageFillSeverity(fill) ?: return
    val tint = meterColor(severity, 0.0)
    Box(
        modifier
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp))
            // The TRACK at a fifth, so the line reads as a gauge with an end
            // rather than as a stray underline whose length means nothing.
            .background(tint.copy(alpha = 0.20f)),
    ) {
        Box(
            Modifier.fillMaxHeight()
                .fillMaxWidth(fill!!.fraction)
                .clip(RoundedCornerShape(1.dp))
                .background(tint),
        )
    }
}

/**
 * The session whose headroom the transcript rows below belong to.
 *
 * A composition local rather than a parameter for the reason the transcript
 * metrics are one: [TranscriptEventItem] is called from four places across two
 * shells and threading a nullable through all of them to reach one row kind would
 * change every signature for a row most transcripts never contain. Null is the
 * honest default — a chat transcript has no session headroom at all.
 */
val LocalSessionHeadroom = staticCompositionLocalOf<SessionHeadroom?> { null }
