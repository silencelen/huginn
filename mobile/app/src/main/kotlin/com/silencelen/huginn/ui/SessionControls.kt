package com.silencelen.huginn.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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

/**
 * Model / effort / permission-mode controls for a live session.
 *
 * These are driven the only way they can be from outside the process: by typing
 * the same slash commands a person would (`/model`, `/effort`) into the pane, and
 * by sending Shift+Tab to cycle permission modes, which is what that key does in
 * Claude Code. So this is a shortcut for keys you could send by hand, not a
 * separate control channel — which is also why the current values are read back
 * from the session's own transcript and pane rather than tracked here.
 */
@Composable
fun SessionControls(
    model: String?,
    effort: String?,
    permissionMode: String?,
    models: List<ModelChoice>,
    onCommand: (String) -> Unit,
    onCycleMode: () -> Unit,
    contextPercent: Int? = null,
    compacting: Boolean = false,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status readouts lead so they stay visible if the controls scroll:
            // the "Compacting…" marker and the "context used" meter (both render
            // nothing when there's nothing to say).
            if (compacting) CompactingChip()
            ContextMeter(contextPercent)
            PickerChip(
                label = ModelLabels.model(model),
                // SESSION site: Claude rows only — this chip types /model into a
                // live pane, where a local row could never work.
                options = ModelLabels.options(models, ModelLabels.PickerSite.SESSION),
                onPick = { onCommand("/model $it") },
            )
            PickerChip(
                // Reads as the current value, like the model and mode chips; the
                // word "Effort" only appears when the session has not reported one.
                label = ModelLabels.effort(effort),
                options = ModelLabels.effortOptions(),
                onPick = { onCommand("/effort $it") },
            )
            // Permission mode has no slash command that sets it directly; Shift+Tab
            // cycles it, which is what the key row does too.
            AssistChip(
                onClick = onCycleMode,
                label = { Text(permissionMode?.replaceFirstChar { it.uppercase() } ?: "Mode") },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    }
}

/**
 * The same controls for a chat. A chat has no permission mode to cycle (its tool
 * access is fixed by Ask/Act at creation), so that slot states the mode instead of
 * offering to change it.
 */
@Composable
fun ChatOptionsBar(
    mode: String,
    model: String?,
    effort: String?,
    models: List<ModelChoice>,
    enabled: Boolean,
    onModel: (String) -> Unit,
    onEffort: (String) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val localNow = ModelLabels.isLocal(model, models)
            PickerChip(
                label = if (model == null) "Default model" else ModelLabels.model(model, models),
                options = ModelLabels.options(models, ModelLabels.PickerSite.CHAT),
                enabled = enabled,
                onPick = onModel,
            )
            // A local model has no effort knob — five levels that do nothing
            // would be a lying control, so the chip is absent, not disabled.
            if (!localNow) {
                PickerChip(
                    label = if (effort == null) "Default effort" else ModelLabels.effort(effort),
                    options = ModelLabels.effortOptions(),
                    enabled = enabled,
                    onPick = onEffort,
                )
            }
            AssistChip(
                onClick = { },
                enabled = false,
                label = { Text(if (mode == "act") "Act" else "Ask") },
            )
            if (localNow) {
                Text(
                    ModelLabels.LOCAL_ASK_ONLY,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PickerChip(
    label: String,
    options: List<Pair<String, String>>,
    enabled: Boolean = true,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            enabled = enabled,
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label)
                    Spacer(Modifier.width(2.dp))
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            },
            colors = AssistChipDefaults.assistChipColors(
                labelColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { open = false; onPick(value) },
                )
            }
        }
    }
}

// The duplicate label tables and pretty* helpers that used to live here are
// gone — ModelLabels in :core is the one copy, as its header always demanded.
