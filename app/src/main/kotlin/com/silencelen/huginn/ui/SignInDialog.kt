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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.LoginState

/**
 * Adding a Claude account, entirely inside the app.
 *
 * Two things this has to get right, both learned the hard way on this host:
 *
 *  * **The browser decides which account is authorized.** An authorize page uses
 *    whatever claude.ai session the browser already carries, so "add a second
 *    account" can silently re-authorize the first one — which is exactly what
 *    happened, leaving two saved profiles that were one account. Asking which
 *    account is being added lets the page be aimed at it, and lets the result be
 *    checked rather than assumed.
 *  * **Report what was actually captured.** The new token is asked who it belongs
 *    to, so a duplicate or a wrong account is stated plainly instead of appearing
 *    later as a second row that looks like a second account.
 */
@Composable
fun SignInDialog(
    state: LoginState,
    busy: Boolean,
    onStart: (String?) -> Unit,
    onOpenUrl: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var email by remember { mutableStateOf(state.intendedEmail.orEmpty()) }
    var code by remember { mutableStateOf("") }
    val finished = state.done

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                when {
                    finished && state.duplicate -> "That is the same account"
                    finished && state.mismatch -> "Signed in as somebody else"
                    finished -> "Account added"
                    state.awaitingCode -> "Paste the code"
                    else -> "Add a Claude account"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    finished -> Finished(state)

                    state.awaitingCode -> {
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
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                        )
                        if (busy) Working("Signing in…") else state.message?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    else -> {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Which account? (email)") },
                            singleLine = true,
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                keyboardType = KeyboardType.Email,
                            ),
                        )
                        Text(
                            "The sign-in page uses whichever account your browser is already " +
                                "signed into. To add a different one, sign out of claude.ai in " +
                                "that browser first, or open the link in a private window.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (busy) Working("Starting…")
                    }
                }
            }
        },
        confirmButton = {
            when {
                finished -> TextButton(onClick = onDismiss) { Text("Done") }
                state.awaitingCode -> TextButton(
                    onClick = { onSubmit(code) },
                    enabled = !busy && code.trim().length >= 8,
                ) { Text("Sign in") }
                else -> TextButton(
                    onClick = { onStart(email.trim().ifBlank { null }) },
                    enabled = !busy,
                ) { Text("Continue") }
            }
        },
        dismissButton = {
            if (!finished) TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
            else if (state.duplicate || state.mismatch) {
                TextButton(onClick = { onStart(email.trim().ifBlank { null }) }) { Text("Try again") }
            }
        },
    )
}

@Composable
private fun Finished(state: LoginState) {
    val captured = state.email ?: "an unknown account"
    when {
        state.duplicate -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Signed in as $captured, which is already saved.", fontWeight = FontWeight.Medium)
            Text(
                "So this is one account signed in twice: both copies share a single usage " +
                    "limit and switching between them gains nothing. The browser almost " +
                    "certainly reused a session it already had.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Sign out of claude.ai in your browser, or open the sign-in link in a " +
                    "private window, then try again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.mismatch -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Signed in as $captured.", fontWeight = FontWeight.Medium)
            Text(
                "You were adding ${state.intendedEmail}. The browser authorized the account " +
                    "it was already signed into. It is saved and usable, but it is not the " +
                    "one you asked for.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> Text("Signed in as $captured.", fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Working(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(
            "  $label",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
