package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.ui.system.Scale
import com.silencelen.huginn.update.UpdateReminder

/**
 * "Huginn x.y.z is ready": the notice at the top of a list when a verified
 * update is parked, with the three answers [UpdateReminder] knows.
 *
 * The same shape as the first-run local-AI offer the desktop draws in the same
 * place — a tier-2 surface inside the list's own inset, a title line, one line
 * of body, the actions on the right — because two cards in one slot with two
 * shapes is a legend.
 *
 * ⚠ THE ACTIONS WRAP AS WHOLE BUTTONS (owner, 2026-10-30). They were one Row, and
 * at the list pane's default 320 dp Install and restart and Later took their
 * width and "Don't remind me" was measured into what was left — two letters to
 * a line, down the card. A FlowRow moves a button that does not fit to the next
 * line, and no label here may wrap inside its button. Not modal, not a toast: it sits where the reader already
 * looks and waits. Nothing here installs anything; [onInstall] is the caller's
 * hand-off to the updater and the quit that lets the installer replace files.
 *
 * @param line the release notes' first bullet ([UpdateReminder.firstLine]), or null.
 * @param installable whether this client can apply the file itself. When it
 *   cannot, [hint] says what to do instead and [command] (the command inside
 *   it, [UpdateReminder.commandOf]) gets a Copy button in place of Install.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateNoticeCard(
    version: String,
    line: String?,
    installable: Boolean,
    hint: String,
    command: String?,
    onInstall: () -> Unit,
    onCopyCommand: () -> Unit,
    onLater: () -> Unit,
    onSilence: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).testTag(TAG),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(UpdateReminder.title(version), style = MaterialTheme.typography.labelLarge)
            if (!line.isNullOrBlank()) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!installable && hint.isNotBlank()) {
                // The sentence the Updates page shows too: where the file is and
                // what installs it. Said here because the card is what the reader
                // sees first, and a button that only opened Settings would be a
                // card that says "go and read".
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Scale.s4, Alignment.End),
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                when {
                    installable -> Button(onClick = onInstall, modifier = Modifier.testTag("$TAG.install")) {
                        OneLine("Install and restart")
                    }
                    command != null -> TextButton(onClick = onCopyCommand, modifier = Modifier.testTag("$TAG.copy")) {
                        OneLine("Copy command")
                    }
                }
                TextButton(onClick = onLater, modifier = Modifier.testTag("$TAG.later")) { OneLine("Later") }
                TextButton(onClick = onSilence, modifier = Modifier.testTag("$TAG.silence")) { OneLine("Don't remind me") }
            }
        }
    }
}

/** A button label: one line, whole, or the FlowRow moves the button instead. */
@Composable
private fun OneLine(text: String) = Text(text, maxLines = 1, softWrap = false)

/** The card's testTag; its buttons are `$TAG.install`, `.copy`, `.later`, `.silence`. */
const val UPDATE_NOTICE_TAG: String = "update.notice"

private const val TAG = UPDATE_NOTICE_TAG
