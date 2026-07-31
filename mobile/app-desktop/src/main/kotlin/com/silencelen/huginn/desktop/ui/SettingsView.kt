package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.AppdRoutes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import com.silencelen.huginn.desktop.DesktopSettings
import com.silencelen.huginn.desktop.update.DesktopUpdater
import com.silencelen.huginn.desktop.update.UpdateState
import kotlinx.coroutines.launch

/**
 * Connection, notifications, and where the file lives.
 *
 * The address field REFUSES anything off the allowlist rather than saving it and
 * failing later: the bearer token follows the base URL on every request, so an
 * arbitrary address is a one-field path to handing a root-equivalent daemon token
 * to a stranger. The refusal is shown, not swallowed — a setting that silently
 * does not take is worse than one that says no.
 */
@Composable
fun SettingsView(
    settings: DesktopSettings,
    route: String,
    present: Boolean,
    notifyEnabled: Boolean,
    updater: DesktopUpdater,
) {
    val scope = rememberCoroutineScope()
    var url by remember(route) { mutableStateOf(route) }
    var token by remember { mutableStateOf(settings.tokenNow()) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleMedium)

        Text(
            "Server",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.widthIn(min = 320.dp, max = 460.dp),
            )
            Button(onClick = {
                scope.launch {
                    message = runCatching { settings.selectRoute(url, pinned = true) }
                        .fold({ "saved — route pinned" }, { it.message })
                }
            }) { Text("Save") }
        }
        Muted(
            "known routes: " + AppdRoutes.ALL.joinToString("  ") { "${it.label} ${it.url}" },
            Modifier.padding(top = 4.dp),
        )

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.padding(top = 12.dp).widthIn(min = 320.dp, max = 460.dp),
        )
        Button(
            onClick = { scope.launch { settings.setToken(token); message = "token saved" } },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("Save token") }

        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Text(
            "Notifications",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 24.dp, bottom = 6.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = notifyEnabled,
                onCheckedChange = { scope.launch { settings.setNotifyEnabled(it) } },
            )
            Text("Claim the notification route", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
        }
        // The reader has to be able to see WHY the claim is off, because "off"
        // is also what a bug looks like.
        Muted(
            if (!notifyEnabled) "off — huginn falls back to Telegram"
            else if (present) "claiming: this window has been attended recently"
            else "not claiming: window hidden or unattended, so Telegram stays live",
            Modifier.padding(top = 6.dp, start = 4.dp),
        )

        Text(
            "This install",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 24.dp, bottom = 6.dp),
        )
        Muted(settings.path)
        Muted("client id ${settings.clientIdNow()}", Modifier.padding(top = 2.dp))
        Muted(
            if (DesktopSettings.isPackaged()) "packaged build" else "unpackaged — dev token bootstrap allowed",
            Modifier.padding(top = 2.dp),
        )
    }
}
