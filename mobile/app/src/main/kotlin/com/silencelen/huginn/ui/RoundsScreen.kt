package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Round

/**
 * The Rounds tab: what this host does on a schedule, and what it last found.
 *
 * Its own destination rather than a band above the chat list. A Round is not a
 * conversation — it has a cadence, a goal and a verdict — and sharing a screen
 * made each of them read as the other's preamble.
 *
 * No header of its own: the tab is already called Rounds, and repeating the word
 * at the top of the screen it opened is the same fact twice.
 */
@Composable
fun RoundsScreen(
    rounds: List<Round>,
    nowMs: Long,
    loading: Boolean,
    connected: Boolean?,
    onOpenRound: (Round) -> Unit,
    onRunRound: (Round) -> Unit,
    onSetRoundEnabled: (Round, Boolean) -> Unit,
    onEditRound: (Round) -> Unit,
    /** "I have read this and dealt with it." Null hides the control. */
    onAcknowledgeRound: ((Round, Boolean) -> Unit)? = null,
    onOpenSettings: () -> Unit,
    onNewRound: () -> Unit,
    onStartPolling: () -> Unit = {},
    onStopPolling: () -> Unit = {},
) {
    // Tied to the screen, not to the app: nothing polls Rounds while you are
    // reading a chat, and the list is live while you are looking at it.
    DisposableEffect(Unit) {
        onStartPolling()
        onDispose { onStopPolling() }
    }

    Box(Modifier.fillMaxSize()) {
        if (rounds.isEmpty() && !loading) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                if (connected == false) {
                    EmptyState(
                        "Not connected",
                        "Add the server URL and token in Settings, then pull to refresh.",
                    )
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        TextButton(onClick = onOpenSettings) { Text("Open Settings") }
                    }
                } else {
                    EmptyState(
                        "No rounds yet",
                        "A round is work huginn does on a schedule and reports back on \u2014 " +
                            "a weekly alert review, a nightly check. Each one runs once against a " +
                            "goal, then closes and stays here to read.",
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
            ) {
                item(key = "rounds") {
                    RoundsSection(
                        rounds = rounds,
                        nowMs = nowMs,
                        header = null,
                        onOpenRound = onOpenRound,
                        onRunNow = onRunRound,
                        onSetEnabled = onSetRoundEnabled,
                        onEdit = onEditRound,
                        onAcknowledge = onAcknowledgeRound,
                    )
                }
            }
        }

        // The whole point of this release: until now a Round could only be made
        // with curl, so the feature was real and unreachable.
        ExtendedFloatingActionButton(
            onClick = onNewRound,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("New round") },
        )
    }
}
