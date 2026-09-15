package com.silencelen.huginn.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.AppdRoutes

/**
 * Which huginn this phone talks to, and which Claude login serves it.
 *
 * The connection half is this shell's own product and stays exactly as it was —
 * named chips, Find live route, pin/unpin, ONE Save and connect — because
 * Android runs one VPN at a time and the route is the setting that most often
 * explains a dead app. The accounts half is now the SHARED editor: the phone's
 * old `SavedAccountRow` and its sign-in/sign-out row pair are gone, replaced by
 * `AccountsEditor`, which already carried the discipline the phone's copy did
 * not — the state dot rather than a row tint, the freshness word only when it is
 * not `fresh`, and the daemon's own sentence about a duplicate or a mismatch
 * rather than a hopeful one.
 *
 * ⚠ `showAutoswitch = false`. Account switching was rendered three times in
 * three vocabularies — a phone toggle, a desktop sentence, and the field in the
 * headroom form that is the only place its threshold and margin are also
 * editable. It is now rendered once, in *Usage & headroom*, and this page must
 * not grow a second opinion.
 */
@Composable
fun HostPage(
    baseUrl: String,
    token: String,
    connected: Boolean?,
    routePinned: Boolean,
    resolvingRoute: Boolean,
    onSelectRoute: (String) -> Unit,
    onResolveRoute: () -> Unit,
    onUnpinRoute: () -> Unit,
    onSave: (String, String) -> Unit,
    accountsIo: AccountsIo,
    openLink: (String) -> Boolean,
    signedIn: Boolean,
    onSignOut: () -> Unit,
    highlight: String?,
) {
    // Keyed on what the view model holds, so a route change from a chip refills
    // the field rather than leaving it editing the address you just left.
    var url by remember(baseUrl) { mutableStateOf(baseUrl) }
    var tok by remember(token) { mutableStateOf(token) }
    var reveal by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    SettingsGroup("Connection")

    SettingsReadOnlyRow(
        id = "host.route",
        title = "Route",
        summary = "Android runs one VPN at a time, so the address that reaches huginn depends on " +
            "which tunnel is up. Pick one, or let it find the live one.",
        highlighted = SettingsRowStyle.isHighlighted("host.route", highlight),
    )
    Row(
        Modifier.padding(start = 8.dp, top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (route in AppdRoutes.ALL) {
            FilterChip(
                selected = AppdRoutes.normalize(baseUrl) == AppdRoutes.normalize(route.url),
                onClick = { onSelectRoute(route.url) },
                label = { Text(route.label) },
            )
        }
    }
    Row(
        Modifier.padding(start = 8.dp, top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onResolveRoute, enabled = !resolvingRoute) {
            Text(if (resolvingRoute) "Finding…" else "Find live route")
        }
        if (routePinned) {
            TextButton(onClick = onUnpinRoute) { Text("Pinned — unpin") }
        } else {
            Text(
                "Auto",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    AppdRoutes.match(baseUrl)?.let {
        SettingsNote("${it.label} · ${it.hint}", Modifier.padding(start = 8.dp, top = 4.dp))
    }

    SettingsFieldRow(
        id = "host.base-url",
        title = "Base URL",
        value = url,
        onValueChange = { url = it },
        summary = "The address every request goes to.",
        highlighted = SettingsRowStyle.isHighlighted("host.base-url", highlight),
    )
    SettingsFieldRow(
        id = "host.token",
        title = "Token",
        value = tok,
        onValueChange = { tok = it },
        summary = "The bearer this app sends with every request.",
        secret = !reveal,
        highlighted = SettingsRowStyle.isHighlighted("host.token", highlight),
        trailing = {
            IconButton(onClick = { reveal = !reveal }) {
                Icon(
                    if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (reveal) "Hide token" else "Show token",
                )
            }
        },
    )
    Text(
        "On huginn: cat /etc/huginn-appd/token",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 2.dp),
    )
    // ONE save for both fields — the desktop's two separate saves is the split
    // this redesign is not copying. The outcome is the summary rather than a
    // fourth line below it: "did it work" is what the button is asking.
    SettingsActionRow(
        id = "host.connect",
        title = "Save and connect",
        summary = when (connected) {
            true -> "Connected."
            false -> "Not connected. Check the URL, the token, and that the phone is on the tailnet."
            null -> "Stores the address and the token together and reconnects with them."
        },
        actionLabel = "Save",
        onAction = { onSave(url, tok) },
        enabled = url.isNotBlank() && tok.isNotBlank(),
        highlighted = SettingsRowStyle.isHighlighted("host.connect", highlight),
    )

    SettingsGroup("Claude logins")
    // The whole accounts product in one call: the list, the freshness, Use,
    // Refresh, Forget, the three-step add-login and every refusal the daemon
    // answers with. Nothing of it is duplicated here.
    Column(Modifier.padding(start = 8.dp)) {
        AccountsEditor(
            io = accountsIo,
            openLink = openLink,
            showAutoswitch = false,
        )
    }

    // Sign out is HERE, beside the account it signs out of, and not in Privacy:
    // same verb, one control. It is the host-wide one, so it asks — and the
    // question says what actually stops, which is not only this app.
    if (signedIn) {
        SettingsActionRow(
            id = "host.sign-out",
            title = "Sign out",
            summary = "Signs huginn out of the Claude login that is serving now.",
            actionLabel = "Sign out",
            onAction = { confirmSignOut = true },
            destructive = true,
            highlighted = SettingsRowStyle.isHighlighted("host.sign-out", highlight),
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of Claude on huginn?") },
            text = {
                Text(
                    "This signs out the whole host, not just this app. Every running session " +
                        "stops working, and so do the scheduled jobs (briefings, escalation, " +
                        "status-page investigation) until someone signs back in.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}
