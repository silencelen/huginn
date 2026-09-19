package com.silencelen.huginn.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.QuickActions

/**
 * What to do with the text that is selected — the phone's answer to the
 * desktop's right-click.
 *
 * A strip rather than the platform's own floating toolbar: the platform one
 * holds Copy / Select all / Share and has no room offered to an app, and four
 * verbs hidden behind its overflow "⋮" is four verbs nobody finds. Copy is
 * repeated here so the strip is a complete answer and the reader never has to
 * work out which of two toolbars holds the thing they want.
 *
 * WHAT IS OFFERED is [SelectionMode.barItems]'s decision, not this composable's:
 * it draws what it is given, and it draws NOTHING when that is empty (a
 * long-press that caught no words, or a selection past the cap). That rule is
 * pure and asserted in `SelectionModeTest` precisely because a bar that appears
 * over an empty selection is the version of this feature that gets turned off.
 *
 * Nothing here sends. Every verb stages text in the composer to be edited.
 *
 * IT ALSO CARRIES THE TIME. A phone has no pointer and therefore no hover, so
 * the desktop's answer to "when was this written" is unavailable — and a new
 * gesture for it would be a second long-press on the same row. The bar the reader
 * has already raised is the one place the answer can go for free: one muted line
 * above the verbs, present only when the row carried a timestamp
 * ([SelectionMode.at], already in words).
 *
 * THE WAY OUT IS PINNED, not scrolled. The verbs live in a horizontal scroll and
 * on a 360dp phone the last of them is already past the right edge — so the
 * dismiss was the one control in the bar that could not be SEEN without first
 * scrolling a strip nobody realises scrolls. It is an X outside that scroll now,
 * always in the same place, and it takes exactly the path Back takes.
 */
@Composable
fun SelectionActionBar(
    mode: SelectionMode,
    actions: QuickActions?,
    onAct: (SelectionAction, String) -> Unit,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = mode.barItems(actions)
    if (items.isEmpty()) return

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
      Column(Modifier.fillMaxWidth()) {
        // When the row was written, above the verbs rather than among them: it is
        // the one thing here that is not a thing to DO, and a label in a row of
        // buttons reads as a disabled button.
        if (mode.at.isNotBlank()) {
            Text(
                mode.at,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 12.dp, top = 6.dp),
            )
        }
        // One row, scrolled rather than wrapped, for the same reason the
        // suggestion chips are: four verbs plus Copy wrap to two lines on a
        // narrow phone and push the composer off the bottom of the screen. The
        // scroll is the INNER row now; the X rides outside it.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items.forEach { item ->
                    when (item) {
                        is SelectionBarItem.Verb ->
                            TextButton(onClick = { onAct(item.action, mode.text) }) {
                                Text(
                                    item.action.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        SelectionBarItem.Copy ->
                            TextButton(onClick = { onCopy(mode.text) }) {
                                Text("Copy", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            }
                        // Pinned beside the scroll, below — not in it.
                        SelectionBarItem.Cancel -> Unit
                    }
                }
            }
            if (SelectionBarItem.Cancel in items) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        // Not a bare "Close": what it closes is the SELECTION, and
                        // a reader who cannot see the screen has no way to tell
                        // which of several things an unqualified close takes away.
                        contentDescription = "Cancel selection",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
      }
    }
}
