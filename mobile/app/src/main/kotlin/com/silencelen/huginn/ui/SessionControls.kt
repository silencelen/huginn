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
/** Used only until the host's own list arrives, and if discovery ever fails. */
private val FALLBACK_MODELS = listOf(
    "fable" to "Fable",
    "opus" to "Opus",
    "sonnet" to "Sonnet",
    "haiku" to "Haiku",
)

private val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")

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
                label = prettyModel(model),
                options = modelOptions(models),
                onPick = { onCommand("/model $it") },
            )
            PickerChip(
                // Reads as the current value, like the model and mode chips; the
                // word "Effort" only appears when the session has not reported one.
                label = prettyEffort(effort),
                options = EFFORTS.map { it to it.replaceFirstChar { c -> c.uppercase() } },
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
            PickerChip(
                label = if (model == null) "Default model" else prettyModel(model),
                options = modelOptions(models),
                enabled = enabled,
                onPick = onModel,
            )
            PickerChip(
                label = if (effort == null) "Default effort" else prettyEffort(effort),
                options = EFFORTS.map { it to it.replaceFirstChar { c -> c.uppercase() } },
                enabled = enabled,
                onPick = onEffort,
            )
            AssistChip(
                onClick = { },
                enabled = false,
                label = { Text(if (mode == "act") "Act" else "Ask") },
            )
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

/** `xhigh` reads as `Xhigh`; an unknown or missing level falls back to the word. */
fun prettyEffort(effort: String?): String =
    effort?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: "Effort"

fun modelOptions(models: List<ModelChoice>): List<Pair<String, String>> =
    if (models.isEmpty()) FALLBACK_MODELS else models.map { it.id to it.display }

/**
 * The label for the model control.
 *
 * Both sources already carry the version — the pane writes "Opus 5" and the
 * server formats ids into "Opus 4.8" — so this must NOT collapse them to a
 * family name. It used to, which is why the control said "Opus" when Claude Code
 * can be running Opus 5 or Opus 4.8 and the difference matters.
 */
fun prettyModel(model: String?): String {
    val m = model?.trim()
    if (m.isNullOrEmpty()) return "Model"
    return m
}
