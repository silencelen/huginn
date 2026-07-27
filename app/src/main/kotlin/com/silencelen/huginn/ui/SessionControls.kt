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
private val MODELS = listOf(
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
    onCommand: (String) -> Unit,
    onCycleMode: () -> Unit,
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
                label = prettyModel(model),
                options = MODELS.map { it.first to it.second },
                onPick = { onCommand("/model $it") },
            )
            PickerChip(
                label = effort?.replaceFirstChar { it.uppercase() } ?: "Effort",
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

@Composable
private fun PickerChip(
    label: String,
    options: List<Pair<String, String>>,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
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

/** `claude-fable-5` reads better as `Fable`. */
fun prettyModel(model: String?): String {
    if (model.isNullOrBlank()) return "Model"
    val m = model.removePrefix("claude-")
    return MODELS.firstOrNull { m.startsWith(it.first) }?.second
        ?: m.substringBefore('-').replaceFirstChar { it.uppercase() }
}
