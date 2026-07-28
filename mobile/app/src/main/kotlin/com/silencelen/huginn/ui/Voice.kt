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
 * Always returns a launcher: hiding the button behind an availability
 * pre-check turned out to hide it on phones where dictation worked, because
 * resolveActivity answers about package VISIBILITY, not existence. A launch
 * that truly cannot work toasts instead.
 */
@Composable
fun rememberSpeechInput(onText: (String) -> Unit): () -> Unit {
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

    // No availability pre-check, deliberately. resolveActivity is subject to
    // package-visibility filtering (Android 11+), and a null there made this
    // button REMOVE ITSELF on a phone where the recognizer worked fine — a
    // silent gate, the worst kind. The <queries> declaration fixes the check,
    // but the button no longer trusts any pre-check: it always shows, and a
    // launch that genuinely cannot work says so out loud.
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
        }.onFailure {
            android.widget.Toast.makeText(
                context,
                "No speech input app is available on this phone.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
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
