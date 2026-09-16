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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.silencelen.huginn.data.PinnedRoute
import com.silencelen.huginn.data.RouteBook
import com.silencelen.huginn.data.RouteHealth

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

// ------------------------------------------------------------- pinned routes

/**
 * What a shell can do to the route list, as one value rather than eight
 * callbacks in a signature.
 *
 * The same argument as [AccountsIo]: the vocabulary — what a rename does to a
 * blank name, what removing the active pin means, which refusal is worth
 * printing — is the part that must not be written twice, and it can only live in
 * one file if that file sees the shells' verbs rather than pre-composed strings.
 * Every one of these is a [RouteBook] operation the
 * shell persists; none of them decide anything.
 */
class RouteListActions(
    val activate: (String) -> Unit = {},
    val rename: (id: String, name: String) -> Unit = { _, _ -> },
    val setUrl: (id: String, url: String) -> Unit = { _, _ -> },
    val move: (id: String, delta: Int) -> Unit = { _, _ -> },
    val remove: (String) -> Unit = {},
    val add: (name: String, url: String) -> Unit = { _, _ -> },
    val setAutoSwitch: (Boolean) -> Unit = {},
    val findLive: () -> Unit = {},
)

/**
 * THE ROUTE LIST — one composable, both shells.
 *
 * This is the row that replaced three different products. The phone had named
 * chips, a *Find live route* button, a pin/unpin toggle and a Base URL field;
 * the desktop had a flat `known routes: Tailscale http://… Yggdrasil http://…`
 * string and a bare address box with its own Save. Neither could hold a third
 * address, and both spelled Tailscale and Yggdrasil into the interface as if
 * they were the only two networks that would ever exist.
 *
 * WHAT IT SHOWS, and why each part is here rather than somewhere else:
 *
 * - **The owner's names**, editable, because *"Tailscale"* is a word this app
 *   chose and *"the mesh"* might be the word its reader uses.
 * - **A kind badge**, computed from the address by the same guard that decides
 *   whether plain HTTP may carry the bearer — so it costs nothing and cannot
 *   disagree with the rule.
 * - **A state dot** in the row's own text flow. The house mark. ⚠ NEVER a rail
 *   down the side of a row — [SettingsHouseRulesTest] greps this file for one.
 * - **The address under the name**, which is where "Base URL" went: editing a
 *   pin IS how an address is typed now, and the active one is always legible
 *   without opening anything.
 * - **Order controls**, because the order IS the preference — auto-switching
 *   takes the first route on this list that answers, not the fastest.
 *
 * ⚠ IT DECIDES NOTHING. Every action goes out through [RouteListActions] to a
 * shell that applies it to a `RouteBook` in `:core` and persists the result.
 * That is what keeps the cap, the guard and the duplicate rule out of the UI,
 * where they would exist twice and disagree once.
 */
@Composable
fun SettingsRouteListRow(
    book: RouteBook,
    actions: RouteListActions,
    modifier: Modifier = Modifier,
    health: Map<String, RouteHealth> = emptyMap(),
    nowMs: Long = 0,
    id: String = "host.route",
    title: String = "Routes",
    summary: String? = null,
    highlighted: Boolean = false,
    finding: Boolean = false,
    note: String? = null,
    suggestedUrl: String = "",
) {
    var editing by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

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

        if (book.routes.isEmpty()) {
            // ⚠ THE EMPTY STATE IS THE CONNECT FLOW. A fresh install pins
            // nothing, so this sentence is the first thing a new owner reads
            // about connecting — it has to say what to type, not that a list is
            // empty.
            Text(
                "No routes yet. Add the address huginn answers on and it becomes the first one.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        for ((index, route) in book.routes.withIndex()) {
            RouteRow(
                route = route,
                active = route.id == book.activeId,
                first = index == 0,
                last = index == book.routes.lastIndex,
                health = health[route.id],
                nowMs = nowMs,
                editing = editing == route.id,
                onEdit = { editing = if (editing == route.id) null else route.id },
                actions = actions,
            )
        }

        Row(
            Modifier.padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!book.isFull) {
                TextButton(onClick = { adding = !adding }) { Text(if (adding) "Cancel" else "Add a route") }
            } else {
                Text(
                    "Eight routes is the limit.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (book.routes.isNotEmpty()) {
                TextButton(onClick = actions.findLive, enabled = !finding) {
                    Text(if (finding) "Finding…" else "Find live route")
                }
            }
        }

        if (adding) {
            RouteForm(
                initialName = "",
                initialUrl = suggestedUrl,
                confirmLabel = "Add",
                onConfirm = { name, url -> actions.add(name, url); adding = false },
                onCancel = { adding = false },
            )
        }

        if (book.routes.isNotEmpty()) {
            SettingsToggleRow(
                id = "host.route.auto",
                title = "Switch automatically",
                checked = book.autoSwitch,
                onCheckedChange = actions.setAutoSwitch,
                summary = if (book.autoSwitch) {
                    "Uses the first route on this list that answers."
                } else {
                    "Stays on ${book.activeName.ifBlank { "the chosen route" }} even when it is not answering."
                },
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (!note.isNullOrBlank()) {
            Text(
                note,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp, start = 8.dp),
            )
        }
    }
}

/** Just enough room for a glyph or a short verb — see [RouteRow]. */
private val TIGHT = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp)

