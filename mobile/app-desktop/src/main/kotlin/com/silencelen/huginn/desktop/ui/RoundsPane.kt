package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.ui.RoundsSection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Rounds destination: every scheduled job, what it last found, and the two
 * controls worth having at this level — run it now, or pause it.
 *
 * Full width rather than list-and-detail, because a Round row already carries its
 * whole report. Opening one means reading the run behind it, which is a chat, so
 * it hands off to the Chats destination instead of growing a second reader here.
 */
@Composable
fun RoundsPane(store: AppStore) {
    val rounds by store.rounds.collectAsState()
    val scope = rememberCoroutineScope()

    // A ticking clock, because this pane can sit open for hours and its rows are
    // all relative times. Thirty seconds: the rows round to minutes at the finest,
    // so anything faster would recompose to redraw identical text.
    var nowMs by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(30_000)
        }
    }

    if (rounds.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No rounds yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "A round is work this host does on a schedule and reports back on — " +
                    "a weekly alert review, a nightly check. Create one from the daemon " +
                    "and it appears here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
    ) {
        RoundsSection(
            rounds = rounds,
            nowMs = nowMs,
            // The rail already says "Rounds"; repeating it here would be the same
            // word in adjacent columns, which is the thing the rail lost its labels over.
            header = null,
            onOpenRound = { r -> r.lastRun?.chatId?.let { store.openChat(it) } },
            onRunNow = { r -> scope.launch { store.runRound(r.id) } },
            onSetEnabled = { r, on -> scope.launch { store.setRoundEnabled(r.id, on) } },
        )
    }
}
