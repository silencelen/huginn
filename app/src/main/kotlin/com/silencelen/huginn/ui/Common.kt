package com.silencelen.huginn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Session state marks. Deliberately a small filled dot plus a word rather than a
 * bar or a badge: the state is a property of the row, not a decoration on it.
 */
@Composable
fun StateDot(state: String?, modifier: Modifier = Modifier) {
    val c = when (state) {
        "running" -> MaterialTheme.colorScheme.primary
        "attention" -> MaterialTheme.colorScheme.error
        "idle" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.outline
    }
    Box(modifier.size(8.dp).clip(CircleShape).background(c))
}

fun stateLabel(state: String?): String = when (state) {
    "running" -> "working"
    "attention" -> "needs you"
    "idle" -> "waiting"
    else -> "no claude"
}

/** "3m", "2h", "4d" — a phone has no room for timestamps nobody reads. */
fun relTime(epochSec: Long): String {
    if (epochSec <= 0) return ""
    val secs = (System.currentTimeMillis() / 1000 - epochSec).coerceAtLeast(0)
    return when {
        secs < 60 -> "now"
        secs < 3600 -> "${secs / 60}m"
        secs < 86_400 -> "${secs / 3600}h"
        else -> "${secs / 86_400}d"
    }
}

fun formatUptime(sec: Long): String {
    val d = sec / 86_400
    val h = (sec % 86_400) / 3600
    val m = (sec % 3600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
fun EmptyState(title: String, hint: String) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun KeyValueRow(key: String, value: String, valueColor: Color? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}
