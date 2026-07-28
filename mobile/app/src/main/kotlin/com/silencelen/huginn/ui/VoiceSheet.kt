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

    // ---- engines, created once per sheet, torn down with it ----
    val engines = remember { VoiceEngines(context) }
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
            is VoiceLoop.Effect.SendMessage -> currentOnSend.value(e.text)
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

    // The turn boundary: thinking, nothing in flight any more, and an answer
    // text that is not the one already spoken.
    LaunchedEffect(sending, streamingText, lastAnswer, state) {
        if (state == VoiceLoop.State.Thinking && !sending && streamingText == null &&
            !lastAnswer.isNullOrBlank() && lastAnswer != spokenFor
        ) {
            spokenFor = lastAnswer
            apply(VoiceLoop.reduce(state, VoiceLoop.Event.AnswerReady(lastAnswer)))
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

/**
 * The two Android speech engines behind one small surface. Both are created
 * lazily and every call is runCatching'd: a missing recognizer service or a TTS
 * engine mid-initialisation must degrade, never crash the sheet.
 */
private class VoiceEngines(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak: Pair<String, () -> Unit>? = null

    fun listen(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (Boolean) -> Unit) {
        runCatching {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) { onError(false); return }
            val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
            r.setRecognitionListener(object : RecognitionListener {
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.let(onPartial)
                }

                override fun onResults(results: Bundle?) {
                    onFinal(
                        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty()
                    )
                }

                override fun onError(error: Int) {
                    // Silence and no-match are the user thinking; retry those.
                    val transient = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                        error == SpeechRecognizer.ERROR_NO_MATCH
                    onError(transient)
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
            )
        }.onFailure { onError(false) }
    }

    fun stopListening() {
        runCatching { recognizer?.cancel() }
    }

    fun speak(text: String, onDone: () -> Unit) {
        runCatching {
            val engine = tts
            if (engine == null) {
                pendingSpeak = text to onDone
                tts = TextToSpeech(context) { status ->
                    ttsReady = status == TextToSpeech.SUCCESS
                    val p = pendingSpeak; pendingSpeak = null
                    if (p != null) {
                        if (ttsReady) speakNow(p.first, p.second)
                        // No TTS engine at all: skip straight past speaking so the
                        // loop continues silently rather than hanging forever.
                        else p.second()
                    }
                }
                return
            }
            if (ttsReady) speakNow(text, onDone) else onDone()
        }.onFailure { onDone() }
    }

    private fun speakNow(text: String, onDone: () -> Unit) {
        val engine = tts ?: return onDone()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onDone(utteranceId: String?) {
                android.os.Handler(context.mainLooper).post { onDone() }
            }

            override fun onError(utteranceId: String?) {
                android.os.Handler(context.mainLooper).post { onDone() }
            }

            override fun onStart(utteranceId: String?) = Unit
        })
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "huginn-voice")
    }

    fun stopSpeaking() {
        runCatching { tts?.stop() }
    }

    fun destroy() {
        runCatching { recognizer?.destroy() }; recognizer = null
        runCatching { tts?.shutdown() }; tts = null; ttsReady = false
    }
}
