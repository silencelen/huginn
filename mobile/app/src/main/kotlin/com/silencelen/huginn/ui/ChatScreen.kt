package com.silencelen.huginn.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.ModelChoice
import com.silencelen.huginn.data.TranscriptEvent
import kotlinx.coroutines.launch
import com.silencelen.huginn.data.TranscriptPage

/**
 * A chat. The history is the session's real Claude Code transcript, rendered by
 * the same code as a tmux session's conversation, so a chat shows thinking, tool
 * results and subagent output rather than the flat digest v1 kept. The live SSE
 * stream is still used while a turn is in flight, because the transcript is only
 * written as blocks complete and a phone should see tokens as they arrive.
 */
@Composable
fun ChatScreen(
    /**
     * The way out of a finished Round. Null when there is nothing to carry
     * forward — an all-clear round needs no door, and an offer on every sealed
     * run is an offer that means nothing on the ones that do.
     */
    onContinueRound: (() -> Unit)? = null,
    page: TranscriptPage?,
    /** Why the transcript could not be read, when it could not. */
    error: String?,
    onRetry: () -> Unit,
    streamingText: String?,
    activeTool: String?,
    sending: Boolean,
    mode: String,
    model: String?,
    effort: String?,
    models: List<ModelChoice>,
    /** The daemon's pin condition — history exists — which shapes the model menu. */
    started: Boolean,
    /** A local chat between send and first token: the model is loading, say so. */
    waking: Boolean = false,
    onSetOptions: (String?, String?) -> Unit,
    onMode: (String) -> Unit,
    onEscalate: (() -> Unit)? = null,
    chatId: String,
    suggestions: List<String>,
    voiceReady: Boolean,
    onVoicePermission: () -> Unit,
    draft: String,
    /** A finished Round run: readable, but closed to new messages. */
    sealedRun: Boolean = false,
    onDraft: (String) -> Unit,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onCopy: (String) -> Unit,
    attachment: HuginnViewModel.Attachment? = null,
    onAttach: (android.net.Uri) -> Unit = {},
    onAttachFile: (android.net.Uri) -> Unit = {},
    onClearAttachment: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var voiceOpen by remember { mutableStateOf(false) }
    val events = page?.events ?: emptyList()
    val rows = remember(events) { TranscriptGroups.group(events) }
    val rowKeys = remember(rows) { TranscriptGroups.keys(rows) }
    val streaming = streamingText != null || activeTool != null
    val itemCount = rows.size + if (streaming) 1 else 0

    // streamingText.length is what makes a live answer follow: the item count does
    // not change while tokens arrive into the same block.
    val hasUnseen = AutoScrollToNewest(
        listState = listState,
        itemCount = itemCount,
        revision = tailRevision(page?.nextOffset, events.size, streamingText?.length, activeTool),
        key = chatId,
    )

    if (voiceOpen) {
        VoiceSheet(
            micGranted = voiceReady,
            onRequestMic = onVoicePermission,
            sending = sending,
            streamingText = streamingText,
            lastAnswer = remember(events) {
                events.lastOrNull { it.kind == "assistant" && !it.sidechain }?.text
            },
            onSend = { onSend(it) },
            onDismiss = { voiceOpen = false },
        )
    }

    Column(Modifier.fillMaxSize()) {
        // Model and effort apply to the next turn; a run already in flight keeps
        // what it started with, because the flags are fixed when it spawns.
        ChatOptionsBar(
            mode = mode,
            model = model,
            effort = effort,
            models = models,
            enabled = !sending,
            started = started,
            onModel = { onSetOptions(it, null) },
            onEffort = { onSetOptions(null, it) },
            onMode = onMode,
            onEscalate = onEscalate,
        )
        if (error != null && !streaming) {
            // NOT the empty state. A chat that failed to load looks identical to a
            // chat that has never run, and drawing history's absence when the
            // history is merely unread reads as data loss. Nothing polls a chat, so
            // the way back has to be offered rather than waited for.
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
            ) {
                EmptyState("Could not load this conversation", error)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = onRetry) { Text("Try again") }
                }
            }
        } else if (page == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
        } else if (events.isEmpty() && !streaming) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(
                    if (mode == "act") "Act mode" else "Ask mode",
                    if (mode == "act") "Runs on the host with tools: files, commands, the web."
                    else "Reasoning and memory, no tools.",
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp, end = 14.dp, top = 8.dp, bottom = 10.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                items(rows.size, key = { rowKeys[it] }) { i -> TranscriptRowItem(rows[i], onCopy) }
                if (streaming) {
                    item { StreamingItem(streamingText, activeTool, waking, onCopy) }
                }
            }
        }

        if (hasUnseen) {
            JumpToNewest { scope.launch { listState.jumpToTail(itemCount, animate = true) } }
        }

        // Same contract as the session chips: a suggestion FILLS the composer,
        // yields to typing, and clears when a new turn starts.
        if (suggestions.isNotEmpty() && !streaming && !sending && draft.isBlank()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suggestions.forEach { sug ->
                    SuggestionChip(
                        onClick = { onDraft(sug) },
                        label = {
                            Text(
                                sug,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }

        Composer(
            sealedRun = sealedRun,
            onContinueRound = onContinueRound,
            draft = draft,
            onDraft = onDraft,
            sending = sending,
            mode = mode,
            micGranted = voiceReady,
            onRequestMic = onVoicePermission,
            onVoiceOpen = { voiceOpen = true },
            attachment = attachment,
            onAttach = onAttach,
            onAttachFile = onAttachFile,
            onClearAttachment = onClearAttachment,
            onSend = {
                val t = draft.trim()
                // A photo alone is a complete message: "what is this?" is implied
                // by having attached it, and the send path builds the marker text.
                if (t.isNotEmpty() || attachment is HuginnViewModel.Attachment.Ready) onSend(t)
            },
            onCancel = onCancel,
        )
    }
}

/**
 * The turn currently arriving. Rendered as markdown like a finished answer so the
 * text does not visibly reflow when the block completes and the transcript
 * replaces it.
 */
@Composable
private fun StreamingItem(text: String?, activeTool: String?, waking: Boolean, onCopy: (String) -> Unit) {
    Column {
        if (!text.isNullOrEmpty()) {
            MarkdownText(text, onCopy)
        }
        if (activeTool != null) {
            Spacer(Modifier.size(6.dp))
            TranscriptEventItem(
                TranscriptEvent(kind = "tool", name = activeTool, input = "running"),
                onCopy,
            )
        } else if (text.isNullOrEmpty() && waking) {
            // A cold local model takes up to ~30s to load; a generic thinking
            // shimmer over that silence reads as a hang. Name what is happening.
            Text(
                "waking the local model…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (text.isNullOrEmpty()) {
            ThinkingLine()
        }
    }
}

/** A slow pulse rather than a spinner: this waits on a model, not on a network. */
@Composable
private fun ThinkingLine() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val a by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha",
    )
    Text(
        "thinking",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.alpha(a),
    )
}

@Composable
private fun Composer(
    sealedRun: Boolean = false,
    /** The way out of a finished Round; null when there is nothing to carry on. */
    onContinueRound: (() -> Unit)? = null,
    draft: String,
    onDraft: (String) -> Unit,
    sending: Boolean,
    mode: String,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    onVoiceOpen: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    attachment: HuginnViewModel.Attachment? = null,
    onAttach: (android.net.Uri) -> Unit = {},
    onAttachFile: (android.net.Uri) -> Unit = {},
    onClearAttachment: () -> Unit = {},
) {
    // REPLACED, not disabled beside a note. The daemon refuses a send to a sealed
    // run with 409, and offering an input that cannot deliver is the kind of small
    // dishonesty that makes a working feature feel broken. Returning early here
    // rather than at the call site keeps the decision in one place, and the scope
    // of the `return` unambiguous.
    if (sealedRun) {
        // navigationBarsPadding, like the Surface below it: the Scaffold sets
        // contentWindowInsets to zero precisely because every composer owns its own
        // inset, so a replacement that forgot it would sit under the system bar.
        SealedNote(Modifier.fillMaxWidth().navigationBarsPadding(), onContinue = onContinueRound)
        return
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
      Column {
        AttachmentBar(attachment, onClearAttachment)
        Row(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (sending) "Send anyway, it will queue"
                        else if (mode == "act") "Ask huginn to do something"
                        else "Ask huginn"
                    )
                },
                maxLines = 6,
                shape = RoundedCornerShape(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            AttachButton(onPickImage = onAttach, onPickFile = onAttachFile)
            DictationMicButton(
                micGranted = micGranted,
                onRequestMic = onRequestMic,
                onText = { heard -> onDraft(appendDictation(draft, heard)) },
            )
            // Voice MODE, distinct from dictation: a hands-free loop that listens,
            // sends, reads the answer aloud, and listens again.
            IconButton(
                // ALWAYS opens the sheet. The permission ask happens inside it,
                // visibly — a tap that can silently do nothing is a broken button,
                // and on some builds a permission request IS silently denied.
                onClick = onVoiceOpen,
                modifier = Modifier.size(46.dp),
            ) {
                Icon(
                    Icons.Filled.GraphicEq,
                    contentDescription = "Voice conversation",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (sending && draft.isBlank()) {
                // Nothing typed: the useful action on a running turn is to stop it.
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(23.dp))
                        .background(MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = MaterialTheme.colorScheme.onError)
                }
            } else {
                val canSend = draft.isNotBlank() || attachment is HuginnViewModel.Attachment.Ready
                IconButton(
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(23.dp))
                        .background(
                            if (canSend) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                ) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = "Send",
                        tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
      }
    }
}
