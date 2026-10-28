package com.silencelen.huginn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.FastMode
import com.silencelen.huginn.data.SessionHeadroom
import com.silencelen.huginn.ui.system.HouseDialog
import com.silencelen.huginn.data.StatusHeadroom
import com.silencelen.huginn.ui.theme.LocalHuginnColors

/**
 * The two headroom controls a live session carries, drawn the same on both
 * clients: whether huginn will pick it back up when the limit resets, and what
 * the limit has already done to it.
 *
 * The phone puts these in its scrolling chip row and the desktop puts them in the
 * header beside the model picker — different frames, same two marks, so the words
 * are decided once here and by [HeadroomRules] underneath.
 */

/**
 * The toggle's label.
 *
 * States what WILL happen rather than naming a setting: "auto-resume" is a
 * feature name and tells a reader nothing about what their session is going to do
 * at 3am. Both halves are stated positively for the same reason — "auto-resume
 * off" reads as a control that is broken rather than one that is set.
 */
fun autoResumeWords(on: Boolean): String =
    if (on) "resumes on reset" else "stays stopped"

/**
 * A toggle chip for per-session auto-resume.
 *
 * @param on the effective value — the per-session override when there is one,
 *   otherwise the global default. The client never shows the three-state
 *   underneath: "follow the global" is a storage detail, and a reader looking at
 *   one session wants to know what THAT session will do.
 */
@Composable
fun AutoResumeChip(
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Text(
        autoResumeWords(on),
        style = MaterialTheme.typography.labelSmall,
        color = if (on) scheme.primary else scheme.onSurfaceVariant,
        // NEVER WRAPS. On the desktop this sits in the header row that the Screen
        // tab measures into real tmux rows, so a second line here resizes somebody
        // else's terminal.
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (on) scheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(enabled = enabled) { onToggle(!on) }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * The fast chip's words: the state, never the mechanics. While a toggle is still
 * queued ([pending], from [fastPending]) the chip names the state asked for and
 * when it lands, so the header does not read as if the tap did nothing.
 */
fun fastWords(fast: FastMode, pending: Boolean? = null): String = when {
    !fast.available -> "fast unavailable"
    pending != null -> "fast ${if (pending) "on" else "off"} · next turn"
    fast.on -> "fast on"
    else -> "fast off"
}

/**
 * The state a queued `/fast` toggle asked for, while the session has not reported
 * it yet; null once it has. The daemon types `/fast` at the next turn boundary, so
 * between the tap and that boundary the status line still reports the old state.
 * Null too when fast mode is unavailable: no queued toggle is going to land then.
 * The shell holds [asked] (it must outlive the chip) and drops it once this is null.
 */
fun fastPending(fast: FastMode, asked: Boolean?): Boolean? =
    asked?.takeIf { fast.available && it != fast.on }

/**
 * The note after a queued `/fast` toggle, the same sentence on both shells (house
 * rule: one wording for one control): the state asked for, and when it lands.
 */
fun fastQueuedWords(on: Boolean): String = "Fast mode ${if (on) "on" else "off"} · after this turn"

/** The one-line note a reader sees before fast mode goes on. Claude Code's own trade. */
const val FAST_ON_NOTE: String = "Auto mode turns off while fast is on."

/**
 * Fast mode for one session, drawn like [AutoResumeChip] because it is the
 * same kind of thing: a per-session switch the header shows as a fact.
 *
 * Disabled when Claude Code says it is unavailable, with Claude Code's own
 * [FastMode.reason] beside it ([showReason], the phone) or in the shell's
 * tooltip (the desktop). Turning it ON asks once, in the house dialog, because
 * fast mode turns auto mode off while it is on — the reader should hear that
 * from the switch, not discover it mid-run. Turning it OFF just turns it off.
 *
 * A toggle is queued, not instant (the daemon types `/fast` at the next turn
 * boundary), so the chip shows the state [asked] for, faded, until the session
 * reports it. The shell keeps [asked]: the chip leaves the tree on every tab flip.
 *
 * Not drawn at all by callers against a daemon that reports no [FastMode].
 */
@Composable
fun FastChip(
    fast: FastMode,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    showReason: Boolean = false,
    /** The state a queued toggle asked for (the shell's record), or null. */
    asked: Boolean? = null,
    /** The session now reports [asked], or never will: the shell forgets it. */
    onSettled: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    var confirming by remember { mutableStateOf(false) }
    val pending = fastPending(fast, asked)
    LaunchedEffect(asked, pending) { if (asked != null && pending == null) onSettled() }
    // What a tap acts on: the state on its way in, when one is queued.
    val target = pending ?: fast.on
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(
            fastWords(fast, pending),
            style = MaterialTheme.typography.labelSmall,
            color = when {
                !fast.available -> scheme.onSurfaceVariant.copy(alpha = 0.6f)
                // Asked for, not yet true: the colour of the state, faded.
                pending != null -> (if (pending) scheme.primary else scheme.onSurfaceVariant).copy(alpha = 0.7f)
                fast.on -> scheme.primary
                else -> scheme.onSurfaceVariant
            },
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .testTag(FAST_CHIP_TAG)
                .clip(RoundedCornerShape(6.dp))
                .background(if (fast.on && fast.available) scheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                .clickable(enabled = fast.available) { if (target) onToggle(false) else confirming = true }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        val reason = fast.reason
        if (showReason && !fast.available && !reason.isNullOrBlank()) {
            Text(
                reason,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(start = 4.dp).testTag("fast.reason"),
            )
        }
    }
    if (confirming) {
        HouseDialog(
            title = "Turn fast mode on?",
            onDismiss = { confirming = false },
            confirmLabel = "Turn on",
            onConfirm = { confirming = false; onToggle(true) },
        ) { Text(FAST_ON_NOTE, style = MaterialTheme.typography.bodyMedium) }
    }
}

const val FAST_CHIP_TAG: String = "fast.chip"

/**
 * What the limit has done to this session, in the daemon's own terms — or
 * nothing at all, which is the ordinary case.
 *
 * Drawn beside the model chip because that is what it is usually about: a session
 * laddered to opus shows a model the reader did not choose, and without this mark
 * there is nothing on screen to say who chose it or why.
 */
@Composable
fun SessionStateMark(
    session: SessionHeadroom?,
    status: StatusHeadroom?,
    nowMs: Long,
    modifier: Modifier = Modifier,
    resetClock: String? = null,
    /**
     * The session's own stall record from `/v1/headroom`, when the caller has
     * read it. It carries the clock Claude Code printed and the daemon's reason
     * for not resuming — neither of which is on the `/v1/sessions` cell, which
     * is why this mark used to say "resumes on reset" to a session the daemon
     * had already given up on.
     */
    stall: com.silencelen.huginn.data.HeadroomStall? = null,
) {
    val words = HeadroomRules.sessionMark(session, status, nowMs, resetClock, stall) ?: return
    // A stalled session is the urgent one; a laddered one is a fact about how it
    // is running. Same two tints the meters use for the same two ideas.
    val stalled = session?.stalled == true
    Text(
        words,
        style = MaterialTheme.typography.labelSmall,
        color = if (stalled) LocalHuginnColors.current.stateAttention else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
