package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.LoginState

/**
 * Signing in, entirely inside the app.
 *
 * The flow is a terminal program that prints a URL and waits for a pasted code,
 * and the first version of this sent the user into that terminal to type it.
 * Sending somebody to a tmux pane to paste a code is not a flow, so the URL goes
 * to the browser and the code comes back here.
 */
@Composable
fun SignInDialog(
    state: LoginState,
    busy: Boolean,
    onOpenUrl: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Add a Claude account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Sign in in your browser, then paste the code it gives you.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.url?.let { url ->
                    OutlinedButton(onClick = { onOpenUrl(url) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Open the sign-in page again")
                    }
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Code") },
                    singleLine = true,
                    enabled = !busy,
                    // Codes are case-sensitive and not words.
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                )
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            "  Signing in…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else state.message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        // A message that arrives while still awaiting the code is
                        // progress; one that arrives afterwards is why it failed.
                        color = if (state.awaitingCode) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(code) }, enabled = !busy && code.trim().length >= 8) {
                Text("Sign in")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
