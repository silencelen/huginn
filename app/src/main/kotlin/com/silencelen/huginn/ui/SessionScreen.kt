package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Screen
import com.silencelen.huginn.data.TranscriptPage

/**
 * One tmux session, two ways of looking at it.
 *
 * **Conversation** is the default and is read from the session's Claude Code
 * transcript: real structured events (thinking, tool calls with their results,
 * subagent output, workflow runs) rather than anything scraped off the screen.
 * **Screen** is the live pane, for the things only the pane can do: answering a
 * prompt, watching a spinner, typing.
 */
@Composable
fun SessionScreen(
    name: String,
    transcript: TranscriptPage?,
    transcriptError: String?,
    screen: Screen?,
    tab: Int,
    onTab: (Int) -> Unit,
    fontScale: Float,
    onFontScale: (Float) -> Unit,
    onGeometry: (Int, Int) -> Unit,
    draft: String,
    onDraft: (String) -> Unit,
    onSendText: (String, Boolean) -> Unit,
    onSendKeys: (List<String>) -> Unit,
    onAnswerPrompt: (Int) -> Unit,
    onForceResize: () -> Unit,
    onCopy: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { onTab(0) }, text = { Text("Conversation") })
            Tab(selected = tab == 1, onClick = { onTab(1) }, text = { Text("Screen") })
        }
        SessionControls(
            // The pane reports the CURRENT model and mode; the transcript only
            // knows what the last completed turn used, so a just-issued /model
            // change would otherwise leave the control showing the old value.
            model = screen?.liveModel ?: transcript?.model,
            effort = transcript?.effort,
            permissionMode = screen?.liveMode ?: transcript?.permissionMode,
            // Slash commands go in as a submitted line, exactly as typed by hand.
            onCommand = { onSendText(it, true) },
            onCycleMode = { onSendKeys(listOf("BTab")) },
        )
        Box(Modifier.weight(1f)) {
            if (tab == 0) {
                SessionConversation(
                    name = name,
                    page = transcript,
                    error = transcriptError,
                    prompt = screen?.prompt,
                    draft = draft,
                    onDraft = onDraft,
                    onSendText = onSendText,
                    onAnswerPrompt = onAnswerPrompt,
                    onCopy = onCopy,
                )
            } else {
                TerminalScreen(
                    session = name,
                    screen = screen,
                    draft = draft,
                    onDraft = onDraft,
                    fontScale = fontScale,
                    onFontScale = onFontScale,
                    onGeometry = onGeometry,
                    onSendText = onSendText,
                    onSendKeys = onSendKeys,
                    onAnswerPrompt = onAnswerPrompt,
                    onForceResize = onForceResize,
                )
            }
        }
    }
}

@Composable
private fun SessionConversation(
    name: String,
    page: TranscriptPage?,
    error: String?,
    prompt: com.silencelen.huginn.data.PanePrompt?,
    draft: String,
    onDraft: (String) -> Unit,
    onSendText: (String, Boolean) -> Unit,
    onAnswerPrompt: (Int) -> Unit,
    onCopy: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val events = page?.events ?: emptyList()

    // Open on the newest message, then follow only while already at the bottom.
    AutoScrollToNewest(listState, events.size, key = name)

    Column(Modifier.fillMaxSize()) {
        when {
            error != null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(
                    "No conversation yet",
                    // The mapping is written by a hook that fires on the first
                    // prompt, so a brand-new session genuinely has nothing here.
                    error,
                )
            }
            page == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp, end = 14.dp, top = 8.dp, bottom = 10.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                if (page.truncated) {
                    item {
                        Text(
                            "Showing the most recent part of this session.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(events.size) { i -> TranscriptEventItem(events[i], onCopy) }
            }
        }

        prompt?.let {
            PromptCard(
                it.question,
                it.options.map { o -> o.number to o.label },
                it.options.firstOrNull { o -> o.selected }?.number,
                onAnswerPrompt,
            )
        }

        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraft,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Reply in $name") },
                    maxLines = 6,
                    shape = RoundedCornerShape(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = { if (draft.isNotBlank()) onSendText(draft, true) },
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.size(46.dp),
                ) {
                    Icon(
                        Icons.Filled.KeyboardReturn,
                        contentDescription = "Send",
                        tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Header line shown in the app bar subtitle for a session. */
@Composable
fun SessionSubtitle(page: TranscriptPage?, screen: Screen?) {
    val bits = buildList {
        page?.model?.let { add(it.removePrefix("claude-").substringBeforeLast('-')) }
        page?.gitBranch?.let { add(it) }
        page?.permissionMode?.let { add("$it mode") }
        screen?.let { add("${it.width}x${it.height}") }
    }
    if (bits.isEmpty()) return
    Text(
        bits.joinToString("  ·  "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Normal,
    )
}
