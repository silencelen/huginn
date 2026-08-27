package com.silencelen.huginn.desktop.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.ModelChoice

/**
 * Model, effort and permission mode for a live session.
 *
 * These are driven the only way they can be from outside the process: by typing
 * the same slash commands a person would (`/model`, `/effort`) into the pane, and
 * by sending Shift+Tab to cycle permission modes, which is what that key does in
 * Claude Code. A shortcut for keys you could send by hand, not a second control
 * channel — which is also why the current values are read back from the session's
 * transcript and pane rather than tracked here.
 *
 * On the desktop these ARE the header's state marks rather than a second bar of
 * chips under them: the header already had to say what model and mode a session is
 * on, and a row that displays a value beside a control that changes it is the same
 * verb twice. It also costs no height — and on the Screen tab, height is geometry,
 * and geometry is somebody's real terminal being resized.
 */
@Composable
fun ControlPicker(
    label: String,
    options: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Text(
            "$label ▾",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // NEVER WRAPS. These sit at the end of a header row that can lose
            // 360dp to the pages panel between one frame and the next, and a
            // two-word model name folding onto a second line does not just look
            // wrong — it makes the whole header taller, which on the Screen tab is
            // measured into rows and pushed to a real tmux window.
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { open = true }
                .background(if (open) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(value) })
            }
        }
    }
}

/**
 * A mark that acts rather than opens: permission mode has no slash command that
 * sets it directly, so clicking it sends the Shift+Tab that cycles it.
 */
@Composable
fun ControlAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        // Single line, for the same reason as [ControlPicker] above.
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * The host's model list, fetched once per open session.
 *
 * Empty until it lands, which [com.silencelen.huginn.ui.ModelLabels] turns into
 * the built-in fallback list — a picker that is briefly incomplete beats a picker
 * that is briefly absent, and a failure here must not cost the reader the control.
 */
@Composable
fun rememberModels(client: HuginnClient): List<ModelChoice> {
    var models by remember(client) { mutableStateOf<List<ModelChoice>>(emptyList()) }
    LaunchedEffect(client) {
        runCatching { client.models() }.onSuccess { models = it }
    }
    return models
}
