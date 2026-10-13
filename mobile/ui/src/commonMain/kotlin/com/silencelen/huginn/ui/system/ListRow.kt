package com.silencelen.huginn.ui.system

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.silencelen.huginn.ui.theme.LocalMonoStyle

/**
 * THE ONE ROW (UI-AUDIT.md section 3, "Row"): a leading dot or icon, a title
 * (mono where it is a name), one meta line, and a trailing time or state word.
 * Insets from [LocalRowMetrics]. Selected is `surfaceContainerLow` plus
 * SemiBold, nothing else. Sessions, chats, projects, members, apps, devices and
 * archived items all read this; a row that needs more is a card, not a row.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    titleMono: Boolean = false,
    titleColor: Color = Color.Unspecified,
    metaColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    metaMaxLines: Int = 1,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val m = LocalRowMetrics.current
    val base = modifier.fillMaxWidth()
        .let { if (selected) it.background(MaterialTheme.colorScheme.surfaceContainerLow) else it }
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(horizontal = m.inset, vertical = m.vertical)
    Row(base, verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(Scale.s12))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = if (titleMono) LocalMonoStyle.current.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize)
                else MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!meta.isNullOrBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = metaColor,
                    maxLines = metaMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Scale.s12))
            trailing()
        }
    }
}
