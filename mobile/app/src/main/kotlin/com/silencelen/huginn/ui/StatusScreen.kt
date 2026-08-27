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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.silencelen.huginn.appVersion
import com.silencelen.huginn.data.Plan
import com.silencelen.huginn.data.Status
import com.silencelen.huginn.data.Usage
import kotlinx.coroutines.delay

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
    val nowMs = planClock()
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
            PlanSection(plan, nowMs, sectionPadding)
            SectionLabel("Tokens")
            UsageSection(usage, sectionPadding)
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
        PlanSection(plan, nowMs, sectionPadding)

        HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

        SectionLabel("Tokens")
        UsageSection(usage, sectionPadding)

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
 * A clock that ticks while the screen is on the front, and stops when it is not.
 *
 * The plan rows count DOWN to their resets, so a `nowMs` sampled once per
 * composition freezes the moment the screen settles — and this screen can sit
 * open. Thirty seconds because the finest unit shown is a minute. Tied to the
 * lifecycle rather than to composition: a backgrounded screen counting down to
 * itself is a wakeup every half minute for a number nobody is reading.
 */
@Composable
private fun planClock(): Long {
    var started by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) {
        started = true
        onStopOrDispose { started = false }
    }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(30_000)
        }
    }
    return nowMs
}

/** The screen's own gutter; the shared sections draw edge to edge without it. */
private val sectionPadding = Modifier.padding(horizontal = 16.dp)
