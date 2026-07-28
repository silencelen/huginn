package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.silencelen.huginn.appVersion
import com.silencelen.huginn.data.Plan
import com.silencelen.huginn.data.PlanLimit
import com.silencelen.huginn.data.Status
import com.silencelen.huginn.data.Usage
import java.time.Duration
import java.time.OffsetDateTime

/** The same health summary `huginn status` prints, minus what a phone cannot use. */
@Composable
fun StatusScreen(
    status: Status?,
    error: String?,
    sessions: Int,
    chatsRunning: Int,
    plan: Plan?,
    usage: Usage?,
) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        if (error != null) {
            Surface(
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                modifier = Modifier.padding(16.dp),
            ) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        if (status == null) {
            if (error == null) EmptyState("No status yet", "Pull to refresh once the server URL and token are set.")
            // Plan and token figures come from their own endpoints, so show them
            // even when the host summary has not arrived.
            SectionLabel("Plan")
            PlanSection(plan)
            SectionLabel("Tokens")
            UsageSection(usage)
            Spacer(Modifier.height(24.dp))
            return@Column
        }

        SectionLabel("Host")
        KeyValueRow("Name", status.host ?: "unknown")
        KeyValueRow("Uptime", formatUptime(status.uptimeSec))
        KeyValueRow(
            "Load",
            status.load.joinToString(" ") { it.toString() } + "  on ${status.cores} cores",
            valueColor = if (status.load.firstOrNull()?.let { it > status.cores } == true)
                MaterialTheme.colorScheme.error else null,
        )
        status.disk?.let { d ->
            KeyValueRow(
                "Disk /",
                "${d.usedPercent ?: "?"} used, ${d.free ?: "?"} free",
                valueColor = diskColor(d.usedPercent),
            )
        }

        HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

        SectionLabel("Agent")
        KeyValueRow("Claude Code", status.claude ?: "unknown")
        // Adjacent and labelled apart, because they version independently: app
        // 2.36.0 beside appd 2.33.0 is an ordinary state, and a lone "appd" row
        // reads as the app's own version to anyone who has not internalised that
        // the phone and the host daemon are separate release lines.
        KeyValueRow("This app", remember(ctx) { appVersion(ctx) })
        KeyValueRow("appd (host)", status.appdVersion ?: "unknown")
        KeyValueRow(
            "MemPalace",
            when (status.mempalace) {
                "ok" -> "connected"
                "rebuilding" -> "index rebuilding"
                "daemon-down" -> "write daemon down"
                "unreachable" -> "not reachable"
                else -> status.mempalace ?: "unknown"
            },
            valueColor = when (status.mempalace) {
                "ok" -> null
                "rebuilding" -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.error
            },
        )

        HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

        SectionLabel("Plan")
        PlanSection(plan)

        HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

        SectionLabel("Tokens")
        UsageSection(usage)

        HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

        SectionLabel("Work in flight")
        KeyValueRow("tmux sessions", sessions.toString())
        KeyValueRow("Chats running", chatsRunning.toString())

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun diskColor(usedPercent: String?): androidx.compose.ui.graphics.Color? {
    val pct = usedPercent?.removeSuffix("%")?.toIntOrNull() ?: return null
    return when {
        pct >= 90 -> MaterialTheme.colorScheme.error
        pct >= 80 -> MaterialTheme.colorScheme.primary
        else -> null
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
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        plan.error != null && plan.limits.isEmpty() -> Text(
            plan.error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        plan.limits.isEmpty() -> Text(
            "No limits reported for this account.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        else -> Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        usage.error != null -> Text(
            usage.error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        d == null -> Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "Counting tokens across every transcript, this takes about half a minute.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
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
