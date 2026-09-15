package com.silencelen.huginn.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The five shapes a settings row can be, drawn once for both shells.
 *
 * Every one takes the catalog `id` and a `highlighted` flag, because arriving
 * from a search hit has to be able to say WHICH row was meant. Passing the id
 * rather than letting the caller position a marker keeps the mark and the row
 * inseparable: a row that moves takes its highlight with it.
 *
 * ⚠ NO LEFT ACCENT BARS. The owner's standing note, and [SettingsHouseRulesTest]
 * greps this file for the shape of one. State here is what it is everywhere else
 * in this product: a small dot in the row's own text flow, plus a brief surface
 * tint. A rail down the side of a row is a second vocabulary for the same bit.
 *
 * ⚠ CAP BEFORE FILL. `widthIn(max).fillMaxWidth()`, never the reverse —
 * `fillMaxWidth` hands down FIXED constraints and a `widthIn` inside them can
 * only coerce into them, so the cap is silently swallowed. `CapBeforeFillTest`
 * scans all four modules for the wrong order, this file included.
 */

/** How a row says "this is the one you searched for", in numbers a test can read. */
object SettingsRowStyle {

    /** The tint behind a highlighted row. Brief and low: a mark, not a selection. */
    const val HIGHLIGHT_ALPHA: Float = 0.14f

    /** The row's own reading cap, matching the page it sits on. */
    val ROW_MAX_WIDTH = 840.dp

    fun tintAlpha(highlighted: Boolean): Float = if (highlighted) HIGHLIGHT_ALPHA else 0f

    /** Exactly one row in a page is ever the arrival target. */
    fun isHighlighted(id: String, highlightItemId: String?): Boolean =
        highlightItemId != null && highlightItemId == id
}

/** The one state mark this product uses. A dot in the text flow — never a bar. */
@Composable
fun SettingsStateDot(color: Color) {
    Box(Modifier.padding(end = 6.dp).size(7.dp).clip(CircleShape).background(color))
}

/**
 * The shared frame: the cap, the tint, the dot and the title/summary column.
 * Every row below is this plus its own control.
 */
@Composable
private fun RowFrame(
    id: String,
    title: String,
    summary: String?,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = SettingsRowStyle.tintAlpha(highlighted))
    val base = modifier
        .testTag(id)
        .widthIn(max = SettingsRowStyle.ROW_MAX_WIDTH)
        .fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .background(tint)
    Row(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (highlighted) SettingsStateDot(MaterialTheme.colorScheme.primary)
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            if (!summary.isNullOrBlank()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/** A setting that is on or off. */
@Composable
fun SettingsToggleRow(
    id: String,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    highlighted: Boolean = false,
    enabled: Boolean = true,
) {
    RowFrame(id, title, summary, highlighted, modifier) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * A setting that is a string.
 *
 * The field sits UNDER the title rather than beside it: a label and a 280dp
 * minimum field in one Row is the shape that rendered "Add login" as a 32px
 * stripe with one letter per line on a narrow window.
 */
@Composable
fun SettingsFieldRow(
    id: String,
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    secret: Boolean = false,
    singleLine: Boolean = true,
    isError: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = SettingsRowStyle.tintAlpha(highlighted))
    Column(
        modifier
            .testTag(id)
            .widthIn(max = SettingsRowStyle.ROW_MAX_WIDTH)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(tint)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (highlighted) SettingsStateDot(MaterialTheme.colorScheme.primary)
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        if (!summary.isNullOrBlank()) {
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                enabled = enabled,
                isError = isError,
                visualTransformation =
                    if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
    }
}

/** A setting that is a verb — "Lock now", "Copy diagnostics", "Check for updates". */
@Composable
fun SettingsActionRow(
    id: String,
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    RowFrame(id, title, summary, highlighted, modifier) {
        if (destructive) {
            // A TextButton in the error colour, not a filled one: the weight of a
            // primary button is an invitation, and this is not.
            TextButton(onClick = onAction, enabled = enabled) {
                Text(actionLabel, color = MaterialTheme.colorScheme.error)
            }
        } else {
            Button(onClick = onAction, enabled = enabled) { Text(actionLabel) }
        }
    }
}

/** A setting that lives somewhere else — the fleet, the shortcut list, Status. */
@Composable
fun SettingsNavRow(
    id: String,
    title: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    highlighted: Boolean = false,
    trailingText: String? = null,
) {
    RowFrame(id, title, summary, highlighted, modifier, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!trailingText.isNullOrBlank()) {
                Text(
                    trailingText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "›",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** A fact, not a control: the version, the log path, what the token is. */
@Composable
fun SettingsReadOnlyRow(
    id: String,
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    summary: String? = null,
    highlighted: Boolean = false,
) {
    RowFrame(id, title, summary, highlighted, modifier) {
        if (!value.isNullOrBlank()) {
            Text(
                value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
    }
}
