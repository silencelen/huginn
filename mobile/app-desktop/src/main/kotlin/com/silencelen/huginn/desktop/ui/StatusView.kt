package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Plan
import com.silencelen.huginn.data.Status
import com.silencelen.huginn.data.Usage

/**
 * Host, plan headroom and token usage. Three endpoints on one screen, which makes
 * it the cheapest end-to-end proof that the client can talk to the daemon at all —
 * if this renders, /v1/status, /v1/plan and /v1/usage all decoded.
 */
@Composable
fun StatusView(status: Status?, plan: Plan?, usage: Usage?, route: String, watchConnected: Boolean) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Status", style = MaterialTheme.typography.titleMedium)

        Section("Host")
        if (status == null) {
            Muted("no answer from $route yet")
        } else {
            Field("host", status.host ?: "?")
            Field("appd", status.appdVersion ?: "?")
            Field("claude", status.claude ?: "?")
            Field("uptime", humanUptime(status.uptimeSec))
            Field("load", status.load.joinToString(" ") { fmt(it, 2) } + "  (${status.cores} cores)")
            status.disk?.let { Field("disk", "${it.used ?: "?"} / ${it.size ?: "?"} used (${it.usedPercent ?: "?"})") }
            Field("sessions", "${status.sessions}")
            Field("chats running", "${status.chatsRunning}")
        }

        Section("Connection")
        Field("route", route)
        Field("watch stream", if (watchConnected) "attached" else "detached")

        Section("Plan")
        val limits = plan?.limits.orEmpty()
        if (plan?.error != null) Muted(plan.error!!)
        else if (limits.isEmpty()) Muted("no plan data")
        else limits.forEach { limit ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(limit.label, style = MaterialTheme.typography.bodyMedium)
                    Muted("${fmt(limit.percent, 0)}%" + (limit.resetsAt?.let { " · resets ${shortTime(it)}" } ?: ""))
                }
                Meter(limit.percent / 100.0, limit.severity)
            }
        }

        Section("Usage")
        val today = usage?.data?.today
        val week = usage?.data?.week
        if (usage?.error != null) Muted(usage.error!!)
        else {
            Field("today", "${thousands(today?.totalTokens ?: 0)} tokens")
            Field("7 days", "${thousands(week?.totalTokens ?: 0)} tokens")
            // The $ figure is ccusage's list-rate estimate; on a Max plan it
            // overstates the real cost by a wide margin, so it is labelled rather
            // than shown as a number someone might quote.
            if (usage?.costIsEstimate == true) Muted("cost figures are list-rate estimates, not what the plan charges")
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Muted(label)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A filled track, no accent bar: the fill IS the mark. */
@Composable
private fun Meter(fraction: Double, severity: String) {
    val scheme = MaterialTheme.colorScheme
    val color = when (severity) {
        "critical", "high" -> scheme.error
        else -> scheme.primary
    }
    Box(
        Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(scheme.surfaceVariant),
    ) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0.0, 1.0).toFloat())
                .fillMaxSize()
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
    }
}

private fun fmt(v: Double, decimals: Int): String {
    if (decimals == 0) return v.toLong().toString()
    val scale = generateSequence(1L) { it * 10 }.take(decimals + 1).last()
    return ((v * scale).toLong() / scale.toDouble()).toString()
}

private fun thousands(n: Long): String =
    n.toString().reversed().chunked(3).joinToString(",").reversed()

/**
 * The daemon passes Claude's reset timestamps through verbatim, microseconds and
 * all. Nobody reads `2026-08-03T08:00:00.746373+00:00`; the minute is the
 * information.
 */
private fun shortTime(iso: String): String =
    Regex("^(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2})").find(iso)
        ?.let { "${it.groupValues[1]} ${it.groupValues[2]}" } ?: iso

private fun humanUptime(sec: Long): String = when {
    sec <= 0 -> "?"
    sec < 3600 -> "${sec / 60}m"
    sec < 86_400 -> "${sec / 3600}h ${(sec % 3600) / 60}m"
    else -> "${sec / 86_400}d ${(sec % 86_400) / 3600}h"
}
