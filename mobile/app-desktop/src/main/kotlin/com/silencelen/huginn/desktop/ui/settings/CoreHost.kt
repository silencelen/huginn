package com.silencelen.huginn.desktop.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.core.CoreBoot
import com.silencelen.huginn.desktop.core.CoreClient
import com.silencelen.huginn.desktop.core.CoreRoute
import com.silencelen.huginn.desktop.core.CoreRoutes
import com.silencelen.huginn.ui.settings.SettingsActionRow
import com.silencelen.huginn.ui.settings.SettingsFieldRow
import com.silencelen.huginn.ui.settings.SettingsReadOnlyRow
import com.silencelen.huginn.ui.settings.SettingsToggleRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * Settings > Host while huginn core carries this app (docs/DESKTOP-VIA-CORE.md):
 * the link is the core's, so this page shows the core's route book - the
 * addresses it reaches huginn on, which one is in use, how each last went - and
 * edits it there (`/core/routes`, the same book `huginn core route` shows).
 * There is no token here to show or type: the core holds it.
 */
@Composable
internal fun ColumnScope.CoreHostSection(store: AppStore, via: CoreBoot.Outcome.ViaCore) {
    val core = remember(via.socket) { CoreClient(via.socket) }
    val scope = rememberCoroutineScope()
    var book by remember { mutableStateOf<CoreRoutes?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf("") }

    suspend fun refresh() {
        book = runCatching { core.routes() }.onFailure { message = "huginn core did not answer: ${it.message}" }.getOrNull() ?: book
    }
    fun act(what: suspend () -> CoreRoutes) {
        scope.launch {
            runCatching { what() }.onSuccess { book = it; message = null }
                .onFailure { message = (it as? CoreClient.CoreError)?.message ?: it.message }
        }
    }
    LaunchedEffect(via.socket) {
        while (true) {
            refresh()
            delay(10_000) // health changes as requests come and go
        }
    }

    SettingsReadOnlyRow(
        id = "host.link",
        title = "Link to huginn",
        value = "through huginn core ${via.coreVersion}",
        summary = "huginn core keeps the token and proves huginn's certificate; this app sends " +
            "everything through it and keeps no credential of its own." + (via.note?.let { " At this start: $it." } ?: ""),
    )
    val routes = book?.routes.orEmpty()
    for (r in routes) {
        // Pinning picks one address over the others: with one address there is
        // nothing to pick (run 3 showed a lone "Pin"). A pin left from before
        // stays undoable.
        if (routes.size > 1 || r.pinned) {
            SettingsActionRow(
                id = "host.core-route.${r.url}",
                title = r.url + tags(r),
                summary = health(r),
                actionLabel = if (r.pinned) "Unpin" else "Pin",
                onAction = { act { core.patchRoutes(pinned = if (r.pinned) "" else r.url) } },
            )
        } else {
            SettingsReadOnlyRow(id = "host.core-route.${r.url}", title = r.url + tags(r), summary = health(r))
        }
        if (!r.preferred) {
            SettingsActionRow(
                id = "host.core-route-remove.${r.url}",
                title = "Remove ${r.name.ifBlank { r.url }}",
                actionLabel = "Remove",
                destructive = true,
                onAction = { act { core.removeRoute(r.url) } },
            )
        }
    }
    book?.takeIf { routes.size > 1 }?.let { b ->
        SettingsToggleRow(
            id = "host.core-autoswitch",
            title = "Switch addresses automatically",
            checked = b.autoSwitch,
            onCheckedChange = { on -> act { core.patchRoutes(autoSwitch = on) } },
            summary = "When the address in use does not answer, huginn core tries the next one, " +
                "and moves back to the address it was paired on once that answers again.",
        )
    }
    SettingsFieldRow(
        id = "host.core-route-add",
        title = "Add an address",
        value = adding,
        onValueChange = { adding = it },
        summary = "Another address of the same huginn, e.g. its mesh or LAN address (https://host:8787).",
        trailing = {
            Button(onClick = { val u = adding.trim(); if (u.isNotEmpty()) act { core.addRoute(u).also { adding = "" } } }) { Text("Add") }
        },
    )
    message?.let { SettingsReadOnlyRow(id = "host.core-note", title = "huginn core", value = it) }
}

private fun tags(r: CoreRoute): String {
    val t = buildList {
        if (r.name.isNotBlank()) add(r.name)
        if (r.kind.isNotBlank()) add(r.kind)
        if (r.active) add("in use")
        if (r.preferred) add("paired on")
        if (r.pinned) add("pinned")
    }
    return if (t.isEmpty()) "" else "  (" + t.joinToString(", ") + ")"
}

private fun health(r: CoreRoute): String {
    val seen = r.lastSeenAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val fail = r.lastFailAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    return when {
        fail != null && (seen == null || fail.isAfter(seen)) -> "not answering since ${ago(fail)}: ${r.lastError}"
        seen != null -> "answered ${ago(seen)}"
        else -> "not tried yet"
    }
}

private fun ago(t: Instant): String {
    val s = Duration.between(t, Instant.now()).seconds.coerceAtLeast(0)
    return when {
        s < 60 -> "$s s ago"
        s < 3600 -> "${s / 60} min ago"
        else -> "${s / 3600} h ago"
    }
}
