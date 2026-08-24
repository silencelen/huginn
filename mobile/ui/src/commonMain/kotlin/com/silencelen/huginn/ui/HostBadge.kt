package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "on PRESTIGE" — where a chat is actually running, when that is not here.
 *
 * Drawn only for a remote chat. The common case is this host, and a badge on
 * every row would be a badge that says nothing: the whole job of this mark is
 * that the unusual one catches the eye.
 *
 * Deliberately a quiet outlined chip rather than a coloured one. The fact is
 * locational, not a state — nothing is wrong with a chat running on another
 * machine — and colour here would compete with the marks that DO mean something
 * needs you.
 */
@Composable
fun HostBadge(host: String?, hostName: String?, modifier: Modifier = Modifier) {
    val label = chatHostLabel(host, hostName) ?: return
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier,
    ) {
        Text(
            "on $label",
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.4.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/**
 * The note a finished, sealed run shows where a composer would be.
 *
 * It replaces the input rather than sitting beside it: the daemon refuses a send
 * with 409, and letting someone type a message that cannot be delivered is the
 * kind of small dishonesty that makes a feature feel broken.
 */
@Composable
fun SealedNote(modifier: Modifier = Modifier, isRound: Boolean = true) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    ) {
        Text(
            sealedNote(isRound),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
