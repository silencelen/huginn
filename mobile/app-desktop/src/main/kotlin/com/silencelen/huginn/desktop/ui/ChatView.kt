package com.silencelen.huginn.desktop.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.DraftBook
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.TranscriptEvent
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.ChatController
import com.silencelen.huginn.desktop.attach.AttachButton
import com.silencelen.huginn.desktop.attach.AttachChip
import com.silencelen.huginn.desktop.attach.AttachFilePicker
import com.silencelen.huginn.desktop.attach.AttachStatus
import com.silencelen.huginn.desktop.attach.AttachmentController
import com.silencelen.huginn.desktop.attach.AwtTransfer
import com.silencelen.huginn.desktop.attach.appendDropped
import com.silencelen.huginn.desktop.attach.attachmentDropTarget
import com.silencelen.huginn.desktop.attach.composeMessage
import com.silencelen.huginn.desktop.attach.rememberAttachmentController
import com.silencelen.huginn.desktop.ui.chat.ChatTopBar
import com.silencelen.huginn.ui.LocalTranscriptMetrics
import com.silencelen.huginn.ui.FollowNewest
import com.silencelen.huginn.ui.MarkdownText
import com.silencelen.huginn.ui.NewestPill
import com.silencelen.huginn.ui.Suggest
import com.silencelen.huginn.ui.SuggestionChips
import com.silencelen.huginn.ui.TranscriptEventItem
import com.silencelen.huginn.ui.TranscriptGroups
import com.silencelen.huginn.ui.TranscriptRowItem
import com.silencelen.huginn.ui.scrollToNewest
import com.silencelen.huginn.ui.tailRevision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * One chat: the session's real Claude Code transcript, plus whatever the live run
 * has streamed on top of it.
 *
 * The history is rendered by the same code as a tmux session's conversation, so a
 * chat shows thinking, tool results and subagent output rather than the flat
 * digest this view used to draw. That was not a styling difference: the digest
 * has no thinking records and no tool results in it at all, so no amount of
 * rendering could have shown them.
 *
 * The partial answer is its OWN trailing block rather than being merged into the
 * transcript. Merging is where the double-render bug lives: the transcript gains
 * the finished text at the same moment the stream stops producing it, and any
 * overlap between the two shows up as a paragraph written twice.
 *
 * @param store the app store, which owns the draft book and the navigation this
 *   view needs when a chat is deleted underneath it. Defaulted rather than
 *   required because `Shell` still constructs this from a bare client; passing
 *   `store` there instead of `store.client` retires the default and the holder
 *   behind it.
 */