/** One pinned route: the name, the badge, the state, the address, the handles. */
@Composable
private fun RouteRow(
    route: PinnedRoute,
    active: Boolean,
    first: Boolean,
    last: Boolean,
    health: RouteHealth?,
    nowMs: Long,
    editing: Boolean,
    onEdit: () -> Unit,
    actions: RouteListActions,
) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsStateDot(dotColour(health))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        route.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        route.kind.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    listOfNotNull(route.url, reachedWords(health, nowMs)).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // "In use" is a WORD, not a tint: the state dot already owns colour
            // in this row and a second colour-coded thing beside it would be two
            // vocabularies for two different facts.
            if (active) {
                Text(
                    "in use",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 4.dp),
                )
            } else {
                TextButton(onClick = { actions.activate(route.id) }, contentPadding = TIGHT) { Text("Use") }
            }
            // ⚠ TIGHT CONTENT PADDING. A Material TextButton is 64dp wide at
            // minimum, and three of them at full width spread one route's controls
            // across a third of the page — the arrows end up further from the row
            // they reorder than from each other.
            TextButton(onClick = { actions.move(route.id, -1) }, enabled = !first, contentPadding = TIGHT) { Text("↑") }
            TextButton(onClick = { actions.move(route.id, 1) }, enabled = !last, contentPadding = TIGHT) { Text("↓") }
            TextButton(onClick = onEdit, contentPadding = TIGHT) { Text(if (editing) "Done" else "Edit") }
        }
        if (editing) {
            RouteForm(
                initialName = route.name,
                initialUrl = route.url,
                confirmLabel = "Save",
                onConfirm = { name, url ->
                    if (name != route.name) actions.rename(route.id, name)
                    if (url != route.url) actions.setUrl(route.id, url)
                    onEdit()
                },
                onCancel = onEdit,
                onRemove = { actions.remove(route.id); onEdit() },
            )
        }
    }
}

/** The name and the address, together — adding one and editing one are the same form. */
@Composable
private fun RouteForm(
    initialName: String,
    initialUrl: String,
    confirmLabel: String,
    onConfirm: (String, String) -> Unit,
    onCancel: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    Column(Modifier.padding(start = 20.dp, top = 6.dp, bottom = 4.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text("Name") },
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            singleLine = true,
            label = { Text("Address") },
            modifier = Modifier.padding(top = 6.dp).widthIn(max = 420.dp).fillMaxWidth(),
        )
        Row(
            Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { onConfirm(name, url) }, enabled = url.isNotBlank()) { Text(confirmLabel) }
            TextButton(onClick = onCancel) { Text("Cancel") }
            if (onRemove != null) {
                TextButton(onClick = onRemove) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * Untried is a THIRD state and is drawn as one: a route nobody has probed yet is
 * not a route that failed, and colouring it red would make every fresh launch
 * look like an outage.
 */
@Composable
private fun dotColour(health: RouteHealth?): Color = when (health?.reachable) {
    true -> MaterialTheme.colorScheme.primary
    false -> MaterialTheme.colorScheme.error
    null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
}

/** "last reached 4m ago" — and nothing at all before the first probe. */
internal fun reachedWords(health: RouteHealth?, nowMs: Long): String? {
    val at = health?.lastOkAt ?: 0
    if (at <= 0 || nowMs <= 0) return null
    val secs = ((nowMs - at) / 1000).coerceAtLeast(0)
    val words = when {
        secs < 60 -> "just now"
        secs < 3600 -> "${secs / 60}m ago"
        secs < 86400 -> "${secs / 3600}h ago"
        else -> "${secs / 86400}d ago"
    }
    return if (words == "just now") "reached just now" else "last reached $words"
}
