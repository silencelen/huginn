package com.silencelen.huginn.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.HuginnSettings
import com.silencelen.huginn.data.RouteBook
import com.silencelen.huginn.data.RouteHealth

/**
 * Which huginn this phone talks to, and which Claude login serves it.
 *
 * The connection half is now the SHARED route list: the phone's named chips —
 * two of them, spelling "Tailscale" and "Yggdrasil" into the interface — and its
 * Base URL field are gone, replaced by pins the owner names and orders. Android
 * still runs one VPN at a time and the route is still the setting that most
 * often explains a dead app, which is exactly why the list has to be able to
 * hold a third address. The accounts half is likewise the SHARED editor: the phone's
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
    routeBook: RouteBook,
    routeHealth: Map<String, RouteHealth>,
    routeNote: String?,
    nowMs: Long,
    token: String,
    connected: Boolean?,
    resolvingRoute: Boolean,
    routeActions: RouteListActions,
    onSave: (String) -> Unit,
    accountsIo: AccountsIo,
    openLink: (String) -> Boolean,
    signedIn: Boolean,
    onSignOut: () -> Unit,
    highlight: String?,
) {
    var tok by remember(token) { mutableStateOf(token) }
    var reveal by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    SettingsGroup("Connection")

    SettingsRouteListRow(
        book = routeBook,
        actions = routeActions,
        health = routeHealth,
        nowMs = nowMs,
        summary = "Android runs one VPN at a time, so which address reaches huginn depends on " +
            "which tunnel is up. These are tried in order.",
        highlighted = SettingsRowStyle.isHighlighted("host.route", highlight),
        finding = resolvingRoute,
        note = routeNote,
        // A placeholder, never a pre-filled value (D24): the old default was a real
        // tailnet address compiled into a public repo.
        suggestedUrl = HuginnSettings.ROUTE_URL_PLACEHOLDER,
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
    // The outcome is the summary rather than a fourth line below it: "did it
    // work" is what the button is asking. The address is NOT here — editing a
    // pin's URL in the list above is how an address is typed now.
    SettingsActionRow(
        id = "host.connect",
        title = "Save and connect",
        summary = when (connected) {
            true -> "Connected on ${routeBook.activeName.ifBlank { "this route" }}."
            false -> "Not connected. Check the token, and that a route on this list is reachable."
            null -> "Stores the token and reconnects on the route in use."
        },
        actionLabel = "Save",
        onAction = { onSave(tok) },
        enabled = tok.isNotBlank(),
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
