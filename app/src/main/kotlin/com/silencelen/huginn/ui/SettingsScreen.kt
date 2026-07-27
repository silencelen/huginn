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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip
import com.silencelen.huginn.data.Account
import com.silencelen.huginn.data.Plan
import com.silencelen.huginn.data.PlanLimit
import java.time.Duration
import java.time.OffsetDateTime
import com.silencelen.huginn.data.Usage
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
    account: Account?,
    plan: Plan?,
    usage: Usage?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSave: (String, String) -> Unit,
    onTest: () -> Unit,
) {
    var confirmSignOut by remember { mutableStateOf(false) }
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
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onSignIn) {
                Text(if (account?.loggedIn == true) "Switch account" else "Sign in")
            }
            if (account?.loggedIn == true) {
                OutlinedButton(onClick = { confirmSignOut = true }) { Text("Sign out") }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("Plan usage", style = MaterialTheme.typography.titleMedium)
        PlanSection(plan)

        Spacer(Modifier.height(8.dp))
        Text("Tokens", style = MaterialTheme.typography.titleMedium)
        UsageSection(usage)

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
 * Plan utilization — the same rows Claude Code's `/usage` prints. These are the
 * limits that actually stop work, so they lead the section; token counts below
 * are volume, which is a different question.
 */
@Composable
private fun PlanSection(plan: Plan?) {
    when {
        plan == null -> Text(
            "Loading…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        plan.error != null && plan.limits.isEmpty() -> Text(
            plan.error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        plan.limits.isEmpty() -> Text(
            "No limits reported for this account.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            plan.limits.forEach { LimitBar(it) }
            plan.extraUsage?.let { eu ->
                Text(
                    "Extra usage: ${eu.utilization?.toInt() ?: 0}% of " +
                        "${eu.monthlyLimit?.toInt() ?: 0} ${eu.currency}" +
                        if (eu.spendLimitReached) " (limit reached)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (eu.spendLimitReached) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LimitBar(l: PlanLimit) {
    val pct = l.percent.coerceIn(0.0, 100.0)
    // Colour by headroom, not decoration: this is the number that stops work.
    val bar = when {
        l.severity == "critical" || pct >= 90 -> MaterialTheme.colorScheme.error
        l.severity == "warning" || pct >= 70 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                l.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (l.isActive) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${pct.toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = bar,
            )
        }
        LinearProgressIndicator(
            progress = { (pct / 100.0).toFloat() },
            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
            color = bar,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
        )
        resetLabel(l.resetsAt)?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "resets in 3h 12m" — a countdown is what you act on, not an ISO timestamp. */
private fun resetLabel(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    val at = runCatching { OffsetDateTime.parse(iso) }.getOrNull() ?: return null
    val secs = Duration.between(OffsetDateTime.now(), at).seconds
    if (secs <= 0) return "resetting now"
    val d = secs / 86_400
    val h = (secs % 86_400) / 3600
    val m = (secs % 3600) / 60
    return when {
        d > 0 -> "resets in ${d}d ${h}h"
        h > 0 -> "resets in ${h}h ${m}m"
        else -> "resets in ${m}m"
    }
}

/** Tokens are exact; the dollar figure is a list-price estimate, so it is labelled. */
@Composable
private fun UsageSection(usage: Usage?) {
    val d = usage?.data
    when {
        usage == null -> Text(
            "Loading…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        usage.error != null -> Text(
            usage.error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        d == null -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "Counting tokens across every transcript, this takes about half a minute.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            d.today?.let { UsageRow("Today", it.totalTokens, it.costUsd) }
            UsageRow("Last ${d.week.days} days", d.week.totalTokens, d.week.costUsd)
            if (d.week.totalTokens > 0) {
                Text(
                    "Cache reads are ${pct(d.week.cacheReadTokens, d.week.totalTokens)} of the total.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Token counts are exact. The dollar figures are list-price estimates and " +
                    "run high on a Max plan, so treat them as a trend, not a bill.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (usage.refreshing) {
                Text(
                    "Refreshing…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun UsageRow(label: String, tokens: Long, cost: Double?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(compactTokens(tokens), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (cost != null) {
                Text(
                    "approx $" + String.format("%.0f", cost),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun compactTokens(n: Long): String = when {
    n >= 1_000_000_000 -> String.format("%.2fB tokens", n / 1_000_000_000.0)
    n >= 1_000_000 -> String.format("%.1fM tokens", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1fk tokens", n / 1_000.0)
    else -> "$n tokens"
}

private fun pct(part: Long, whole: Long): String =
    if (whole <= 0) "0%" else String.format("%.0f%%", part * 100.0 / whole)
