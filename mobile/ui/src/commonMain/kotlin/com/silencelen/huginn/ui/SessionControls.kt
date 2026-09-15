package com.silencelen.huginn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.SessionHeadroom
import com.silencelen.huginn.data.StatusHeadroom

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
) {
    val words = HeadroomRules.sessionMark(session, status, nowMs, resetClock) ?: return
    // A stalled session is the urgent one; a laddered one is a fact about how it
    // is running. Same two tints the meters use for the same two ideas.
    val stalled = session?.stalled == true
    Text(
        words,
        style = MaterialTheme.typography.labelSmall,
        color = if (stalled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
