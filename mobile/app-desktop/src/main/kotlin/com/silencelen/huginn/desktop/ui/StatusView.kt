package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Plan
import com.silencelen.huginn.data.Status
import com.silencelen.huginn.data.Usage
import com.silencelen.huginn.ui.PlanSection
import com.silencelen.huginn.ui.UsageSection
import kotlinx.coroutines.delay

/**
 * Host, plan headroom and token usage. Three endpoints on one screen, which makes
 * it the cheapest end-to-end proof that the client can talk to the daemon at all —
 * if this renders, /v1/status, /v1/plan and /v1/usage all decoded.
 */
@Composable
fun StatusView(status: Status?, plan: Plan?, usage: Usage?, route: String, watchConnected: Boolean) {
    // The plan rows count down to their resets, and this pane can sit open on a
    // second monitor for a working day. Thirty seconds: the countdown's finest
    // unit is a minute, so anything faster recomposes to redraw identical text.
    // Composition-gated, which on a desktop is the whole of the lifecycle there
    // is — the view exists only while Status is the selected pane.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(30_000)
        }
    }
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

        // Both sections are :ui's, drawn from :core's decisions — the same pixels
        // the phone gets, including the extra-usage card this pane never had and
        // the countdown that replaced a hand-sliced timestamp printing UTC.
        Section("Plan")
        PlanSection(plan, nowMs)

        Section("Usage")
        UsageSection(usage)
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

private fun fmt(v: Double, decimals: Int): String {
    if (decimals == 0) return v.toLong().toString()
    val scale = generateSequence(1L) { it * 10 }.take(decimals + 1).last()
    return ((v * scale).toLong() / scale.toDouble()).toString()
}

private fun humanUptime(sec: Long): String = when {
    sec <= 0 -> "?"
    sec < 3600 -> "${sec / 60}m"
    sec < 86_400 -> "${sec / 3600}h ${(sec % 3600) / 60}m"
    else -> "${sec / 86_400}d ${(sec % 86_400) / 3600}h"
}
