package com.silencelen.huginn.ui.system

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * THE ONE DIALOG: a `titleSmall` title, a body, Cancel, and one verb. A
 * destructive verb and its confirmation are drawn in `error` (BRAND.md section
 * 4; UI-AUDIT.md "Error is failure; destructive verbs are red and their
 * confirmations too"). Two dialog styles existed; this is the one.
 */
@Composable
fun HouseDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    dismissLabel: String = "Cancel",
    text: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(Scale.radiusSheet),
        title = { Text(title, style = MaterialTheme.typography.titleSmall) },
        text = text,
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}
