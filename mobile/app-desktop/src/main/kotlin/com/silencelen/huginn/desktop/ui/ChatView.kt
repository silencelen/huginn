package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.Message
import com.silencelen.huginn.desktop.ChatController
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.silencelen.huginn.data.TranscriptEvent
import com.silencelen.huginn.ui.MarkdownText
import com.silencelen.huginn.ui.TranscriptEventItem
import androidx.compose.runtime.collectAsState

/**
 * One chat: the digest as the server tells it, then whatever the live run has
 * streamed on top.
 *
 * The partial answer is rendered as its OWN trailing block rather than being
 * merged into the digest. Merging is where the double-render bug lives: the
 * digest gains the finished text at the same moment the stream stops producing
 * it, and any overlap between the two shows up as a paragraph written twice.
 */
@Composable
fun ChatView(client: HuginnClient, chatId: String) {
    val scope = rememberCoroutineScope()
    val controller = remember(chatId) { ChatController(client, chatId, scope) }
    DisposableEffect(chatId) {
        controller.start()
        onDispose { controller.close() }
    }

    val detail by controller.detail.collectAsState()
    val partial by controller.partial.collectAsState()
    val running by controller.running.collectAsState()
    val activity by controller.activity.collectAsState()
    val error by controller.error.collectAsState()
    val queued by controller.queued.collectAsState()
    val pendingSend by controller.pendingSend.collectAsState()

    var draft by remember(chatId) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val messages = detail?.messages ?: emptyList()

    // Follow the tail while an answer is being written. Keyed on the partial's
    // LENGTH rather than its content so a 4000-delta burst schedules one scroll
    // per frame instead of one per delta.
    LaunchedEffect(messages.size, partial.length, pendingSend) {
        val last = messages.size +
            (if (pendingSend != null) 1 else 0) +
            (if (partial.isNotEmpty()) 1 else 0)
        if (last > 0) listState.animateScrollToItem(last - 1)
    }

    Column(Modifier.fillMaxSize()) {
        Header(detail?.title ?: chatId, detail?.mode, detail?.model, running, activity)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        // The weight lives on a plain Box, NOT on the SelectionContainer. Handing
        // the weight straight to SelectionContainer left the composer measured
        // after the scroll area had already taken the remaining height, so it was
        // laid out past the bottom edge and clipped away entirely — a window with
        // no way to type in it, and nothing in the logs.
        Box(Modifier.weight(1f)) {
            SelectionContainer {
                // The phone's chat rhythm, because the rows are now the phone's
                // rows: they carry no outer margin of their own, so the gap
                // between them belongs to whoever lists them.
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    state = listState,
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    itemsIndexed(messages) { i, m -> MessageBlock(m, i) }
                    pendingSend?.let { text ->
                        item("pending") { MessageBlock(Message(type = "user", text = text), messages.size) }
                    }
                    if (partial.isNotEmpty()) {
                        item("partial") { PartialBlock(partial) }
                    }
                }
            }
        }

        queued?.let {
            Muted("queued behind the current run (#$it)", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f).heightIn(min = 56.dp, max = 160.dp)
                    // Ctrl+Enter sends; plain Enter is a newline. The opposite
                    // binding on a desktop composer sends half-written messages,
                    // and a chat message cannot be unsent.
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.isCtrlPressed && e.key == Key.Enter) {
                            controller.send(draft); draft = ""; true
                        } else false
                    },
                placeholder = { Text("Message…  (Ctrl+Enter to send)") },
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            if (running) TextButton(onClick = { controller.cancel() }) { Text("Stop") }
            Button(
                onClick = { controller.send(draft); draft = "" },
                enabled = draft.isNotBlank(),
            ) { Text(if (running) "Queue" else "Send") }
        }
    }
}

@Composable
private fun Header(title: String, mode: String?, model: String?, running: Boolean, activity: String?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (running) StateDot(MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        // State marks in the header's own vernacular: muted suffixes, not badges.
        activity?.let { Muted("$it…", Modifier.padding(end = 10.dp)) }
        mode?.let { Muted(it, Modifier.padding(end = 10.dp)) }
        model?.let { Muted(it) }
    }
}

/**
 * One message from the chat digest.
 *
 * The user bubble, the answer and the tool card are the PHONE's — `:ui` owns all
 * three now, and this file no longer has an opinion about how any of them look.
 * That is worth spelling out because the three it used to own were the classic
 * lookalike divergence: a different bubble radius, a different width rule, and a
 * tool call flattened to one muted line where the phone folds it into an openable
 * card. Anything that disagreed, the phone won.
 *
 * `result` and `error` stay here on purpose: they are not transcript content,
 * they are how a RUN ended, and `TranscriptEvent` has no kind for them.
 */
@Composable
private fun MessageBlock(m: Message, seq: Int) {
    when (m.type) {
        "user", "assistant", "tool" -> TranscriptEventItem(
            TranscriptEvent(
                seq = seq,
                kind = m.type,
                text = m.text,
                name = m.name,
                input = m.input,
                ok = m.ok,
            ),
            onCopy = rememberCopy(),
        )

        "result" -> Muted(
            buildString {
                append(if (m.ok == false) "failed" else "done")
                m.durationMs?.let { append(" · ${it / 1000}s") }
                m.turns?.let { append(" · $it turns") }
            },
            Modifier.padding(vertical = 4.dp),
        )

        "error" -> Text(
            m.text ?: "error",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        else -> Muted("${m.type}: ${m.text ?: ""}", Modifier.padding(vertical = 2.dp))
    }
}

/** The answer currently being written. Marked so it reads as in-progress. */
@Composable
private fun PartialBlock(text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        MarkdownText(text, onCopy = rememberCopy())
        Text(
            "▍",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
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
