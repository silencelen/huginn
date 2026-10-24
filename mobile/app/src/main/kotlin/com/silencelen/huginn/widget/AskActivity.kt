package com.silencelen.huginn.widget

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import com.silencelen.huginn.data.lockEnabledOrLocked
import com.silencelen.huginn.notify.AppLock
import com.silencelen.huginn.notify.SessionWatchWorker
import com.silencelen.huginn.ui.theme.HuginnTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.silencelen.huginn.ui.theme.huginnFilterChipColors

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

    /**
     * Whether the lock is up on this sheet. Compose reads it; the lifecycle below
     * writes it, as MainActivity's own does.
     *
     * ⚠ NOT A `remember` IN THE COMPOSITION, AND NOT DECIDED ONCE. It was both:
     * the lock was weighed in onCreate and the answer kept in composition. But a
     * sheet left open behind Home is not created again — tapping the bar later
     * brings the same activity forward, and onCreate does not run — so a sheet
     * unlocked once stayed unlocked however long it had been away, while the
     * app's front door had locked a minute after it was left. Whoever held the
     * phone then had free text to Claude, Act mode included. The lock is now
     * decided every time the sheet comes to the foreground ([onStart]) by the
     * rule the front door uses, the grace is stamped when it leaves ([onStop]),
     * and Send checks again ([submit]).
     */
    private val locked = mutableStateOf(AppLock.lockedNow)

    /** The setting as read at creation; [onStart] says why the cache joins it. */
    private var lockEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The same blocking read MainActivity makes at creation, for the same
        // reason: the lock decision cannot wait on DataStore.
        lockEnabled = runBlocking {
            lockEnabledOrLocked { SettingsStore(applicationContext).appLock.first() }
        }
        if (lockEnabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }

        setContent {
            HuginnTheme {
                // ⚠ THE QUESTION OUTLIVES THE LOCK. A lock that comes back takes
                // the composer out of composition, and what was typed would go
                // with it; held here, outside the branch, the draft is there again
                // after the unlock, the way MainActivity keeps its place.
                val held = rememberSaveableStateHolder()
                if (locked.value) {
                    // Scrim only while the system sheet decides; the composer
                    // must not be drawn — or focused, or filled — behind it.
                    Scrim(onDismiss = { finish() })
                    LaunchedEffect(Unit) {
                        AppLock.authenticate(this@AskActivity) { ok, _ ->
                            if (ok) {
                                AppLock.lockedNow = false
                                locked.value = false
                            } else {
                                finish()
                            }
                        }
                    }
                } else {
                    held.SaveableStateProvider(ASK_STATE_KEY) {
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
    }

    override fun onStart() {
        super.onStart()
        // The setting as read at creation, or the app's cache of it: a lock
        // turned on in the app while this sheet waited behind Home is seen here,
        // and one turned off leaves this sheet asking until it closes, the safe
        // way round.
        val enabled = lockEnabled || AppLock.enabledCache
        if (askSheetLocks(enabled, AppLock.lockedNow, AppLock.lastAwayAt, SystemClock.elapsedRealtime())) {
            AppLock.lockedNow = true
            locked.value = true
        }
    }

    override fun onStop() {
        super.onStop()
        // As MainActivity: recorded only while unlocked, so a lock left up in the
        // background never refreshes its own grace and lets the sheet back in.
        if (!locked.value) AppLock.lastAwayAt = SystemClock.elapsedRealtime()
    }

    /**
     * The chat this sheet has already created, kept across retries.
     *
     * ⚠ ONE QUESTION, ONE CHAT. createChat and queueMessage ran under one 25 s
     * budget with no idempotency key and no cleanup, so any failure strictly
     * BETWEEN them left a chat on the host while the sheet showed an error and
     * kept the text — and every retry minted another. The orphans render as
     * "Untitled" rows, survive restarts and are never swept. A daemon restart, a
     * tunnel flap, the budget expiring after createChat returned, or a refusal of
     * the message all do it; 429 "too many concurrent runs (3)" does it
     * deterministically.
     */
    private val attempt = AskAttempt()

    /**
     * Creates the chat and hands it the question. Returns the chat to open.
     *
     * ⚠ NOT WHILE THE LOCK IS UP, checked here as well as at the door: before the
     * chat is created and again right before the question is queued. A press
     * that reaches this past a lock that has just come back is refused, and the
     * sheet shows the unlock instead.
     */
    private suspend fun submit(mode: String, text: String): String {
        requireUnlocked()
        val settings = SettingsStore(applicationContext)
        val bearer = settings.token.first()
        if (bearer.isBlank()) throw IllegalStateException("Not signed in — open Huginn first")
        val base = settings.baseUrl.first()
        val client = HuginnClient({ base }, { bearer })
        return withTimeout(25_000) {
            attempt.submit(
                create = { client.createChat(mode).id },
                queue = { id ->
                    requireUnlocked()
                    client.queueMessage(id, text)
                },
                discard = { id -> client.deleteChat(id) },
            )
        }
    }

    /** Throws, and puts the lock up on this sheet, unless [askMaySend] says Send may go. */
    private fun requireUnlocked() {
        if (askMaySend(locked.value, AppLock.lockedNow)) return
        locked.value = true
        throw IllegalStateException("Huginn is locked")
    }
}

/** Where the composer's draft is held while the lock is up. */
private const val ASK_STATE_KEY = "ask"

/**
 * Whether the ask sheet comes to the foreground locked: [AppLock.lockedNow], or
 * the grace rule MainActivity applies at its own door — which also locks a
 * process with no memory of an unlock (`awayAt == 0`) and a clock that went
 * back. Asked every time the sheet comes to the foreground, not once at its
 * creation: a sheet brought back from behind Home is not created again.
 *
 * Pure, so the decision is asserted without a device; [AskActivity] feeds it.
 */
internal fun askSheetLocks(lockEnabled: Boolean, lockedNow: Boolean, awayAt: Long, now: Long): Boolean =
    lockedNow || AppLock.shouldLock(lockEnabled, awayAt, now)

/**
 * Whether Send may go to the host: never while the lock is up, on this sheet or
 * on the app. The grace is not weighed here — it is a question about coming
 * back, and a sheet in the foreground has not been away.
 */
internal fun askMaySend(sheetLocked: Boolean, lockedNow: Boolean): Boolean = !sheetLocked && !lockedNow

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
    // Saveable, so the holder in AskActivity can keep them across the lock.
    var text by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("ask") }
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
                        colors = huginnFilterChipColors(),
                        selected = mode == "ask",
                        onClick = { mode = "ask" },
                        label = { Text("Ask") },
                        enabled = !sending,
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        colors = huginnFilterChipColors(),
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
                    "Ask: reads files and the web, and changes nothing. Act: also edits files and runs commands.",
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

/**
 * One question from the ask sheet, across however many presses it takes.
 *
 * ⚠ THE CHAT IS REMEMBERED. A retry re-runs only the send, so three failed
 * presses leave one chat on the host rather than three.
 *
 * ⚠ AND A FRESH ONE IS TAKEN BACK. When the send fails on the TRANSPORT — no
 * answer at all — the chat this attempt just minted is deleted best-effort and
 * forgotten, so a retry starts clean. A refusal from the daemon (a
 * HuginnException: 429 "too many concurrent runs", a validation error) is the
 * opposite case: the host has the chat and said no to the message, so the chat
 * is kept and the retry re-sends into it.
 *
 * A send cut off by the sheet's own timeout (a cancellation) is not a transport
 * failure either: it may well have landed, so the chat is kept as for a refusal.
 *
 * Residual, and it needs a daemon-side idempotency key to close properly: a
 * send that LANDED and whose answer was lost still looks like a failure here,
 * and the retry then queues a second turn into the same chat rather than
 * starting a second run on a second one — which is the better of the two.
 */
internal class AskAttempt {

    /** The chat created for this question, if one has been. */
    var chatId: String? = null
        private set

    suspend fun submit(
        create: suspend () -> String,
        queue: suspend (String) -> Unit,
        discard: suspend (String) -> Unit,
    ): String {
        val fresh = chatId == null
        val id = chatId ?: create().also { chatId = it }
        try {
            queue(id)
        } catch (e: Throwable) {
            // ⚠ NOT ON A TIMEOUT. 2026-10-02: the sheet's 25 s withTimeout fired
            // mid-send, the TimeoutCancellationException took this branch, the
            // suspending discard threw at once inside the cancelled scope (and
            // runCatching hid it), and the chat was forgotten anyway: neither
            // deleted nor reused, so the retry minted a second chat and asked the
            // same question twice. A cut-off send is the one that may have
            // LANDED, so the chat is kept and the retry re-sends into it.
            if (fresh && e !is HuginnClient.HuginnException && e !is CancellationException) {
                runCatching { discard(id) }
                chatId = null
            }
            throw e
        }
        return id
    }
}
