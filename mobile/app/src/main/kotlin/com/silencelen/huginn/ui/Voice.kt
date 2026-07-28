package com.silencelen.huginn.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * Speech-to-text for the composers.
 *
 * The system recognizer rather than a streaming in-app one, on purpose. A
 * SpeechRecognizer session needs the RECORD_AUDIO permission, an availability
 * dance, and its own UI for partials — and this app cannot be tested on a device,
 * so every one of those is a place to be silently wrong. The recognizer activity
 * is the platform's own dialog: the permission is its problem, the UI is its
 * problem, and the result comes back as plain text or not at all.
 *
 * Returns null when no recognizer exists on the device, so callers show no mic
 * rather than a button that cannot work.
 */
@Composable
fun rememberSpeechInput(onText: (String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    // The freshest callback, not the one captured when the launcher was created:
    // the composer's lambda closes over the current draft, which changes.
    val currentOnText = rememberUpdatedState(onText)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val heard = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
        if (!heard.isNullOrEmpty()) currentOnText.value(heard)
    }

    val available = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .resolveActivity(context.packageManager) != null
    }
    if (!available) return null

    return {
        runCatching {
            launcher.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to huginn")
                }
            )
        }
    }
}

/**
 * Appends dictation to a draft the way a person would have typed it: a space
 * between utterances, none at the start. Pure so the joining rule is testable —
 * "Hello" + "world" must become "Hello world", not "Helloworld" or " Hello world".
 */
fun appendDictation(draft: String, heard: String): String {
    val t = heard.trim()
    if (t.isEmpty()) return draft
    return if (draft.isBlank()) t else draft.trimEnd() + " " + t
}
