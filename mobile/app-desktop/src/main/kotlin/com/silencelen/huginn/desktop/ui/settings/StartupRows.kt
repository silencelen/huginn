package com.silencelen.huginn.desktop.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.setup.Autostart
import com.silencelen.huginn.desktop.setup.ClaudePath
import com.silencelen.huginn.desktop.setup.SetupHost
import com.silencelen.huginn.ui.settings.SettingsActionRow
import com.silencelen.huginn.ui.settings.SettingsRowStyle
import com.silencelen.huginn.ui.settings.SettingsToggleRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The two rows the setup flow shares with Settings.
 *
 * ⚠ ONE CONTROL PER VERB, which is why they are here rather than written twice.
 * "Start with your session" is a step of the first-run flow AND a row on the
 * Appearance page, and "Run setup again" is the door back into the flow — three
 * call sites, one composable each. The alternative was a wizard with its own
 * copies, which is how two screens end up disagreeing about what a toggle did.
 */

/**
 * Autostart, as a toggle that reports the DISK rather than the flag.
 *
 * The flag is the choice and the file is the effect, and they can disagree — an
 * upgrade that replaced the launcher, a restored profile, a desktop's own
 * Startup Applications editor removing the entry. So the summary line under this
 * row says what is actually on disk, and a write that fails says so instead of
 * leaving a switch sitting proudly in the on position over nothing.
 */
@Composable
fun ColumnScope.StartupRow(store: AppStore, mark: String? = null) {
    val scope = rememberCoroutineScope()
    val on by store.settings.autostart.collectAsState()
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    SettingsToggleRow(
        id = ID,
        title = "Start with your session",
        checked = on,
        enabled = !busy,
        onCheckedChange = { want ->
            busy = true
            scope.launch {
                // Off the UI thread: on Windows this spawns a PowerShell to
                // write a shell-link, and a settings toggle that blocks the
                // frame for a second reads as a hung window.
                val result = withContext(Dispatchers.IO) {
                    if (want) Autostart.enable() else Autostart.disable()
                }
                result.fold(
                    onSuccess = { store.settings.setAutostart(want); note = it },
                    // ⚠ THE FLAG DOES NOT MOVE ON A FAILED WRITE. A switch that
                    // flips while the file it stands for was never written is
                    // the exact lie this row exists to avoid.
                    onFailure = { note = it.message ?: "could not change the startup entry" },
                )
                busy = false
            }
        },
        summary = note ?: Autostart.describe(ClaudePath.isWindows()),
        highlighted = SettingsRowStyle.isHighlighted(ID, mark),
    )
}

/** The catalog id this row answers to. Named once; both call sites use it. */
const val AUTOSTART_ROW_ID: String = "appearance.autostart"

private const val ID = AUTOSTART_ROW_ID

/**
 * The way back into the first-run flow.
 *
 * ⚠ IDEMPOTENT AND NEVER DESTRUCTIVE — the owner's requirement, and the only
 * thing that makes it safe to press. It re-checks; it does not reset. A machine
 * that is already set up walks seven green steps and comes out the other side
 * with nothing changed, which is what makes it usable as a DIAGNOSTIC rather
 * than only as an onboarding.
 */
@Composable
fun ColumnScope.RunSetupAgainRow(mark: String? = null) {
    SettingsActionRow(
        id = "host.run-setup",
        title = "Run setup again",
        actionLabel = "Run setup",
        onAction = { SetupHost.ifReady { it.rerun() } },
        summary = "Walks the address, the token, claude, this computer, local AI, notifications " +
            "and autostart, checking each one. Nothing is reset — it only re-checks.",
        highlighted = SettingsRowStyle.isHighlighted("host.run-setup", mark),
        modifier = Modifier,
    )
}