@Composable
fun ChatView(
    client: HuginnClient,
    chatId: String,
    store: AppStore? = AppStore.current,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(chatId) { ChatController(client, chatId, scope) }
    // Keyed on the chat: switching chats must not carry a half-uploaded screenshot
    // into somebody else's conversation, and the old controller's upload is
    // cancelled with it.
    val attachments = rememberAttachmentController(client, scope, chatId)
    val drafts = store?.drafts
    val draftKey = DraftBook.chatKey(chatId)

    DisposableEffect(chatId) {
        controller.start()
        onDispose {
            controller.close()
            // Leaving the chat is exactly when a debounced write is still in the
            // air, and the composition scope that would have run it is being
            // cancelled. The book belongs to the app for this reason.
            drafts?.flush()
        }
    }

    val detail by controller.detail.collectAsState()
    val page by controller.page.collectAsState()
    val partial by controller.partial.collectAsState()
    val running by controller.running.collectAsState()
    val activity by controller.activity.collectAsState()
    val error by controller.error.collectAsState()
    val notice by controller.notice.collectAsState()
    val deleted by controller.deleted.collectAsState()
    val models by controller.models.collectAsState()
    val suggestions by controller.suggestions.collectAsState()
    val pendingSend by controller.pendingSend.collectAsState()

    // A deleted chat must not stay on screen: the pane would be showing a
    // conversation the daemon no longer has, and every action on it would 404.
    // Its draft goes with it — the map is rewritten whole on every save, so an
    // orphan is paid for on every keystroke in every other chat, forever.
    LaunchedEffect(deleted) {
        if (deleted) {
            drafts?.clear(DraftBook.chatKey(chatId))
            // The refresh runs on the APP's scope, not this effect's. Closing the
            // view is the same frame that cancels the effect, so a refresh
            // launched here dies at its first suspension point — and the store's
            // runCatching then reports the cancellation as an error, which put
            // "The coroutine scope left the composition" in the status bar every
            // time a chat was deleted.
            store?.let { s ->
                s.scope.launch { s.refreshChats() }
                s.openChat(null)
            }
        }
    }

    // The draft, from the app's book when there is one. The fallback keeps this
    // view usable when it is built without a store (nothing in the app does, but
    // a composable that silently stops accepting typing is not a good failure).
    val fallback = remember { MutableStateFlow(emptyMap<String, String>()) }
    val draftMap by (drafts?.drafts ?: fallback).collectAsState()
    val draft = draftMap[draftKey].orEmpty()
    val setDraft: (String) -> Unit = { text ->
        if (drafts != null) drafts.set(draftKey, text)
        else fallback.value = fallback.value + (draftKey to text)
    }
    val clearDraft: () -> Unit = {
        if (drafts != null) drafts.clear(draftKey)
        else fallback.value = fallback.value - draftKey
    }

    val events = page?.events ?: emptyList()
    val rows = remember(events) { TranscriptGroups.group(events) }
    val rowKeys = remember(rows) { TranscriptGroups.keys(rows) }
    val streaming = partial.isNotEmpty() || activity != null
    val busy = running || streaming || pendingSend != null
    val itemCount = rows.size + (if (pendingSend != null) 1 else 0) + (if (streaming) 1 else 0)

    val listState = rememberLazyListState()
    // partial.length is what makes a live answer follow: the item count does not
    // change while tokens arrive into the same block.
    val hasUnseen = FollowNewest(
        listState = listState,
        itemCount = itemCount,
        revision = tailRevision(page?.nextOffset, events.size, partial.length, activity, pendingSend),
        key = chatId,
    )

    // Suggestions are asked for by the CONTROLLER, at the turn boundary it can
    // see (the transcript settled, nothing in flight) rather than from an effect
    // here. A view that fetches on its own recomposition asks again every time
    // the window regains focus, for an answer the daemon has already cached.

    Column(Modifier.fillMaxSize()) {
        ChatTopBar(
            title = detail?.title ?: "Untitled",
            running = running,
            activity = activity,
            mode = detail?.mode,
            model = detail?.model,
            effort = detail?.effort,
            models = models,
            // Model, effort and mode are fixed when a run spawns, so offering to
            // change them mid-turn would be offering something that cannot happen.
            optionsEnabled = !busy,
            onModel = { controller.setOptions(model = it) },
            onEffort = { controller.setOptions(effort = it) },
            onMode = { controller.setOptions(mode = it) },
            onRename = { controller.rename(it) },
            onDelete = { controller.delete() },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        notice?.let {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { controller.dismissNotice() }) { Text("dismiss") }
            }
        }

        // The weight lives on a plain Box, NOT on the SelectionContainer. Handing
        // the weight straight to SelectionContainer left the composer measured
        // after the scroll area had already taken the remaining height, so it was
        // laid out past the bottom edge and clipped away entirely — a window with
        // no way to type in it, and nothing in the logs.
        // Bound to a local: `error` is a delegated property, which does not smart
        // cast, and `!!` on the owner's daily driver is a crash waiting for a race.
        val loadError = error
        Box(Modifier.weight(1f)) {
            when {
                // NOT the empty state. A conversation that failed to load looks
                // identical to one that has never run, and drawing history's
                // absence when the history is merely unread reads as data loss.
                loadError != null && !streaming -> LoadFailed(loadError) { controller.retry() }

                page == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }

                events.isEmpty() && !streaming && pendingSend == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        NewChatHint(detail?.mode ?: "ask")
                    }

                else -> SelectionContainer {
                    // The rows are the phone's rows and carry no outer margin of
                    // their own, so the gap between them belongs to whoever lists
                    // them — and that gap is a density decision the shell owns,
                    // not a property of a transcript row.
                    val metrics = LocalTranscriptMetrics.current
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        state = listState,
                        contentPadding = PaddingValues(vertical = metrics.rowPadding),
                        verticalArrangement = Arrangement.spacedBy(metrics.rowSpacing),
                    ) {
                        items(rows.size, key = { rowKeys[it] }) { i ->
                            TranscriptRowItem(rows[i], onCopy = rememberCopy())
                        }
                        pendingSend?.let { text ->
                            item("pending") {
                                TranscriptEventItem(
                                    TranscriptEvent(seq = -1, kind = "user", text = text),
                                    onCopy = rememberCopy(),
                                )
                            }
                        }
                        if (streaming) {
                            item("streaming") { StreamingBlock(partial, activity) }
                        }
                    }
                }
            }
        }

        if (hasUnseen) {
            NewestPill { scope.launch { listState.scrollToNewest(itemCount, animate = true) } }
        }

        // A suggestion FILLS the composer; it does not send. It yields to typing,
        // to a live prompt and to a running turn — Suggest.visible is that rule,
        // shared with the phone.
        if (Suggest.visible(suggestions, busy, draft)) {
            SuggestionChips(suggestions, onPick = setDraft)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Composer(
            draft = draft,
            onDraft = setDraft,
            onSent = clearDraft,
            attachments = attachments,
            scope = scope,
            running = running,
            onStop = { controller.cancel() },
            onSend = { text -> controller.send(text) },
        )
    }
}

