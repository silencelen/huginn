package com.silencelen.huginn.ui.system

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * THE ONE EMPTY STATE: left-aligned, a title, one line, at most one text button.
 * Four patterns existed (centred, boxed, a bare caption, a paragraph with a
 * filled button); an empty list should read like the list it will become.
 */
@Composable
fun EmptyState(
    title: String,
    line: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val m = LocalRowMetrics.current
    Column(modifier.fillMaxWidth().padding(horizontal = m.inset, vertical = Scale.s24)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(Scale.s4))
        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Scale.s8))
            TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) { Text(actionLabel) }
        }
    }
}
