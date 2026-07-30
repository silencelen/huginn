package com.silencelen.huginn.ui

import android.speech.SpeechRecognizer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * The dictation mic, rebuilt on the path modern phones actually ship.
 *
 * The original opened the system speech dialog (RecognizerIntent) — and on
 * current One UI / Google builds no activity handles that intent any more, so
 * the button died with "No speech input app is available" on a phone whose
 * keyboard dictates fine. Speech input today is a SERVICE (SpeechRecognizer),
 * the same engine voice mode uses; this button hosts its own small listening
 * dialog on that path, keeping the old system dialog only as a fallback for
 * devices where it still exists.
 *
 * The service path needs the microphone permission (the system dialog carried
 * its own); the ask is routed through the same flow voice mode uses, and the
 * tap that triggered it starts listening the moment the grant lands.
 */
@Composable
fun DictationMicButton(
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    onText: (String) -> Unit,
) {
    val context = LocalContext.current
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    /**
     * When this tap asked for the microphone, or 0 for "it did not".
     *
     * A time rather than a flag because a DENIAL produces no event here — the flag
     * simply stayed true, and the next time the mic was granted for any other
     * reason (the voice sheet in the same composer, or Settings) dictation opened
     * by itself, on top of whatever that grant was actually for. An intent to
     * dictate belongs to the tap that expressed it, and a permission dialog is
     * answered in seconds, so it expires.
     */
    var askedAt by remember { mutableStateOf(0L) }

    val currentOnText = rememberUpdatedState(onText)
    val engines = remember { SpeechEngines(context) }
    DisposableEffect(Unit) { onDispose { engines.destroy() } }

    // The system-dialog fallback, for the devices that still have one.
    val legacyDialog = rememberSpeechInput { heard -> currentOnText.value(heard) }

    fun start() {
        partial = ""
        failure = null
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            // No service on this device: the old dialog is the only hope left.
            // Its own failure toast covers the phone that has neither.
            legacyDialog()
            return
        }
        listening = true
        engines.listen(
            onPartial = { partial = it },
            onFinal = { heard ->
                listening = false
                if (heard.isNotBlank()) currentOnText.value(heard)
            },
            onError = { transient ->
                if (transient) {
                    // Silence: the user is composing a thought. Keep listening.
                    engines.listen(
                        onPartial = { partial = it },
                        onFinal = { heard ->
                            listening = false
                            if (heard.isNotBlank()) currentOnText.value(heard)
                        },
                        onError = { again ->
                            if (!again) failure = "Speech recognition failed. " +
                                "Check the microphone permission under App info."
                            else listening = false
                        },
                    )
                } else {
                    failure = "Speech recognition is not working on this phone. " +
                        "Check the microphone permission under App info."
                }
            },
        )
    }

    // A tap that had to ask for the permission gets its dictation the moment
    // the grant lands — not on a second tap that makes the first look dead.
    LaunchedEffect(micGranted) {
        val asked = askedAt
        askedAt = 0L
        if (micGranted && asked != 0L &&
            android.os.SystemClock.elapsedRealtime() - asked < GRANT_WINDOW_MS
        ) {
            start()
        }
    }

    IconButton(
        onClick = {
            if (micGranted) start()
            else { askedAt = android.os.SystemClock.elapsedRealtime(); onRequestMic() }
        },
        modifier = Modifier.size(46.dp),
    ) {
        Icon(
            Icons.Filled.Mic,
            contentDescription = "Dictate",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (listening || failure != null) {
        AlertDialog(
            onDismissRequest = {
                engines.stopListening()
                listening = false
                failure = null
            },
            title = { Text(if (failure != null) "Dictation" else "Listening…") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (failure != null) {
                        Text(
                            failure!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        PulsingDot(MaterialTheme.colorScheme.primary, Modifier.size(14.dp))
                        Text(
                            partial.ifBlank { "Say it — it lands in the message box." },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    engines.stopListening()
                    listening = false
                    failure = null
                }) { Text(if (failure != null) "Close" else "Cancel") }
            },
        )
    }
}

/** How long a tap's intent to dictate outlives the permission request it started. */
private const val GRANT_WINDOW_MS = 30_000L
