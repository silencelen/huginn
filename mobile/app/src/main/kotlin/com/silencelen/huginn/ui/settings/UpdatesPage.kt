package com.silencelen.huginn.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.update.AppUpdateState

/**
 * Which build is installed, and how to hand somebody the facts about it.
 *
 * The update half is unchanged and stays three explicit steps — check, download,
 * install — so a background check never spends the owner's data and an APK never
 * installs without two taps and the system's own confirmation. **There is no
 * update channel and never will be**: it is a compile-time constant by explicit
 * security decision, so this page has a repo it reads from and no picker.
 *
 * COPY DIAGNOSTICS IS NEW, and is the owner's answer to Q3 of the redesign. The
 * phone had no diagnostics at all — no bundle, no logcat, no export — while the
 * question it answers ("why did nothing arrive last night") is more a phone
 * question than a desktop one. The *Delivery details* rows already held most of
 * the facts; this makes them pasteable.
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
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    highlight: String?,
) {
    val updateHighlighted = SettingsRowStyle.isHighlighted("updates.check", highlight)
    when (state) {
        is AppUpdateState.Idle -> SettingsActionRow(
            id = "updates.check",
            title = "Software update",
            summary = "Installed ${installedVersion.ifBlank { "—" }} · updates from github.com/$repo",
            actionLabel = "Check for updates",
            onAction = onCheck,
            highlighted = updateHighlighted,
        )

        is AppUpdateState.Checking -> SettingsReadOnlyRow(
            id = "updates.check",
            title = "Software update",
            value = "checking…",
            summary = "Installed ${installedVersion.ifBlank { "—" }}",
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
            val mb = if (state.size > 0) " (${state.size / 1_000_000} MB)" else ""
            SettingsActionRow(
                id = "updates.check",
                title = "Update available: ${state.versionName}",
                summary = "Installed ${installedVersion.ifBlank { "—" }} · downloads from github.com/$repo",
                actionLabel = "Download$mb",
                onAction = onDownload,
                highlighted = updateHighlighted,
            )
            if (state.notes.isNotBlank()) {
                SettingsNote(state.notes, Modifier.padding(start = 8.dp, top = 2.dp), maxLines = 8)
            }
        }

        is AppUpdateState.Downloading -> {
            val pct = state.fraction?.let { " ${(it * 100).toInt()}%" } ?: ""
            SettingsReadOnlyRow(
                id = "updates.check",
                title = "Software update",
                value = "downloading$pct",
                summary = "Version ${state.versionName}",
                highlighted = updateHighlighted,
            )
        }

        is AppUpdateState.Ready -> SettingsActionRow(
            id = "updates.check",
            title = "Update ready: ${state.versionName}",
            summary = "Downloaded and verified. Android asks its own question before it installs.",
            actionLabel = "Install and restart",
            onAction = onInstall,
            highlighted = updateHighlighted,
        )

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
