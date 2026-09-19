package com.silencelen.huginn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * How much of the surface's own text colour a settled chip keeps.
 *
 * DIMMED BUT READABLE. The strip's job while folded open is to let somebody find
 * a transcript they remember; text they have to lean in to read is a list they
 * scroll past. 0.6 over `onSurfaceVariant` — which is already the muted role —
 * compounded to something close to 40 % of the body text on the owner's phone.
 */
const val STREAM_DIM_ALPHA: Float = 0.7f

/** Disabled is the one state that should be hard to read: the chip cannot be used. */
const val STREAM_DISABLED_ALPHA: Float = 0.5f

/**
 * The opacity a chip's label is drawn at — the one place the three states are
 * compared, so "dimmed" cannot quietly drift under "disabled" again.
 */
fun streamChipTextAlpha(finished: Boolean, enabled: Boolean): Float = when {
    !enabled -> STREAM_DISABLED_ALPHA
    finished -> STREAM_DIM_ALPHA
    else -> 1f
}

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

    // ⚠ THE STRIP IS ALWAYS A STRIP. Everything up to and including the `…` pill
    // is the row above the transcript; everything after it is what the pill
    // stands for, and that goes in an OVERLAY rather than in the flow. See
    // [StreamOverflowSheet].
    val pillAt = items.indexOfFirst { it.overflow }
    val strip = if (pillAt >= 0) items.take(pillAt + 1) else items
    val sheet = if (pillAt >= 0) items.drop(pillAt + 1) else emptyList()

    Box(modifier) {
        FlowRow(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            strip.forEach { item ->
                if (item.header) {
                    RunHeader(item)
                } else if (item.overflow) {
                    // The pill is the ANCHOR as well as the control: the sheet
                    // opens over it, where the reader's pointer or thumb already is.
                    Box {
                        OverflowPill(item, expanded, onToggleExpanded)
                        StreamOverflowSheet(
                            rows = sheet,
                            expanded = expanded,
                            selected = selected,
                            enabled = enabled,
                            onDismiss = onToggleExpanded,
                            onPick = { id -> onToggleExpanded(); onPick(id) },
                        )
                    }
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
}

/**
 * EVERYTHING THIS SESSION HAS FINISHED, AS AN OVERLAY WITH A SEARCH BOX.
 *
 * ⚠⚠ IT USED TO UNFOLD IN PLACE, and at the size real sessions reach that was
 * unusable in three separate ways. A day-long session settles hundreds; the
 * panel pushed the transcript down the screen to make room; every row read
 * `[Workflow harnes… · a85dfc1f` because a CHIP is 28 characters wide by layout
 * necessity, so dozens of rows were identical but for an opaque hex tail; and
 * there was no way to search 198 of them. The overlay fixes all three — it is
 * drawn over the conversation instead of shoving it, it has the width to say the
 * whole title, and the filter is the only honest way through a list that long.
 *
 * ⚠ THE CAP ENDS ON A WHOLE ROW. It was a flat 240dp over rows of whatever
 * height the text happened to make, so the list sliced its top and bottom rows
 * in half and read as a torn edge with nothing saying it scrolled. The height is
 * now [STREAM_SHEET_ROWS] × [STREAM_SHEET_ROW_HEIGHT] and every row is pinned to
 * that height, so a partly-visible row is unrepresentable and the boundary
 * itself says "there is more".
 *
 * ⚠⚠ THE CONTENT IS ONE `DisableSelection` BLOCK. A menu composes in its own
 * layout root while inheriting this one's composition locals — including the
 * transcript's `LocalSelectionRegistrar` — so a `Text` in here would register as
 * a selectable of a selection in another hierarchy, and the next press-drag
 * throws "layouts are not part of the same hierarchy". Same rule as `Tip` and
 * the link peek; see `OverlaysDisableSelectionTest`.
 */
@Composable
private fun StreamOverflowSheet(
    rows: List<StreamPicker.Item>,
    expanded: Boolean,
    selected: String?,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    // Cleared every time the sheet opens: a filter left over from last time is a
    // list that looks empty for no reason anybody can see.
    var query by remember(expanded) { mutableStateOf("") }
    val shown = remember(rows, query) { rows.filter { it.header || StreamPicker.matchesFilter(it, query) } }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = STREAM_SHEET_MIN_WIDTH, max = STREAM_SHEET_MAX_WIDTH),
    ) {
        DisableSelection {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(STREAM_SHEET_FILTER_LABEL) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (shown.none { !it.header }) {
                    Text(
                        "Nothing matches.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.height(STREAM_SHEET_ROW_HEIGHT)
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                    return@Column
                }
                Column(
                    Modifier
                        .heightIn(max = STREAM_SHEET_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                ) {
                    shown.forEach { item ->
                        if (item.header) SheetRunHeader(item) else {
                            SheetRow(
                                item = item,
                                selected = streamChipSelected(item, selected),
                                enabled = enabled,
                                onPick = { onPick(item.agentId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One finished stream, at the width a chip never had. */
@Composable
private fun SheetRow(
    item: StreamPicker.Item,
    selected: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            // PINNED, so the cap above can only ever cut between rows.
            .height(STREAM_SHEET_ROW_HEIGHT)
            .background(if (selected) scheme.surfaceContainerHigh else scheme.surface)
            .clickable(enabled = enabled, onClick = onPick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            StreamPicker.sheetLabel(item),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = scheme.onSurface.copy(alpha = streamChipTextAlpha(finished = !selected, enabled = enabled)),
            maxLines = 1,
            // Ellipsised at the OVERLAY's width rather than at a chip's 28
            // characters — which is the whole difference between a list you can
            // read and dozens of rows of the same clipped prefix.
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A run's name inside the sheet. Labels the rows under it; never pickable. */
@Composable
private fun SheetRunHeader(item: StreamPicker.Item) {
    Row(
        Modifier.fillMaxWidth().height(STREAM_SHEET_ROW_HEIGHT).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.fullLabel.ifBlank { item.label },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The search box's own label, named so a test can hold the sheet to it. */
const val STREAM_SHEET_FILTER_LABEL: String = "Filter finished streams"

/**
 * One row of the fold, pinned.
 *
 * ⚠ THE CAP HAS TO BE A MULTIPLE OF THIS. A flat ceiling over rows of whatever
 * height the text made sliced the first and last rows in half, and a torn edge
 * with no fade and no scrollbar reads as a rendering fault rather than as "there
 * is more below".
 */
val STREAM_SHEET_ROW_HEIGHT = 34.dp

/** How many whole rows the fold shows before it scrolls inside itself. */
const val STREAM_SHEET_ROWS: Int = 7

/**
 * How tall the unfolded list is allowed to get before it scrolls.
 *
 * The cap is the point: the fold exists so a session that fanned out two hundred
 * times does not own the screen, and an unfold with no ceiling gives it away
 * again. Derived rather than typed, so it can only ever end on a whole row.
 */
val STREAM_SHEET_MAX_HEIGHT = STREAM_SHEET_ROW_HEIGHT * STREAM_SHEET_ROWS

/** Wide enough for a real title, capped so it does not become the window. */
private val STREAM_SHEET_MIN_WIDTH = 320.dp
private val STREAM_SHEET_MAX_WIDTH = 560.dp

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
                selected && enabled && !dim -> scheme.onSurface
                // Dimmed over onSURFACE, not onSurfaceVariant: the muted role was
                // already the dim one, so dimming it again was dimming twice.
                dim && enabled -> scheme.onSurface.copy(alpha = streamChipTextAlpha(true, true))
                else -> scheme.onSurfaceVariant.copy(alpha = streamChipTextAlpha(dim, enabled))
            },
            maxLines = 1,
        )
        if (dim) {
            Spacer(Modifier.width(5.dp))
            Text(
                STREAM_FINISHED_HINT,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = streamChipTextAlpha(true, enabled)),
                maxLines = 1,
            )
        }
    }
}
