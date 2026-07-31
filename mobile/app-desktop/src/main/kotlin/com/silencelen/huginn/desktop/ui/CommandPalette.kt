package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.Session

/**
 * Ctrl+K over everything: every chat, every session, and the handful of verbs
 * worth reaching without hunting.
 *
 * The thing a desktop has that a phone does not is a keyboard, and the palette is
 * what that buys — on a phone the list IS the navigation, but here the list is
 * 300px of a 1400px window and scrolling to a session by eye is the slow path.
 *
 * Ranking and filtering live in [filterPalette] so they can be asserted; this
 * file is the frame around them.
 */
@Composable
fun CommandPalette(
    chats: List<Chat>,
    sessions: List<Session>,
    onPick: (PaletteItem) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(0) }
    val all = remember(chats, sessions) { paletteItems(chats, sessions) }
    val shown = remember(all, query) { filterPalette(all, query) }
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    // A filter that shortens the list must not leave the highlight past its end.
    LaunchedEffect(shown.size) { if (selected >= shown.size) selected = 0 }
    LaunchedEffect(selected) { if (selected in shown.indices) listState.animateScrollToItem(selected) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
            // A click anywhere off the card dismisses; the card itself swallows
            // clicks so selecting text inside does not close it.
            .clickable(indication = null, interactionSource = remember { MutableInteraction() }) { onDismiss() },
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            Modifier.padding(top = 96.dp).width(620.dp)
                .clickable(indication = null, interactionSource = remember { MutableInteraction() }) {},
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
        ) {
            Column {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it; selected = 0 },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                        .focusRequester(focus)
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionDown -> {
                                    selected = stepIndex(selected, shown.size, 1); true
                                }
                                Key.DirectionUp -> {
                                    selected = stepIndex(selected, shown.size, -1); true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    shown.getOrNull(selected)?.let(onPick) ?: onDismiss(); true
                                }
                                Key.Escape -> { onDismiss(); true }
                                else -> false
                            }
                        },
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Muted("Find a chat or session, or type a verb")
                        }
                        inner()
                    },
                )

                if (shown.isEmpty()) {
                    Muted("Nothing matches.", Modifier.padding(16.dp))
                } else {
                    LazyColumn(state = listState, modifier = Modifier.heightIn(max = 360.dp)) {
                        itemsIndexed(shown) { i, item ->
                            PaletteRow(item, i == selected) { onPick(item) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteRow(item: PaletteItem, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            // Selection is a surface tint, never a left accent bar.
            .background(
                if (active) MaterialTheme.colorScheme.surfaceContainerHighest
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Muted(item.detail, maxLines = 1)
    }
}

/** Compose wants an InteractionSource for an indication-less clickable. */
private fun MutableInteraction() =
    androidx.compose.foundation.interaction.MutableInteractionSource()

/** The keyboard model, shown rather than remembered. */
@Composable
fun Cheatsheet(onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
            .clickable(indication = null, interactionSource = remember { MutableInteraction() }) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
        ) {
            Column(Modifier.padding(24.dp)) {
                Text("Keyboard", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                SHORTCUT_HELP.forEach { (keys, what) ->
                    Row(Modifier.padding(top = 8.dp)) {
                        Text(
                            keys,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(150.dp),
                        )
                        Muted(what)
                    }
                }
                Muted("Esc or F1 closes this.", Modifier.padding(top = 16.dp))
            }
        }
    }
}
