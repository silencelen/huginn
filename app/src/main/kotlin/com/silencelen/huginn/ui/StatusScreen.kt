package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Status

/** The same health summary `huginn status` prints, minus what a phone cannot use. */
@Composable
fun StatusScreen(status: Status?, error: String?, sessions: Int, chatsRunning: Int) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
        KeyValueRow("appd", status.appdVersion ?: "unknown")
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
