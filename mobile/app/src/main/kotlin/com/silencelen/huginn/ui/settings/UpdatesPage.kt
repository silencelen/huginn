package com.silencelen.huginn.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.update.AppUpdateState
import com.silencelen.huginn.update.UpdateHandoff

/**
 * Which build is installed, whether a newer one exists, and how to hand
 * somebody the facts about it.
 *
 * The update half checks and stops: this app installs nothing, not even itself
 * (see [com.silencelen.huginn.update.PhoneUpdater]). A found release's one
 * button opens the store app that installs it, or the release page on a phone
 * without the store ([UpdateHandoff]). **There is no update channel and never
 * will be**: it is a compile-time constant by explicit security decision, so
 * this page has a repo it reads from and no picker.
 *
 * COPY DIAGNOSTICS is the owner's answer to Q3 of the redesign. The phone had
 * no diagnostics at all — no bundle, no logcat, no export — while the question
 * it answers ("why did nothing arrive last night") is more a phone question
 * than a desktop one. The *Delivery details* rows already held most of the
 * facts; this makes them pasteable.
 *
 * ⚠ THE BUNDLE CARRIES NO TOKEN, by construction rather than by redaction — see
 * [diagnosticsBundle]. It is meant to be pasted into a conversation with
 * somebody else.
 */
@Composable
fun UpdatesPage(
    state: AppUpdateState,
    installedVersion: String,
    repo: String,
    /** Whether the store app is on this phone: decides the found row's button and where it goes. */
    storeInstalled: Boolean,
    onCheck: () -> Unit,
    onOpen: (UpdateHandoff) -> Unit,
    onCopyDiagnostics: () -> Unit,
    highlight: String?,
) {
    ScopeOf("updates.check")
    val updateHighlighted = SettingsRowStyle.isHighlighted("updates.check", highlight)
    val installed = installedVersion.ifBlank { "—" }
    when (state) {
        is AppUpdateState.Idle -> SettingsActionRow(
            id = "updates.check",
            title = "Software update",
            summary = "Installed $installed · devstore installs updates from github.com/$repo",
            actionLabel = "Check for updates",
            onAction = onCheck,
            highlighted = updateHighlighted,
        )

        is AppUpdateState.Checking -> SettingsReadOnlyRow(
            id = "updates.check",
            title = "Software update",
            value = "checking…",
            summary = "Installed $installed",
            highlighted = updateHighlighted,
        )

        is AppUpdateState.UpToDate -> SettingsActionRow(
            id = "updates.check",
            title = "Software update",
            summary = "Up to date on ${state.versionName}.",
            actionLabel = "Check again",
            onAction = onCheck,
            highlighted = updateHighlighted,
        )

        is AppUpdateState.Available -> {
            val handoff = UpdateHandoff.choose({ storeInstalled }, state.releasePageUrl)
            SettingsActionRow(
                id = "updates.check",
                title = "Update available: ${state.versionName}",
                summary = when (handoff) {
                    UpdateHandoff.Store -> "Installed $installed · devstore installs it"
                    is UpdateHandoff.ReleasePage ->
                        "Installed $installed · no devstore on this phone; the release page has the APK"
                },
                actionLabel = handoff.label,
                onAction = { onOpen(handoff) },
                highlighted = updateHighlighted,
            )
            if (state.notes.isNotBlank()) {
                SettingsNote(state.notes, Modifier.padding(start = 8.dp, top = 2.dp), maxLines = 8)
            }
        }

        is AppUpdateState.Error -> {
            SettingsActionRow(
                id = "updates.check",
                title = "Software update",
                summary = state.message,
                actionLabel = "Try again",
                onAction = onCheck,
                highlighted = updateHighlighted,
            )
            Text(
                state.message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 8.dp, top = 2.dp),
            )
        }
    }

    Column(Modifier.padding(top = 10.dp)) {
        SettingsActionRow(
            id = "updates.copy-diagnostics",
            title = "Copy diagnostics",
            summary = "The versions, the route, and what has and has not been delivered — as text " +
                "you can paste. It carries no token.",
            actionLabel = "Copy",
            onAction = onCopyDiagnostics,
            highlighted = SettingsRowStyle.isHighlighted("updates.copy-diagnostics", highlight),
        )
    }
}
