package com.silencelen.huginn.desktop.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.ModelChoice
import com.silencelen.huginn.desktop.ui.chat.OptionActionChip
import com.silencelen.huginn.desktop.ui.chat.OptionPickerChip

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
    /** Greyed, unpickable rows under the options (see [OptionPickerChip]). */
    disabled: List<String> = emptyList(),
    onPick: (String) -> Unit,
) {
    // The SAME chip the chat's options row draws (PR 4), where this used to be a
    // bare "label ▾" in labelSmall: one picker vocabulary across the desktop.
    OptionPickerChip(label = label, options = options, modifier = modifier, disabled = disabled, onPick = onPick)
}

/**
 * A mark that acts rather than opens: permission mode has no slash command that
 * sets it directly, so clicking it sends the Shift+Tab that cycles it.
 */
@Composable
fun ControlAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OptionActionChip(label = label, modifier = modifier, onClick = onClick)
}

/**
 * The host's model list, fetched when the open session changes and every few
 * minutes while it stays open.
 *
 * ⚠ IT WAS KEYED ON THE CLIENT ALONE, so it was fetched once per app run: the
 * view stays composed across session switches, and a model the host gained
 * later (a Claude Code update) never reached the menu until a restart. The
 * fetch is cheap — the daemon caches discovery per installed CLI.
 *
 * Empty until it lands, which [com.silencelen.huginn.ui.ModelLabels] turns into
 * the built-in fallback list — a picker that is briefly incomplete beats a picker
 * that is briefly absent, and a failure here must not cost the reader the control.
 * A switch keeps the previous list until the new one lands, for the same reason.
 */
@Composable
fun rememberModels(client: HuginnClient, session: String? = null): List<ModelChoice> {
    var models by remember(client) { mutableStateOf<List<ModelChoice>>(emptyList()) }
    LaunchedEffect(client, session) {
        while (true) {
            runCatching { client.models() }.onSuccess { models = it }
            kotlinx.coroutines.delay(MODELS_REFRESH_MS)
        }
    }
    return models
}

private const val MODELS_REFRESH_MS = 5 * 60_000L