/**
 * The composer, shared in shape with the session pane's: a chip for whatever is
 * attached, a clip button, the box itself, and Send.
 *
 * A SEND WAITS FOR AN IN-FLIGHT UPLOAD. Posting the moment the button is pressed
 * sends a message whose marker names a path the daemon has not finished writing,
 * and Claude's Read comes back "no such file" — the failure looks like the model
 * being unhelpful rather than like a race. [AttachmentController.take] is what
 * makes the wait a property of sending rather than of typing speed.
 *
 * [onSent] is separate from `onDraft("")` on purpose. Emptying the box is a
 * keystroke and is debounced; SENDING must cancel that pending write, or it
 * lands afterwards carrying the text that was just sent and the message
 * reappears as a draft. That is a bug the Electron client actually shipped.
 */
@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    onSent: () -> Unit,
    attachments: AttachmentController,
    scope: kotlinx.coroutines.CoroutineScope,
    running: Boolean,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
) {
    val attachment by attachments.current.collectAsState()
    val failure by attachments.failure.collectAsState()
    var picking by remember { mutableStateOf(false) }
    var dragOver by remember { mutableStateOf(false) }

    // Hoisted so the enabled rule and the submit path agree: an attachment that
    // FAILED is not something to send a message about, so it does not enable Send
    // on its own — exactly the Electron rule.
    val pending = attachment
    val canSend = draft.isNotBlank() || (pending != null && pending.status != AttachStatus.FAILED)

    val submit: () -> Unit = {
        if (canSend) {
            val body = draft
            onSent()
            scope.launch {
                val marker = attachments.take()
                val full = composeMessage(body, marker)
                if (full.isNotEmpty()) onSend(full)
            }
        }
    }

    AttachFilePicker(picking) { file ->
        picking = false
        if (file != null) attachments.attachFile(file)
    }

    Column(
        Modifier.fillMaxWidth()
            .attachmentDropTarget(
                controller = attachments,
                onText = { onDraft(appendDropped(draft, it)) },
                onDragOver = { dragOver = it },
            )
            .background(
                if (dragOver) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.background
            )
            .padding(12.dp),
    ) {
        pending?.let {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                AttachChip(it) { attachments.clear() }
            }
            it.detail?.takeIf { _ -> it.status == AttachStatus.READY }?.let { note ->
                Muted(note, Modifier.padding(bottom = 6.dp), maxLines = 2)
            }
        }
        failure?.let {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { attachments.dismissFailure() }) { Text("dismiss") }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AttachButton { picking = true }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp, max = 160.dp)
                    // Ctrl+Enter sends; plain Enter is a newline. The opposite
                    // binding on a desktop composer sends half-written messages,
                    // and a chat message cannot be unsent.
                    //
                    // Ctrl+V is intercepted only when the clipboard holds an image
                    // or a file: returning false lets the text field's own paste
                    // run, and swallowing it would make Ctrl+V insert nothing.
                    .onPreviewKeyEvent { e ->
                        when {
                            e.type != KeyEventType.KeyDown -> false
                            e.isCtrlPressed && e.key == Key.Enter -> { submit(); true }
                            e.isCtrlPressed && e.key == Key.V -> AwtTransfer.consumeClipboard(attachments)
                            else -> false
                        }
                    },
                placeholder = {
                    Text(
                        if (running) "Send anyway — it will queue behind this turn"
                        else "Message…  (Ctrl+Enter to send · paste, drop or clip a file)"
                    )
                },
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            if (running) TextButton(onClick = onStop) { Text("Stop") }
            // ONE send button whatever the state. A separate "Queue" verb was a
            // second control for the same act — the message goes to the same
            // place, and the transcript says it is waiting.
            Button(onClick = submit, enabled = canSend) { Text("Send") }
        }
    }
}

