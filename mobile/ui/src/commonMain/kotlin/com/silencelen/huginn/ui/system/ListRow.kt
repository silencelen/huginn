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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp

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
    /** Lines below the meta line, for the few rows (sessions, chats) that carry detail. */
    detail: (@Composable ColumnScope.() -> Unit)? = null,
    /** Drawn on the title line after the title (a state word, a host chip): the title gives way first. */
    titleSuffix: (@Composable RowScope.() -> Unit)? = null,
    /**
     * The trailing is TEXT that may be long (a member's status), so it shares the
     * line with the title — up to half of it, ellipsized — instead of being
     * measured first and taking what it wants. Off for buttons and short marks.
     */
    trailingShares: Boolean = false,
) {
    val m = LocalRowMetrics.current
    val base = modifier.fillMaxWidth()
        .let { if (selected) it.background(MaterialTheme.colorScheme.surfaceContainerLow) else it }
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(horizontal = m.inset, vertical = m.vertical)
    // A row with detail lines aligns its dot and its trailing on the TITLE line,
    // top-left and top-right like the desktop, not on the row's middle (owner,
    // 2026-10-13). A one-line row stays centred.
    val topAligned = detail != null
    val titleLine = with(LocalDensity.current) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() }
    val anchor: Modifier = if (topAligned) Modifier.height(titleLine) else Modifier
    // Half the row, for a sharing trailing: it takes what it needs up to that and
    // stays right-aligned (a weight split the line in two and left a gap).
    var shareCap by remember { mutableStateOf(Dp.Unspecified) }
    val density = LocalDensity.current
    Row(
        if (trailingShares) base.onSizeChanged { shareCap = with(density) { (it.width / 2).toDp() } } else base,
        verticalAlignment = if (topAligned) Alignment.Top else Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(anchor, contentAlignment = Alignment.Center) { leading() }
            Spacer(Modifier.width(Scale.s12))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = if (titleMono) LocalMonoStyle.current.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize)
                    else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (titleSuffix != null) {
                    Spacer(Modifier.width(Scale.s8))
                    titleSuffix()
                }
            }
            if (!meta.isNullOrBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = metaColor,
                    maxLines = metaMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            detail?.invoke(this)
        }
        if (trailing != null) {
            Spacer(Modifier.width(Scale.s12))
            Row(
                if (trailingShares) anchor.widthIn(max = shareCap) else anchor,
                verticalAlignment = Alignment.CenterVertically,
            ) { trailing() }
        }
    }
}
