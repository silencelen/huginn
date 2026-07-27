package com.silencelen.huginn.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
    onSave: (String, String) -> Unit,
    onTest: () -> Unit,
) {
    var url by remember(baseUrl) { mutableStateOf(baseUrl) }
    var tok by remember(token) { mutableStateOf(token) }
    var reveal by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Server", style = MaterialTheme.typography.titleMedium)
        Text(
            "huginn-appd binds huginn's tailnet address, so the phone must be on the tailnet. " +
                "The MagicDNS name works too.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onSave(url, tok) }, enabled = url.isNotBlank() && tok.isNotBlank()) {
                Text("Save and connect")
            }
            OutlinedButton(onClick = onTest) { Text("Test") }
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

        Spacer(Modifier.height(8.dp))
        Text("Notifications", style = MaterialTheme.typography.titleMedium)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Tell me when a session needs me", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Checks every 15 minutes while the phone is on the tailnet, and notifies " +
                        "when a session starts waiting for an answer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = notifyEnabled, onCheckedChange = onNotifyEnabled)
        }

        Spacer(Modifier.height(8.dp))
        Text("What this app can do", style = MaterialTheme.typography.titleMedium)
        Text(
            "Chats run on huginn as headless Claude Code turns in ~/netplan. Ask mode has memory " +
                "and no tools; Act mode can read, write, run commands and fetch the web. Sessions are " +
                "the real tmux sessions, so a session you open here is the same one your laptop attaches " +
                "to; its conversation is read from the session's own Claude Code transcript, and the " +
                "Screen tab is the live pane for answering prompts and typing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}
