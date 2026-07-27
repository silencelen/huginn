package com.silencelen.huginn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silencelen.huginn.data.Screen

/**
 * A read-and-poke view of one tmux pane: the rendered screen from capture-pane,
 * a key row for the things you cannot type on a phone keyboard, and a text field
 * that sends a whole line at once.
 *
 * It is deliberately NOT a terminal emulator. There is no PTY here, so there is
 * no cursor to place and no scrollback to page. What it does cover is the actual
 * job on a phone: read what Claude is asking, answer it, approve a tool, hit Esc.
 */
@Composable
fun TerminalScreen(
    session: String,
    screen: Screen?,
    onSendText: (String, Boolean) -> Unit,
    onSendKeys: (List<String>) -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            if (screen == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            } else {
                ScreenBody(screen)
            }
        }

        KeyRow(onSendKeys)

        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type into $session") },
                    maxLines = 5,
                    shape = RoundedCornerShape(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                // Send-without-newline exists because Claude Code's composer takes
                // multi-line input: you often want the text in the box, not submitted.
                IconButton(
                    onClick = { if (draft.isNotEmpty()) { onSendText(draft, false); draft = "" } },
                    enabled = draft.isNotEmpty(),
                ) {
                    Text("↦", style = MaterialTheme.typography.titleLarge)
                }
                IconButton(
                    onClick = {
                        if (draft.isNotEmpty()) { onSendText(draft, true); draft = "" } else onSendKeys(listOf("Enter"))
                    },
                    modifier = Modifier
                        .size(46.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(23.dp)),
                ) {
                    Icon(
                        Icons.Filled.KeyboardReturn,
                        contentDescription = "Send and press Enter",
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

/**
 * Sizes the monospace text so the pane's full column count fits the phone width,
 * clamped to a legible range; anything wider than the clamp scrolls sideways
 * instead of shrinking into unreadability.
 */
@Composable
private fun ScreenBody(screen: Screen) {
    val fg = MaterialTheme.colorScheme.onSurface
    val bg = MaterialTheme.colorScheme.background
    val hScroll = rememberScrollState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cols = screen.width.coerceAtLeast(20)
        // Monospace advance is ~0.6 em across the platform mono faces.
        val raw = (maxWidth.value / cols) / 0.6f
        val fontSize = raw.coerceIn(6.5f, 13f).sp
        val lineHeight = (fontSize.value * 1.22f).sp

        Column(
            Modifier
                .fillMaxSize()
                .horizontalScroll(hScroll)
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            screen.lines.forEach { line ->
                Text(
                    Ansi.render(line, defaultFg = fg, defaultBg = bg),
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    softWrap = false,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The keys a phone keyboard cannot send. Shift+Tab is here because it is how
 * Claude Code cycles permission modes, and Esc because it is how you interrupt.
 */
@Composable
private fun KeyRow(onSendKeys: (List<String>) -> Unit) {
    val hScroll = rememberScrollState()
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(hScroll)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyChip("Esc") { onSendKeys(listOf("Escape")) }
            KeyChip("⇧Tab") { onSendKeys(listOf("BTab")) }
            KeyChip("Tab") { onSendKeys(listOf("Tab")) }
            IconKeyChip(Icons.Filled.ArrowUpward, "Up") { onSendKeys(listOf("Up")) }
            IconKeyChip(Icons.Filled.ArrowDownward, "Down") { onSendKeys(listOf("Down")) }
            IconKeyChip(Icons.Filled.KeyboardArrowLeft, "Left") { onSendKeys(listOf("Left")) }
            IconKeyChip(Icons.Filled.KeyboardArrowRight, "Right") { onSendKeys(listOf("Right")) }
            KeyChip("^C") { onSendKeys(listOf("C-c")) }
            KeyChip("^D") { onSendKeys(listOf("C-d")) }
            KeyChip("^L") { onSendKeys(listOf("C-l")) }
            KeyChip("^R") { onSendKeys(listOf("C-r")) }
            KeyChip("PgUp") { onSendKeys(listOf("PPage")) }
            KeyChip("PgDn") { onSendKeys(listOf("NPage")) }
        }
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
        colors = AssistChipDefaults.assistChipColors(
            labelColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun IconKeyChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = { Icon(icon, contentDescription = desc, modifier = Modifier.size(16.dp)) },
    )
}
