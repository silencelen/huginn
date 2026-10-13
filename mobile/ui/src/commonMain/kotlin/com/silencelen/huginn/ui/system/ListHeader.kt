package com.silencelen.huginn.ui.system

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.silencelen.huginn.ui.theme.LocalHuginnColors

/**
 * THE ONE LIST HEADER: Title Case, `labelLarge`, no letter-spacing (the audit
 * found three section-label styles, one of them uppercase with 1.2 sp tracking
 * beside a comment declaring Title Case the house style). The count is the
 * cheapest true fact about a list; [verb] is the one action that belongs to the
 * whole list. A hairline below, never a box.
 */
@Composable
fun ListHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    hairline: Boolean = true,
    verb: (@Composable () -> Unit)? = null,
) {
    val m = LocalRowMetrics.current
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = m.inset, end = Scale.s8, top = m.headerVertical, bottom = m.headerVertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            if (count != null && count > 0) {
                Spacer(Modifier.width(Scale.s8))
                Text("$count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            verb?.invoke()
        }
        if (hairline) HorizontalDivider(color = LocalHuginnColors.current.outlineHairline)
    }
}