/** The turn currently arriving: markdown as it lands, then what it is doing. */
@Composable
private fun StreamingBlock(partial: String, activity: String?) {
    Column(Modifier.fillMaxWidth()) {
        if (partial.isNotEmpty()) {
            MarkdownText(partial, onCopy = rememberCopy())
            Text(
                "▍",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        when {
            // The run has started and said nothing yet. A slow pulse rather than a
            // spinner: this is waiting on a model, not on a network.
            activity == THINKING -> {
                Spacer(Modifier.size(6.dp))
                ThinkingLine()
            }
            activity != null -> {
                Spacer(Modifier.size(6.dp))
                TranscriptEventItem(
                    TranscriptEvent(seq = -2, kind = "tool", name = activity, input = "running"),
                    onCopy = rememberCopy(),
                )
            }
        }
    }
}

/** What the controller calls the gap between a run starting and its first token. */
private const val THINKING = "thinking"

@Composable
private fun ThinkingLine() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha",
    )
    Text(
        "thinking",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.alpha(alpha),
    )
}

/** A chat that has never run. Says what this kind of chat can do, and nothing else. */
@Composable
private fun NewChatHint(mode: String) {
    Column(
        Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(if (mode == "act") "Act mode" else "Ask mode", style = MaterialTheme.typography.titleMedium)
        Text(
            if (mode == "act") "Runs in ~/netplan with tools: files, commands, the web."
            else "Reasoning and memory, no tools.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** History that exists and could not be read. Nothing polls a chat, so offer the way back. */
@Composable
private fun LoadFailed(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Could not load this conversation", style = MaterialTheme.typography.titleMedium)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRetry) { Text("Try again") }
    }
}

/**
 * Copy, for the code cards inside a rendered answer. Compose's own clipboard, not
 * AWT's: the Electron client's release that denied every permission also denied
 * clipboard writes, and every copy in the app failed silently for a whole
 * release — the carry-over list names it as a requirement.
 */
@Composable
private fun rememberCopy(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    return remember(clipboard) { { text: String -> clipboard.setText(AnnotatedString(text)) } }
}
