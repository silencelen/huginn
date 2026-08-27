package com.silencelen.huginn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.silencelen.huginn.data.Account
import com.silencelen.huginn.data.AppdRoutes
import com.silencelen.huginn.update.AppUpdateState
import com.silencelen.huginn.data.SavedAccount
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    baseUrl: String,
    token: String,
    connected: Boolean?,
    notifyEnabled: Boolean,
    onNotifyEnabled: (Boolean) -> Unit,
    watchEnabled: Boolean,
    onWatchEnabled: (Boolean) -> Unit,
    alerts: com.silencelen.huginn.data.Alerts?,
    onAlertsEnabled: (Boolean) -> Unit,
    onAlertsMode: (String) -> Unit,
    health: HuginnViewModel.DeliveryHealth,
    clients: com.silencelen.huginn.data.ClientsInfo?,
    push: com.silencelen.huginn.data.PushStatus?,
    onRequestDozeExemption: () -> Unit,
    onRefreshDelivery: () -> Unit,
    deviceCount: Int,
    servingCount: Int = 0,
    onOpenDevices: () -> Unit,
    appLock: Boolean,
    appLockAvailable: Boolean,
    onAppLock: (Boolean) -> Unit,
    onLockNow: () -> Unit,
    autoswitch: com.silencelen.huginn.data.Autoswitch?,
    onAutoswitch: (Boolean) -> Unit,
    notificationsAllowed: Boolean,
    onRequestNotifications: () -> Unit,
    onOpenSystemNotificationSettings: () -> Unit,
    account: Account?,
    savedAccounts: List<SavedAccount>,
    switching: Boolean,
    onSwitchAccount: (String) -> Unit,
    onForgetAccount: (String) -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSave: (String, String) -> Unit,
    routePinned: Boolean,
    resolvingRoute: Boolean,
    onSelectRoute: (String) -> Unit,
    onResolveRoute: () -> Unit,
    onUnpinRoute: () -> Unit,
    updateState: com.silencelen.huginn.update.AppUpdateState,
    installedVersion: String,
    updateRepo: String,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
) {
    var confirmSignOut by remember { mutableStateOf(false) }
    var forgetTarget by remember { mutableStateOf<SavedAccount?>(null) }
    var url by remember(baseUrl) { mutableStateOf(baseUrl) }
    var tok by remember(token) { mutableStateOf(token) }
    var reveal by remember { mutableStateOf(false) }
    var deliveryOpen by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Server", style = MaterialTheme.typography.titleMedium)
        Text(
            "Android runs one VPN at a time, so the route that reaches huginn depends on " +
                "which tunnel is up. Pick one, or let it find the live one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (route in AppdRoutes.ALL) {
                FilterChip(
                    selected = AppdRoutes.normalize(baseUrl) == AppdRoutes.normalize(route.url),
                    onClick = { onSelectRoute(route.url) },
                    label = { Text(route.label) },
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
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
            Text(
                "${it.label} · ${it.hint}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Base URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Next,
            ),
        )

        OutlinedTextField(
            value = tok,
            onValueChange = { tok = it },
            label = { Text("Token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Done,
            ),
            trailingIcon = {
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
        )

        Button(onClick = { onSave(url, tok) }, enabled = url.isNotBlank() && tok.isNotBlank()) {
            Text("Save and connect")
        }

        when (connected) {
            true -> Text(
                "Connected.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            false -> Text(
                "Not connected. Check the URL, the token, and that the phone is on the tailnet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            null -> Unit
        }

        // Host-sent alerts are listed before the in-app ones because they are the
        // ones that work when the app is closed, which is most of the time.
        alerts?.let { al ->
            Spacer(Modifier.height(8.dp))
            Text("Alerts from huginn", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Message me when a session needs me", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (al.channel == "telegram")
                            "Sent by huginn over Telegram, so it reaches you with this app closed " +
                                "and costs no battery. Also tells you when a long chat finishes."
                        else "No delivery channel is configured on huginn.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (al.delivered > 0) {
                        Text(
                            "${al.delivered} sent so far",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = al.enabled,
                    onCheckedChange = onAlertsEnabled,
                    enabled = al.channel != "none",
                )
            }
            if (al.enabled) {
                // Fallback is the default and the recommendation. Both channels
                // firing for one event is worse than either alone: you learn to
                // dismiss without reading, and then the one that mattered is gone.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Only when the app is out of contact", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (al.mode == "always")
                                "Off, so every alert arrives twice — once in the app and once on Telegram."
                            else if (al.appOnline)
                                "On. huginn can see this phone checking in, so it is staying quiet " +
                                    "and letting the app notify you."
                            else
                                "On. huginn has not heard from this phone recently, so Telegram is " +
                                    "carrying alerts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = al.mode != "always",
                        onCheckedChange = { onAlertsMode(if (it) "fallback" else "always") },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("Devices", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when (deviceCount) {
                        0 -> "No machines enrolled"
                        1 -> "1 machine can run work in its own context"
                        else -> "$deviceCount machines can run work in their own context"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                // The serving capability said separately, because a serve-only
                // machine cannot "run work" and this row must not claim it does.
                if (servingCount > 0) {
                    Text(
                        if (servingCount == 1) "1 machine serves local AI models"
                        else "$servingCount machines serve local AI models",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Your PC, a server, a build box. A machine offers ITSELF and decides what " +
                        "it will allow — this phone can see them, start work on one, and " +
                        "withdraw an enrolment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onOpenDevices) { Text("Manage") }
        }

        Spacer(Modifier.height(8.dp))
        Text("Security", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Lock the app", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (!appLockAvailable)
                        "Needs a screen lock on this phone first — there is nothing to unlock with."
                    else
                        "Ask for fingerprint, face or the device PIN when opening the app after " +
                            "it has been away for a minute. This app is a hand on huginn; an " +
                            "unlocked phone passed to someone should not include it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = appLock,
                onCheckedChange = onAppLock,
                enabled = appLockAvailable,
            )
        }
        // Proof on demand. The lock's normal trigger is time away, which makes
        // "is it even on?" unanswerable by looking — the first version's silent
        // failure sat unnoticed behind exactly that.
        if (appLock && appLockAvailable) {
            OutlinedButton(onClick = onLockNow) { Text("Lock now") }
        }

        Spacer(Modifier.height(8.dp))
        Text("Claude account", style = MaterialTheme.typography.titleMedium)
        when {
            account == null -> Text(
                "Loading…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            account.loggedIn -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(account.email ?: "signed in", style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOfNotNull(
                        account.subscriptionType?.let { "$it plan" },
                        account.authMethod,
                        account.orgName?.takeIf { it != account.email },
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> Text(
                account.error ?: "Not signed in. huginn cannot run until it is.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            "Signing in is an interactive flow, so it opens a session called \"login\" " +
                "where you can read the URL and paste the code back.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Other logins saved on this host. The active one is snapshotted
        // automatically, so an account you have used is always here to come back
        // to when a plan runs out.
        if (savedAccounts.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Accounts on this host",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The rotation the accounts exist FOR, automated: when the active
            // login's binding limit crosses ~95%, huginn switches to the freshest
            // saved one and tells you it did. Decided and executed on the host, so
            // it works with the phone in a drawer.
            if (savedAccounts.size > 1 && autoswitch != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Switch automatically when one runs out", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            buildString {
                                append("Rotates to the account with the most headroom and notifies you. ")
                                append("Running sessions keep their account until they restart.")
                                autoswitch.last?.let { l ->
                                    append("\nLast: ${l.fromEmail ?: "?"} ${l.fromPercent}% → ")
                                    append("${l.toEmail ?: "?"} ${l.toPercent}%  ·  ${relTime(l.at)}")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoswitch.enabled, onCheckedChange = onAutoswitch)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                savedAccounts.forEach { a ->
                    SavedAccountRow(
                        account = a,
                        enabled = !switching,
                        onSwitch = { onSwitchAccount(a.slug) },
                        onForget = { forgetTarget = a },
                    )
                }
            }
            if (savedAccounts.any { it.duplicateOf }) {
                Text(
                    "Two of these are the same Claude account signed in twice, so they " +
                        "share one usage limit and switching between them gains nothing. " +
                        "Forget one, or add a different account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "Switching changes the login for the whole host. Sessions already " +
                    "running keep the old account until they restart; new runs use the new one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onSignIn, enabled = !switching) {
                Text(if (account?.loggedIn == true) "Add account" else "Sign in")
            }
            if (account?.loggedIn == true) {
                OutlinedButton(onClick = { confirmSignOut = true }, enabled = !switching) { Text("Sign out") }
            }
        }

        Text(
            "Plan usage and token counts live on the Status tab, with the rest of " +
                "huginn's live state.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        Text("Notifications", style = MaterialTheme.typography.titleMedium)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Tell me when a session needs me", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Checks huginn about every 10 minutes, including while the phone is " +
                        "asleep, and notifies when a session starts waiting for an answer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = notifyEnabled, onCheckedChange = onNotifyEnabled)
        }

        if (notifyEnabled) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Watch continuously", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (watchEnabled)
                            "Alerts arrive within seconds while the phone is awake. Android " +
                                "requires a quiet ongoing notification, and it shows what huginn is doing."
                        else
                            "Off, so alerts wait for the 10-minute check rather than arriving " +
                                "at once. Nothing is missed either way.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = watchEnabled, onCheckedChange = onWatchEnabled)
            }

            // The part that decides whether any of the above works while the phone
            // sleeps, and the part that used to be invisible. Without the allowlist
            // entry Android suspends this app's network during Doze, so the check
            // still fires and reaches nothing — a failure indistinguishable from no
            // check at all, which is why it is stated rather than assumed.
            Spacer(Modifier.height(4.dp))
            Text("Background delivery", style = MaterialTheme.typography.titleMedium)
            if (!health.dozeExempt) {
                Text(
                    "Android is allowed to put this app to sleep. When it does, the checks " +
                        "still run but cannot reach huginn — this is the usual reason " +
                        "notifications stop arriving overnight.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onRequestDozeExemption) { Text("Allow background use") }
            } else {
                Text(
                    "Exempt from battery optimisation, so checks keep working while the " +
                        "phone is asleep.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Push status before the rest, because when it is working the other numbers
            // stop mattering much: FCM reaches a sleeping phone in seconds, where the
            // alarm below takes up to ten minutes.
            push?.let { ps ->
                val registered = ps.devices.isNotEmpty()
                Text(
                    when {
                        ps.configured && registered ->
                            "Push is on. huginn sends straight to this phone through Google, " +
                                "which arrives in seconds even while it is asleep."
                        ps.configured && !registered ->
                            "huginn can push, but this phone has not registered yet. It " +
                                "registers itself on start — check the token above is right."
                        else ->
                            "Push is not set up on huginn, so alerts arrive on the 10-minute " +
                                "check or by Telegram instead."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ps.configured && registered) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // The counts behind that sentence, folded away. They answer "why did
            // nothing arrive last night", which is a question asked rarely and
            // never by accident — and the sentences above already say whether
            // there is anything to ask about.
            Row(
                Modifier.fillMaxWidth().clickable { deliveryOpen = !deliveryOpen },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Delivery details",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (deliveryOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (deliveryOpen) "Collapse" else "Expand",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(deliveryOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    push?.let { ps ->
                        if (ps.pushed > 0) {
                            Text(
                                "${ps.pushed} delivered so far",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // What the background alarm has decided to do, and why. Worth a line
                        // of its own: the cadence swings by a factor of six and used to make
                        // that choice silently, so the one bug it has had could only be found
                        // by reading a night of server logs after the fact.
                        Text(
                            when {
                                health.pushesReceived == 0L ->
                                    "No push has arrived here yet, so the backup check runs every " +
                                        "10 minutes until one proves it can."
                                health.pushesMissing > 0L ->
                                    "${health.pushesMissing} push(es) huginn sent never arrived, so the " +
                                        "backup check has tightened to every 10 minutes."
                                else ->
                                    "${health.pushesReceived} of ${health.pushesSent} pushes arrived — " +
                                        "nothing dropped, so the backup check only runs hourly."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (health.relaxed) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                        )
                    }

                    // Two witnesses. The app's own record can only be written while the app
                    // is alive, so it cannot testify about the hours that matter; huginn's
                    // was taken by a machine that never slept.
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "This app last reached huginn ${ago(health.lastContactAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Background check last ran ${ago(health.lastAlarmAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        clients?.clients?.firstOrNull()?.let { c ->
                            Text(
                                "huginn last heard from this phone ${agoSeconds(c.ageSeconds)}" +
                                    (c.kind?.let { k -> " (${describeKind(k)})" } ?: "") +
                                    "  ·  ${c.checkIns} check-ins",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (health.lastError.isNotBlank()) {
                            Text(
                                "Last failure ${ago(health.lastErrorAt)}: ${health.lastError}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    OutlinedButton(onClick = onRefreshDelivery) { Text("Refresh") }
                }
            }
        }

        // Whether the system will actually deliver them is a separate question
        // from whether this app wants to send them, and it is the one that
        // silently makes notifications never arrive.
        if (notifyEnabled) {
            if (!notificationsAllowed) {
                Text(
                    "Android is blocking notifications for this app, so none will arrive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onRequestNotifications) { Text("Allow") }
                    OutlinedButton(onClick = onOpenSystemNotificationSettings) { Text("System settings") }
                }
            } else {
                Text(
                    "Allowed by Android.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        UpdateSection(
            state = updateState,
            installedVersion = installedVersion,
            repo = updateRepo,
            onCheck = onCheckUpdate,
            onDownload = onDownloadUpdate,
            onInstall = onInstallUpdate,
        )

        Spacer(Modifier.height(8.dp))
        Text("What this app can do", style = MaterialTheme.typography.titleMedium)
        Text(
            "Chats run on the host as headless Claude Code turns in its working directory. Ask mode has memory " +
                "and no tools; Act mode can read, write, run commands and fetch the web. Sessions are " +
                "the real tmux sessions, so a session you open here is the same one your laptop attaches " +
                "to; its conversation is read from the session's own Claude Code transcript, and the " +
                "Screen tab is the live pane for answering prompts and typing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }

    forgetTarget?.let { a ->
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            title = { Text("Forget ${a.email ?: a.slug}?") },
            text = {
                Text(
                    "Removes the saved credentials for this account from huginn. It does " +
                        "not sign the account out anywhere; you would just have to sign in " +
                        "again to use it here.",
                )
            },
            confirmButton = {
                TextButton(onClick = { val s = a.slug; forgetTarget = null; onForgetAccount(s) }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { forgetTarget = null }) { Text("Cancel") } },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of Claude on huginn?") },
            text = {
                Text(
                    "This signs out the whole host, not just this app. Every running " +
                        "session stops working, and so do the scheduled jobs (briefings, " +
                        "escalation, status-page investigation) until someone signs back in.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Self-update, from the public GitHub releases. Three explicit steps — check,
 * download, install — so a background check never spends the owner's data and an
 * APK never installs without two taps and the system's own confirmation.
 */
@Composable
private fun UpdateSection(
    state: AppUpdateState,
    installedVersion: String,
    repo: String,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    Text("Software update", style = MaterialTheme.typography.titleMedium)
    Text(
        "Installed ${installedVersion.ifBlank { "—" }}  ·  updates from github.com/$repo",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when (state) {
        is AppUpdateState.Idle ->
            OutlinedButton(onClick = onCheck) { Text("Check for updates") }

        is AppUpdateState.Checking ->
            Text("Checking…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

        is AppUpdateState.UpToDate -> {
            Text("Up to date.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
            OutlinedButton(onClick = onCheck) { Text("Check again") }
        }

        is AppUpdateState.Available -> {
            Text("Update available: ${state.versionName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary)
            if (state.notes.isNotBlank()) {
                Text(state.notes, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 8)
            }
            val mb = if (state.size > 0) "  (${state.size / 1_000_000} MB)" else ""
            Button(onClick = onDownload) { Text("Download update$mb") }
        }

        is AppUpdateState.Downloading -> {
            val pct = state.fraction?.let { " ${(it * 100).toInt()}%" } ?: ""
            Text("Downloading…$pct", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        is AppUpdateState.Ready -> {
            Text("Version ${state.versionName} downloaded and verified.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
            Button(onClick = onInstall) { Text("Install and restart") }
        }

        is AppUpdateState.Error -> {
            Text(state.message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onCheck) { Text("Try again") }
        }
    }
}

/**
 * One saved login. Leads with its headroom, because "which account still has
 * room" is the question you are answering when you look at this list.
 */
@Composable
private fun SavedAccountRow(
    account: SavedAccount,
    enabled: Boolean,
    onSwitch: () -> Unit,
    onForget: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    account.email ?: account.slug,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val bits = buildList {
                    account.subscriptionType?.let { add("$it plan") }
                    account.weeklyPercent?.let { add("${it.toInt()}% of the week used") }
                    if (account.isActive) add("active")
                    if (!account.verified) add("name unconfirmed")
                }
                if (bits.isNotEmpty()) {
                    Text(
                        bits.joinToString("  ·  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = if ((account.weeklyPercent ?: 0.0) >= 90) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        // A stored token expires; it refreshes once that account is active.
                        "headroom unknown until this account is active",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (account.isActive) {
                Text(
                    "in use",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
            } else {
                TextButton(onClick = onSwitch, enabled = enabled) { Text("Use") }
            }
            IconButton(onClick = onForget, enabled = enabled && !account.isActive) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Forget this account",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * "4 minutes ago", or "never" for a zero — a timestamp of 0 means the thing has not
 * happened yet, and rendering that as 1970 would read as a fault rather than a
 * blank. Deliberately coarse: the question these answer is "recently or not", and a
 * figure to the second invites a precision the underlying schedule does not have.
 */
private fun ago(atMs: Long): String =
    if (atMs <= 0) "never" else agoSeconds((System.currentTimeMillis() - atMs) / 1000)

private fun agoSeconds(seconds: Long): String = when {
    seconds < 0 -> "just now"
    seconds < 60 -> "just now"
    seconds < 3600 -> "${seconds / 60} min ago"
    seconds < 86_400 -> "${seconds / 3600} h ago"
    else -> "${seconds / 86_400} d ago"
}

/** The mechanism behind a check-in, in words rather than the wire's vocabulary. */
private fun describeKind(kind: String): String = when (kind) {
    "stream" -> "live connection"
    "heartbeat" -> "background check"
    "poll" -> "poll"
    else -> kind
}
