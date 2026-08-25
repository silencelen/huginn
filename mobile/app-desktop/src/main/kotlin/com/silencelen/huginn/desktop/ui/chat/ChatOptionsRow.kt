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
import com.silencelen.huginn.data.ModelChoice
import com.silencelen.huginn.ui.ModelLabels

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
    onModel: (String) -> Unit,
    onEffort: (String) -> Unit,
    onMode: (String) -> Unit,
) {
    val localNow = ModelLabels.isLocal(model, models)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PickerChip(
            label = if (model.isNullOrBlank()) "Default model" else ModelLabels.model(model, models),
            // A local chat is pinned to its machine for life: clearing back to a
            // host default would mean a different engine, which the daemon
            // refuses — so the clear entry is absent rather than doomed.
            options = ModelLabels.options(models, ModelLabels.PickerSite.CHAT) +
                (if (localNow) emptyList() else listOf(CLEAR to "Host default")),
            enabled = enabled,
            onPick = onModel,
        )
        // A local model has no effort knob — the chip is absent, not disabled.
        if (!localNow) {
            PickerChip(
                label = if (effort.isNullOrBlank()) "Default effort" else ModelLabels.effort(effort),
                options = ModelLabels.effortOptions() + (CLEAR to "Host default"),
                enabled = enabled,
                onPick = onEffort,
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
            PickerChip(
                label = if (mode == "act") "Act" else "Ask",
                options = listOf("ask" to "Ask — reasoning and memory, no tools", "act" to "Act — files, commands, the web"),
                enabled = enabled,
                onPick = onMode,
            )
        }
    }
}

/** An empty string is how the daemon is told to go back to its own default. */
private const val CLEAR = ""

@Composable
private fun PickerChip(
    label: String,
    options: List<Pair<String, String>>,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            enabled = enabled,
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(2.dp))
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(15.dp))
                }
            },
            colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.onSurface),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text, style = MaterialTheme.typography.bodySmall) },
                    onClick = { open = false; onPick(value) },
                )
            }
        }
    }
}
