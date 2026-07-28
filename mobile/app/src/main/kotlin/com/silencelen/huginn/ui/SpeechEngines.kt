package com.silencelen.huginn.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/**
 * The two Android speech engines behind one small surface. Both are created
 * lazily and every call is runCatching'd: a missing recognizer service or a TTS
 * engine mid-initialisation must degrade, never crash the sheet.
 */
class SpeechEngines(private val context: Context) {
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
