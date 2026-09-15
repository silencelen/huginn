package com.silencelen.huginn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The strip above a session's conversation that chooses WHICH stream is being
 * read: the session's own transcript, or one subagent's.
 *
 * Until now a fan-out arrived as a single folded card keyed on nothing but the
 * `sidechain` flag, so two concurrent agents were one undifferentiated block of
 * interleaved work. Per-agent identity existed — in the work cards and on the
 * overview map — everywhere except the place the reader was actually reading.
 *
 * The ORDER is [StreamPicker] in `:core`, under test, because it is the whole
 * feature: a strip that lists agents in directory order buries the running one.
 * This file is the pixels only.
 */

/**
 * Whether a row is the one being read.
 *
 * `null` means Main, which is also [StreamPicker.MAIN_KEY]'s row, so the two
 * spellings of "the session itself" have to agree here rather than at each call
 * site. A workflow HEADER is never selected: it labels a group and picking it
 * would open a transcript that does not exist.
 */
fun streamChipSelected(item: StreamPicker.Item, selected: String?): Boolean {
    if (item.header) return false
    if (item.agentId == null) return selected == null || selected == StreamPicker.MAIN_KEY
    return selected == item.agentId
}

/**
 * The chip row.
 *
 * @param selected the picked agent id, or null for the session's own transcript.
 * @param onPick called with the agent id, or null for Main.
 * @param enabled false when the host cannot serve an agent transcript at all —
 *   a daemon older than 3.0.0 404s that route. The chips stay VISIBLE and say
 *   why rather than vanishing: an absent control is indistinguishable from a
 *   session that never spawned anything.
 * @param note the reason they are disabled, shown once at the end of the strip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StreamPickerRow(
    items: List<StreamPicker.Item>,
    selected: String?,
    onPick: (String?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    note: String? = null,
) {
    // One row is the Main chip on its own, which is the state every session
    // without subagents is in. Nothing to pick between, so nothing to draw.
    if (items.size <= 1 && note == null) return
    FlowRow(
        modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { item ->
            if (item.header) {
                RunHeader(item)
            } else {
                StreamChip(
                    item = item,
                    selected = streamChipSelected(item, selected),
                    // Main is always pickable: getting BACK to the session's own
                    // transcript must never depend on a route the host may not have.
                    enabled = enabled || item.agentId == null,
                    onPick = { onPick(item.agentId) },
                )
            }
        }
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * A run's label, not a chip.
 *
 * Deliberately a different shape from the things beside it: a workflow run is not
 * a stream, it is the reason the next few chips belong together, and drawing it
 * as a chip invites a click that can only do nothing.
 */
@Composable
private fun RunHeader(item: StreamPicker.Item) {
    Row(
        Modifier.padding(start = 2.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.running) {
            Spacer(
                Modifier.size(5.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            item.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Selection is weight and a surface tint — the same vernacular as the tab strip. */
@Composable
private fun StreamChip(
    item: StreamPicker.Item,
    selected: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) scheme.surfaceContainerHigh else scheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(enabled = enabled, onClick = onPick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The live dot, and only for a running agent: it is what makes the strip
        // worth scanning at all during a fan-out.
        if (item.running) {
            Spacer(Modifier.size(5.dp).clip(CircleShape).background(scheme.primary))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            item.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.5f)
                selected -> scheme.onSurface
                else -> scheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}
