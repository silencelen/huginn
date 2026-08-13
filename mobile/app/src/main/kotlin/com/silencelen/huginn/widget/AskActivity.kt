package com.silencelen.huginn.widget

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.silencelen.huginn.MainActivity
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.SettingsStore
import com.silencelen.huginn.notify.AppLock
import com.silencelen.huginn.notify.SessionWatchWorker
import com.silencelen.huginn.ui.theme.HuginnTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The ask bar's other half: a thin overlay over the launcher with the keyboard
 * already up. Type, send, and the question becomes a chat you land in.
 *
 * Send is the moment the chat is created — the same stance as the fleet
 * widget's New chat button: nothing exists on the host until a deliberate act,
 * so opening and abandoning this sheet costs nothing anywhere.
 *
 * The send itself is createChat + queueMessage, the queueing endpoint and never
 * the streaming one — ReplyReceiver's reasoning, inherited: this activity is
 * finishing as the run starts and has nothing to show a stream to. The host
 * starts the run immediately on an idle chat, and the chat screen this hands
 * off to attaches to it with its ordinary reattach machinery.
 *
 * A FragmentActivity for the same reason MainActivity is one: this is a second
 * doorway into free-text-to-the-host, so it honours the app lock itself —
 * arriving from the launcher must not be a way around a lock the front door
 * enforces. Auth failure finishes rather than lingering: fail closed, retry by
 * tapping the bar again.
 */
class AskActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The same blocking read MainActivity makes at creation, for the same
        // reason: the lock decision cannot wait on DataStore.
        val lockEnabled = runBlocking { SettingsStore(applicationContext).appLock.first() }
        if (lockEnabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        val startLocked = AppLock.lockedNow ||
            AppLock.shouldLock(lockEnabled, AppLock.lastAwayAt, System.currentTimeMillis())

        setContent {
            HuginnTheme {
                var locked by remember { mutableStateOf(startLocked) }
                if (locked) {
                    // Scrim only while the system sheet decides; the composer
                    // must not be drawn — or focused, or filled — behind it.
                    Scrim(onDismiss = { finish() })
                    LaunchedEffect(Unit) {
                        AppLock.authenticate(this@AskActivity) { ok, _ ->
                            if (ok) {
                                AppLock.lockedNow = false
                                locked = false
                            } else {
                                finish()
                            }
                        }
                    }
                } else {
                    AskSheet(
                        onCancel = { finish() },
                        onSubmit = { mode, text -> submit(mode, text) },
                        onSent = { chatId ->
                            startActivity(
                                Intent(this@AskActivity, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    putExtra(SessionWatchWorker.EXTRA_CHAT, chatId)
                                }
                            )
                            finish()
                        },
                    )
                }
            }
        }
    }

    /** Creates the chat and hands it the question. Returns the chat to open. */
    private suspend fun submit(mode: String, text: String): String {
        val settings = SettingsStore(applicationContext)
        val bearer = settings.token.first()
        if (bearer.isBlank()) throw IllegalStateException("Not signed in — open Huginn first")
        val base = settings.baseUrl.first()
        val client = HuginnClient({ base }, { bearer })
        return withTimeout(25_000) {
            val chat = client.createChat(mode)
            client.queueMessage(chat.id, text)
            chat.id
        }
    }
}

@Composable
private fun Scrim(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    )
}

@Composable
private fun AskSheet(
    onCancel: () -> Unit,
    onSubmit: suspend (mode: String, text: String) -> String,
    onSent: (chatId: String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("ask") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    fun send() {
        if (text.isBlank() || sending) return
        sending = true
        error = null
        scope.launch {
            runCatching { onSubmit(mode, text.trim()) }
                .onSuccess { onSent(it) }
                .onFailure {
                    // The question stays in the field: it was typed once and a
                    // network hiccup must not demand it be typed again.
                    sending = false
                    error = it.message ?: "Could not reach huginn"
                }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { if (!sending) onCancel() }
            .systemBarsPadding()
            .imePadding(),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 3.dp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 96.dp, start = 16.dp, end = 16.dp)
                .fillMaxWidth()
                // Consume taps so a touch inside the card never dismisses it.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
        ) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Ask huginn…") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                    maxLines = 4,
                    enabled = !sending,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSend = { send() },
                    ),
                )
                Row(
                    Modifier.padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = mode == "ask",
                        onClick = { mode = "ask" },
                        label = { Text("Ask") },
                        enabled = !sending,
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = mode == "act",
                        onClick = { mode = "act" },
                        label = { Text("Act") },
                        enabled = !sending,
                    )
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { send() }, enabled = text.isNotBlank() && !sending) {
                        Text(if (sending) "Sending…" else "Send")
                    }
                }
                // The same words the new-chat dialog uses, so the choice reads
                // the same everywhere it is offered.
                Text(
                    "Ask: reasoning and memory, no tools. Act: also reads and edits files, runs commands, fetches the web.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
