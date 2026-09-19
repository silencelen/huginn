package com.silencelen.huginn.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * ⚠⚠ REMOVE ASKS FIRST (P-13, D-18, decision 60).
 *
 * "Remove" on an app and on a route deleted instantly: no dialog, no undo, and
 * `GET /v1/apps` confirmed the row was gone. Kill session and Archive both
 * confirm by name — and P-02 is the demonstration of why that matters, since the
 * list moves under the finger and the dialog is what catches a mis-targeted tap.
 * Two verbs in the same product answering the same question differently is the
 * finding; the owner's call (decision 60) is a confirm naming the item, the same
 * pattern as Kill and Archive, rather than an undo snackbar.
 *
 * Shared because there are four call sites across two shells and the one that
 * keeps its own copy is the one that drifts.
 *
 * @param verb the quiet lead — "Remove app", "Remove route".
 * @param name the thing being removed. The largest text in the dialog: see
 *   [ConfirmTitle].
 * @param body what is actually lost, and what is not.
 */
@Composable
fun RemoveConfirmDialog(
    verb: String,
    name: String,
    body: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { ConfirmTitle(verb, name) },
        text = {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Remove", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What removing an app costs, and what it does not. One sentence, both shells. */
const val REMOVE_APP_BODY: String =
    "The row and its cached icon are removed from huginn. Nothing is stopped or " +
        "uninstalled on the machine it runs on — you can add it again."

/** The same for a pinned route. */
const val REMOVE_ROUTE_BODY: String =
    "The address is unpinned. If it is the one in use, huginn moves to the next " +
        "route that answers; nothing on the host changes."
