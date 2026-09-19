package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// The Android half of live typing. The RULES — the sentinel diff and the op
// merge — are in :core (ui/LiveInput.kt), shared with the desktop client; only
// this widget is Android-shaped, because it exists to give an IME something to
// talk to and nothing else has an IME.

/**
 * The invisible field that makes the soft keyboard type into tmux.
 *
 * Rendered 1dp and transparent rather than absent, because the IME needs a real
 * focused editor to talk to. Hardware keys (a folding phone's cover keyboard, a
 * paired one) arrive as key events rather than text and are mapped here too.
 */
@Composable
fun LiveKeyboardField(
    active: Boolean,
    onText: (String) -> Unit,
    onKeys: (List<String>) -> Unit,
) {
    if (!active) return
    val focus = remember { FocusRequester() }
    // ⚠⚠ THE BASELINE IS WHAT THE FIELD LAST SAID, NOT THE SENTINEL. See
    // [LiveKeyboardState]: diffing against the constant duplicated every burst
    // the IME delivered faster than a snap-back could round-trip.
    val typing = remember { LiveKeyboardState() }
    var value by remember {
        mutableStateOf(TextFieldValue(LiveInput.SENTINEL, TextRange(LiveInput.SENTINEL.length)))
    }

    LaunchedEffect(Unit) { focus.requestFocus() }

    // Housekeeping, not correctness: the buffer is put back to the sentinel once
    // the keyboard has been QUIET, so a reset can never overlap a burst. Keyed on
    // the value, so every further change restarts the wait.
    LaunchedEffect(value) {
        if (value.text == LiveInput.SENTINEL) return@LaunchedEffect
        delay(LIVE_SNAPBACK_QUIET_MS)
        value = TextFieldValue(typing.reset(), TextRange(LiveInput.SENTINEL.length))
    }

    Box(Modifier.size(1.dp)) {
        BasicTextField(
            value = value,
            onValueChange = { new ->
                // ORDERED OPS, not three ifs. An edit that begins with a newline
                // presses Return BEFORE its text — "\nls" means submit, then type
                // — and the hand-rolled order could only ever express the other
                // one, which submits the pane's draft with `ls` stuck on the end.
                for (op in typing.change(new.text).ops()) {
                    when (op) {
                        is LiveInput.Op.Text -> onText(op.text)
                        is LiveInput.Op.Key -> onKeys(op.keys)
                    }
                }
                // The sentinel is the only thing that has to go back immediately:
                // it is what the NEXT backspace deletes, and an empty field
                // reports nothing at all. Everything else waits for quiet above.
                value = if (typing.needsRunway) {
                    TextFieldValue(typing.reset(), TextRange(LiveInput.SENTINEL.length))
                } else new
            },
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // Hardware keys the diff can never see, because they produce no
                    // text: consumed here, everything printable falls through to the
                    // field and arrives as a text change.
                    val mapped = when (e.key) {
                        Key.DirectionUp -> "Up"
                        Key.DirectionDown -> "Down"
                        Key.DirectionLeft -> "Left"
                        Key.DirectionRight -> "Right"
                        Key.Escape -> "Escape"
                        Key.Tab -> "Tab"
                        Key.PageUp -> "PPage"
                        Key.PageDown -> "NPage"
                        else -> null
                    }
                    if (mapped != null) { onKeys(listOf(mapped)); true } else false
                },
            // Terminal input must arrive as typed: no autocorrect rewrites, no
            // capitalisation help, and the keyboard's own Enter key rather than an
            // action button, so multi-line pastes keep their structure.
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.None,
            ),
        )
    }
}
