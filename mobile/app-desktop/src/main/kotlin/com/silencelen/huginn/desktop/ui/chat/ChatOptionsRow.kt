package com.silencelen.huginn.desktop.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.ChatFast
import com.silencelen.huginn.data.ModelChoice
import com.silencelen.huginn.ui.ChatFastLabels
import com.silencelen.huginn.ui.ModelLabels
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height

/**
 * Model, effort and mode for a chat.
 *
 * All three apply to the NEXT turn — the daemon fixes the flags when a run
 * spawns — so the whole row is disabled while one is in flight rather than
 * accepting a change it would then silently not honour.
 *
 * Each chip READS AS ITS CURRENT VALUE. A chip labelled "Model" that happens to
 * be set to Opus tells you what the control is; a chip labelled "Opus 5" tells
 * you what the next turn will do, which is the only reason to look.
 *
 * The phone's twin is `ChatOptionsBar` in `:app`. It stays a separate composable
 * for now because the two genuinely differ: this one lets Ask/Act be changed
 * (the daemon accepts `mode` on PATCH) and is sized for a pointer, where the
 * phone states the mode and sizes for a thumb. The LABELS are shared — they come
 * from `ModelLabels` in `:core` — because that is the part that must never
 * disagree.
 */
@Composable
fun ChatOptionsRow(
    mode: String?,
    model: String?,
    effort: String?,
    models: List<ModelChoice>,
    enabled: Boolean,
    started: Boolean,
    onModel: (String) -> Unit,
    onEffort: (String) -> Unit,
    onMode: (String) -> Unit,
    /** The chat's fast-mode choice: null is the host default (appd 3.14.0). */
    fast: Boolean? = null,
    /** The daemon serves `/v1/settings/claude`, so a PATCH with `fast` will be honoured. */
    fastSupported: Boolean = false,
    onFast: (ChatFast) -> Unit = {},
) {
    val localNow = ModelLabels.isLocal(model, models)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OptionPickerChip(
            label = if (model.isNullOrBlank()) "Default model" else ModelLabels.model(model, models),
            // The menu is the daemon's truth: a STARTED chat is pinned to its
            // family and machine, so ModelLabels shrinks the rows accordingly —
            // an unstarted chat may still go anywhere (daemon 2.77.0). The
            // clear entry follows the same rule: only a started local chat has
            // no legal way back to the host default, so only there is it
            // absent rather than doomed.
            options = ModelLabels.options(models, ModelLabels.PickerSite.CHAT, current = model, started = started) +
                (if (localNow && started) emptyList() else listOf(CLEAR to "Host default")),
            enabled = enabled,
            onPick = onModel,
        )
        // A local model has no effort knob — the chip is absent, not disabled.
        if (!localNow) {
            OptionPickerChip(
                label = if (effort.isNullOrBlank()) "Default effort" else ModelLabels.effort(effort),
                options = ModelLabels.effortOptions() + (CLEAR to "Host default"),
                enabled = enabled,
                onPick = onEffort,
            )
        }
        // Fast mode, tri-state like the model and effort: the host default, on, or
        // off. Only against a daemon that stores it, and never for a local model,
        // which has no fast mode to be in.
        if (fastSupported && !localNow) {
            OptionPickerChip(
                label = ChatFastLabels.label(fast),
                options = ChatFastLabels.options(),
                enabled = enabled,
                onPick = { onFast(ChatFastLabels.choice(it)) },
            )
        }
        if (localNow) {
            AssistChip(
                onClick = { },
                enabled = false,
                label = { Text("Ask", style = MaterialTheme.typography.labelMedium) },
            )
            Text(
                ModelLabels.LOCAL_ASK_ONLY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OptionPickerChip(
                label = if (mode == "act") "Act" else "Ask",
                options = listOf("ask" to "Ask — reads and searches, changes nothing", "act" to "Act — files, commands, the web"),
                enabled = enabled,
                onPick = onMode,
            )
        }
    }
}

/** An empty string is how the daemon is told to go back to its own default. */
private const val CLEAR = ""

/**
 * THE desktop picker chip: the chat's options row and the session header both
 * draw through it (PR 4), so a model picker looks like a model picker wherever
 * it is. Reads as its current value; the arrow says it opens.
 *
 * NEVER WRAPS: the session header can lose 360dp to the pages panel between one
 * frame and the next, and a two-word model name folding onto a second line makes
 * the header taller — which on the Screen tab is measured into tmux rows.
 */
@Composable
fun OptionPickerChip(
    label: String,
    options: List<Pair<String, String>>,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        AssistChip(
            onClick = { open = true },
            enabled = enabled,
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                    Spacer(Modifier.width(2.dp))
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(15.dp))
                }
            },
            colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.onSurface),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                // Tighter than Material's 48 dp row, not tight (owner, 10-01: the
                // model/effort/mode lists were "loose"). A mouse needs no thumb target.
                DropdownMenuItem(
                    text = { Text(text, style = MaterialTheme.typography.bodySmall) },
                    onClick = { open = false; onPick(value) },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                )
            }
        }
    }
}

/**
 * A chip that acts rather than opens, drawn like [OptionPickerChip] without the
 * arrow: permission mode has no slash command that sets it, so the session
 * header's mode chip sends the Shift+Tab that cycles it.
 */
@Composable
fun OptionActionChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        modifier = modifier,
        label = { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) },
        colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.onSurface),
    )
}
