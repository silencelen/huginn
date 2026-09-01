package com.silencelen.huginn.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Hands-free conversation with a chat: listen, send, hear the answer, listen
 * again. The loop's decisions are [VoiceLoop] (pure, tested); this file is the
 * machinery around it — a SpeechRecognizer, a TextToSpeech engine, and a sheet
 * that shows which of the three things is happening.
 *
 * Everything engine-side is wrapped in runCatching with a path to Idle: both
 * engines are per-OEM quicksand, and voice mode failing must degrade to "use
 * the keyboard", never to a stuck sheet.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun VoiceSheet(
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    sending: Boolean,
    streamingText: String?,
    lastAnswer: String?,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var state by remember { mutableStateOf<VoiceLoop.State>(VoiceLoop.State.Idle) }
    var spokenFor by remember { mutableStateOf<String?>(null) }
    var micNote by remember { mutableStateOf<String?>(null) }

    val currentOnSend = rememberUpdatedState(onSend)
    // Read fresh inside effects that may run from a callback registered an earlier
    // composition ago (the recognizer's onFinal, the send). The turn's baseline is
    // captured off this the moment the message is sent.
    val currentLastAnswer = rememberUpdatedState(lastAnswer)

    // ---- engines, created once per sheet, torn down with it ----
    val engines = remember { SpeechEngines(context) }
    DisposableEffect(Unit) { onDispose { engines.destroy() } }

    fun apply(step: VoiceLoop.Step) {
        state = step.state
        for (e in step.effects) when (e) {
            VoiceLoop.Effect.StartListening -> engines.listen(
                onPartial = { p -> state = VoiceLoop.reduce(state, VoiceLoop.Event.Partial(p)).state },
                onFinal = { heard -> apply(VoiceLoop.reduce(state, VoiceLoop.Event.Heard(heard))) },
                onError = { transient ->
                    // A hard mic failure must SAY so on the sheet: the silent
                    // version of this is a button that appears to do nothing.
                    if (!transient) micNote = "The microphone or speech service is not available. " +
                        "Check the mic permission, or use the dictation mic instead."
                    apply(VoiceLoop.reduce(state, VoiceLoop.Event.MicFailed(transient)))
                },
            )
            VoiceLoop.Effect.StopListening -> engines.stopListening()
            is VoiceLoop.Effect.SendMessage -> {
                // Record the answer that already exists BEFORE this turn produces
                // one — it belongs to the prior turn. Without this the boundary
                // effect below fired on the prior reply the instant we entered
                // Thinking (before `sending` flipped true) and spoke it aloud, one
                // behind, on the first turn of any chat with history.
                spokenFor = VoiceLoop.baselineForTurn(currentLastAnswer.value)
                currentOnSend.value(e.text)
            }
            is VoiceLoop.Effect.Speak -> engines.speak(Speakable.render(e.text)) {
                apply(VoiceLoop.reduce(state, VoiceLoop.Event.SpokenAll))
            }
            VoiceLoop.Effect.StopSpeaking -> engines.stopSpeaking()
            VoiceLoop.Effect.Close -> onDismiss()
        }
    }

    // Begin listening the moment the sheet opens — or the moment the mic
    // permission lands, when the sheet had to ask for it first. Either way the
    // sheet is ALREADY VISIBLE while any of this happens: the permission flow
    // living behind an unopened sheet is how a tap came to do nothing at all.
    LaunchedEffect(micGranted) {
        if (micGranted && state == VoiceLoop.State.Idle) {
            apply(VoiceLoop.reduce(VoiceLoop.State.Idle, VoiceLoop.Event.Tap))
        }
    }

    // The turn boundary: thinking, nothing in flight any more, and an answer that
    // is genuinely THIS turn's — the decision (and the one-behind guard) is the
    // pure VoiceLoop.answerToSpeak, seeded by the baseline captured at send.
    LaunchedEffect(sending, streamingText, lastAnswer, state) {
        val toSpeak = VoiceLoop.answerToSpeak(
            state = state,
            current = lastAnswer,
            alreadySpoken = spokenFor,
            sending = sending,
            streaming = streamingText,
        )
        if (toSpeak != null) {
            spokenFor = toSpeak
            apply(VoiceLoop.reduce(state, VoiceLoop.Event.AnswerReady(toSpeak)))
        }
    }

    ModalBottomSheet(onDismissRequest = { engines.destroy(); onDismiss() }) {
        if (!micGranted) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.MicOff, contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Voice mode needs the microphone",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    "If no dialog appears after tapping Grant, the system is blocking " +
                        "the request — enable Microphone under App info → Permissions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
                androidx.compose.material3.Button(
                    onClick = onRequestMic,
                    modifier = Modifier.padding(top = 14.dp),
                ) { Text("Grant microphone access") }
            }
            return@ModalBottomSheet
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp)
                .clickable { apply(VoiceLoop.reduce(state, VoiceLoop.Event.Tap)) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val (icon, label, active) = when (val st = state) {
                is VoiceLoop.State.Listening -> Triple(Icons.Filled.Mic, "Listening…", true)
                VoiceLoop.State.Thinking -> Triple(Icons.Filled.MicOff, "Thinking…", false)
                VoiceLoop.State.Speaking -> Triple(Icons.Filled.VolumeUp, "Tap to interrupt", true)
                VoiceLoop.State.Idle -> Triple(
                    Icons.Filled.Mic,
                    if (micNote != null) "Voice unavailable" else "Tap to talk",
                    false,
                )
            }
            Box(
                Modifier
                    .size(84.dp)
                    .background(
                        if (active) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (state is VoiceLoop.State.Listening) {
                    PulsingDot(MaterialTheme.colorScheme.primary, Modifier.size(84.dp).alpha(0.25f))
                }
                Icon(
                    icon, contentDescription = label,
                    modifier = Modifier.size(34.dp),
                    tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            val body = when (val st = state) {
                is VoiceLoop.State.Listening -> st.partial.ifBlank { " " }
                VoiceLoop.State.Thinking -> streamingText ?: " "
                VoiceLoop.State.Speaking -> lastAnswer ?: " "
                VoiceLoop.State.Idle -> micNote ?: " "
            }
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 10.dp),
            )
        }
    }
}

