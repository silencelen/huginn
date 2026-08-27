package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Plan
import com.silencelen.huginn.data.PlanLimit
import com.silencelen.huginn.data.Usage

/**
 * Plan headroom, extra usage and token volume — one rendering, both clients.
 *
 * These were two hand-kept lookalikes until they disagreed about something that
 * mattered: the phone counted DOWN to a reset while the desktop printed the
 * timestamp's wall clock with the timezone offset sliced off, so the same daemon
 * reading produced "resets in 3h 12m" on one screen and an hour that had already
 * passed on the other. And extra usage was on neither — the phone rendered a
 * single line the daemon had already decided to withhold, the desktop nothing at
 * all, while a hundred dollars of it sat unshown.
 *
 * Every judgment here lives in [PlanFormat] in `:core`, under test; this file is
 * only how it looks. House rules: no left accent bars, the meter fill IS the
 * mark, and the state line is a quiet word rather than a badge.
 *
 * @param nowMs the shell's clock, ticked by the shell. Passed in rather than read
 *   here so the countdowns stay live without this file knowing what a lifecycle
 *   is, and so a test can hold time still.
 */
@Composable
fun PlanSection(plan: Plan?, nowMs: Long, modifier: Modifier = Modifier) {
    // Hoisted rather than dereferenced twice: these are public properties of
    // another module, which the compiler will not smart-cast inside a null check.
    val error = plan?.error
    val limits = plan?.limits.orEmpty()
    val extra = PlanFormat.extraUsageCard(plan)
    when {
        plan == null -> Hint("Loading…", modifier)
        error != null && limits.isEmpty() -> Warn(error, modifier)
        limits.isEmpty() && extra == null -> Hint("No limits reported for this account.", modifier)
        else -> Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            limits.forEach { LimitBar(it, nowMs) }
            if (extra != null) ExtraUsageCard(extra)
        }
    }
}

/**
 * Token volume. Exact counts, hedged dollars.
 *
 * The disclaimer is not boilerplate and does not come out: ccusage prices at
 * list rates, a Max plan pays nothing like them, and the figure is high enough to
 * be quoted by someone who did not know that.
 */
@Composable
fun UsageSection(usage: Usage?, modifier: Modifier = Modifier) {
    val data = usage?.data
    val error = usage?.error
    when {
        usage == null -> Hint("Loading…", modifier)
        error != null -> Warn(error, modifier)
        data == null -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "Counting tokens across every transcript, this takes about half a minute.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val today = data.today
            val week = data.week
            if (today != null) UsageRow("Today", today.totalTokens, today.costUsd)
            UsageRow("Last ${week.days} days", week.totalTokens, week.costUsd)
            if (week.totalTokens > 0) {
                Text(
                    "Cache reads are ${PlanFormat.sharePercent(week.cacheReadTokens, week.totalTokens)} of the total.",
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
private fun LimitBar(limit: PlanLimit, nowMs: Long) {
    val pct = limit.percent.coerceIn(0.0, 100.0)
    val bar = meterColor(limit.severity, pct)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                limit.label,
                style = MaterialTheme.typography.bodyMedium,
                // The active window is the one currently spending; weight is the
                // whole mark, since a badge on one of three rows is noise.
                fontWeight = if (limit.isActive) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${pct.toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = bar,
            )
        }
        Meter(pct, bar)
        PlanFormat.resetLabel(limit.resetsAt, nowMs)?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Extra usage, when the account has any.
 *
 * A tinted panel rather than another bare meter, because this is a different KIND
 * of number: the limit rows above are headroom, and this one is money already
 * owed. The amount leads and the state follows it in a quiet line — those three
 * states are the difference between "spend it", "you turned it off" and "the org
 * turned it off until the month rolls".
 */
@Composable
private fun ExtraUsageCard(card: PlanFormat.ExtraUsageCard) {
    val pct = card.percent.coerceIn(0.0, 100.0)
    val bar = meterColor(card.severity, pct)
    Surface(
        // Explicit contentColor: a Surface is what provides LocalContentColor, and
        // a tinted container without one hands its children whatever the last
        // Surface decided.
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Extra usage",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${pct.toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = bar,
                )
            }
            Meter(pct, bar)
            card.amountLine?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                card.state,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A filled track, no accent bar: the fill IS the mark. */
@Composable
private fun Meter(percent: Double, color: Color) {
    LinearProgressIndicator(
        progress = { (percent / 100.0).toFloat() },
        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        drawStopIndicator = {},
    )
}

/** Colour by headroom, not decoration: this is the number that stops work. */
@Composable
private fun meterColor(severity: String?, percent: Double): Color = when {
    severity == "critical" || severity == "high" || percent >= 90 -> MaterialTheme.colorScheme.error
    severity == "warning" || percent >= 70 -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.secondary
}

@Composable
private fun UsageRow(label: String, tokens: Long, cost: Double?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                PlanFormat.compactTokens(tokens),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (cost != null) {
                Text(
                    PlanFormat.approxDollars(cost),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun Warn(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}
