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
 * would open a transcript that does not exist, and neither is the `…` PILL — it
 * has no agent id, and without this it would inherit Main's mark and leave two
 * chips claiming the body.
 */
/**
 * What a chip kept open only because it is being read says about itself.
 *
 * A word, not an icon: the reader is looking at a transcript that has stopped
 * growing and the only question they have is whether that is the agent or the
 * client.
 */
const val STREAM_FINISHED_HINT: String = "finished"

fun streamChipSelected(item: StreamPicker.Item, selected: String?): Boolean {
    if (item.header || item.overflow) return false
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
 * @param expanded whether the `…` pill is open. The pill itself is always drawn
 *   when the session has settled agents; this only says which way it points.
 * @param onToggleExpanded the pill was tapped.
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
    expanded: Boolean = false,
    onToggleExpanded: () -> Unit = {},
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
            } else if (item.overflow) {
                OverflowPill(item, expanded, onToggleExpanded)
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
 * The `…` pill: everything this session has finished, folded into one row.
 *
 * It carries the COUNT because a bare ellipsis asks the reader to guess whether
 * unfolding it is worth the tap — and because the count is the only thing on the
 * strip that still says how much work this session actually did once the live
 * chips have drained away.
 */
@Composable
private fun OverflowPill(item: StreamPicker.Item, expanded: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (expanded) scheme.surfaceContainerHigh
                else scheme.surfaceVariant.copy(alpha = 0.45f),
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (expanded) FontWeight.SemiBold else FontWeight.Normal,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
        )
        if (item.count > 0) {
            Spacer(Modifier.width(5.dp))
            Text(
                item.count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
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

/**
 * Selection is weight and a surface tint — the same vernacular as the tab strip.
 *
 * A [StreamPicker.Item.finished] chip is one the strip is not offering as live
 * work: an agent that has ended, either unfolded from the `…` pill or held open
 * because its transcript is the one on screen. It is drawn DIMMED and says so,
 * because a chip that looks live while nothing more will ever arrive on it is
 * the strip lying about its own body — the same failure as marking the wrong
 * chip.
 */
@Composable
private fun StreamChip(
    item: StreamPicker.Item,
    selected: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val dim = item.finished
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    dim -> scheme.surfaceVariant.copy(alpha = 0.25f)
                    selected -> scheme.surfaceContainerHigh
                    else -> scheme.surfaceVariant.copy(alpha = 0.45f)
                },
            )
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
            fontWeight = if (selected && !dim) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.5f)
                dim -> scheme.onSurfaceVariant.copy(alpha = 0.6f)
                selected -> scheme.onSurface
                else -> scheme.onSurfaceVariant
            },
            maxLines = 1,
        )
        if (dim) {
            Spacer(Modifier.width(5.dp))
            Text(
                STREAM_FINISHED_HINT,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
            )
        }
    }
}
