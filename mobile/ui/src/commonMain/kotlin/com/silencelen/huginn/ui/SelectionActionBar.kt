package com.silencelen.huginn.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
 * WHAT IS OFFERED is [SelectionMode.actions]'s decision, not this composable's:
 * it draws what it is given, and it draws NOTHING when that is empty (a
 * long-press that caught no words, or a selection past the cap). That rule is
 * pure and asserted in `SelectionModeTest` precisely because a bar that appears
 * over an empty selection is the version of this feature that gets turned off.
 *
 * Nothing here sends. Every verb stages text in the composer to be edited.
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
    val offered = mode.actions(actions)
    if (offered.isEmpty()) return

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        // One row, scrolled rather than wrapped, for the same reason the
        // suggestion chips are: four verbs plus Copy plus Done wrap to two lines
        // on a narrow phone and push the composer off the bottom of the screen.
        Row(
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            offered.forEach { action ->
                TextButton(onClick = { onAct(action, mode.text) }) {
                    Text(
                        action.label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(onClick = { onCopy(mode.text) }) {
                Text("Copy", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
            TextButton(onClick = onDismiss) {
                Text(
                    "Done",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
