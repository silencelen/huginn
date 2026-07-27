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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.ChatDetail
import com.silencelen.huginn.data.Message

/**
 * The conversation. Assistant text is plain body text on the page (no bubble) and
 * the user's lines are the ones that get a container, which keeps long answers
 * readable on a narrow screen and makes "who said what" obvious at a glance.
 */
@Composable
fun ChatScreen(
    detail: ChatDetail?,
    streamingText: String?,
    activeTool: String?,
    sending: Boolean,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val messages = detail?.messages ?: emptyList()

    // Follow the tail as tokens arrive, which is what a reader watching a live
    // answer expects; scrolling up during a stream is fine because a new item or
    // delta only nudges the list when it is already near the bottom.
    LaunchedEffect(messages.size, streamingText, activeTool) {
        val itemCount = messages.size + if (streamingText != null || activeTool != null) 1 else 0
        if (itemCount > 0) {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (last >= messages.size - 2) listState.animateScrollToItem(itemCount - 1)
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (detail == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages.size) { i -> MessageItem(messages[i]) }
                if (streamingText != null || activeTool != null) {
                    item { StreamingItem(streamingText, activeTool) }
                }
            }
        }

        Composer(
            draft = draft,
            onDraft = { draft = it },
            sending = sending,
            mode = detail?.mode ?: "ask",
            onSend = {
                val t = draft.trim()
                if (t.isNotEmpty()) { onSend(t); draft = "" }
            },
            onCancel = onCancel,
        )
    }
}

@Composable
private fun MessageItem(msg: Message) {
    when (msg.type) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                modifier = Modifier.fillMaxWidth(0.88f),
            ) {
                Text(
                    msg.text.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }

        "assistant" -> Text(
            msg.text.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        "tool" -> ToolChip(msg.name ?: "tool", msg.input)

        "result" -> {
            val bits = buildList {
                msg.durationMs?.let { add("${it / 1000}s") }
                msg.turns?.let { if (it > 1) add("$it turns") }
                msg.costUsd?.let { add(String.format("$%.3f", it)) }
            }
            if (bits.isNotEmpty()) {
                Text(
                    bits.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        "error" -> Surface(
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(
                msg.text.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** A tool the model ran, with the one field worth seeing (command, path, query). */
@Composable
private fun ToolChip(name: String, input: String?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Build,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(7.dp))
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!input.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    input,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StreamingItem(text: String?, activeTool: String?) {
    Column {
        if (!text.isNullOrEmpty()) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
        if (activeTool != null) {
            Spacer(Modifier.size(6.dp))
            ToolChip(activeTool, "running")
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
    draft: String,
    onDraft: (String) -> Unit,
    sending: Boolean,
    mode: String,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
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
                placeholder = { Text(if (mode == "act") "Ask huginn to do something" else "Ask huginn") },
                maxLines = 6,
                shape = RoundedCornerShape(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            if (sending) {
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
                IconButton(
                    onClick = onSend,
                    enabled = draft.isNotBlank(),
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(23.dp))
                        .background(
                            if (draft.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                ) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = "Send",
                        tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
