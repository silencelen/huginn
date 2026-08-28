package com.silencelen.huginn.desktop.attach

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.AwtWindow
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.Scratchpad
import com.silencelen.huginn.ui.AttachChooser
import com.silencelen.huginn.ui.AttachChooserItems
import com.silencelen.huginn.ui.AttachRow
import com.silencelen.huginn.ui.ScratchpadPickerItems
import kotlinx.coroutines.CoroutineScope
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * The composer's attachment affordances: the chip, the clip button, the drop
 * target and the file picker.
 *
 * Kept out of the two composers so the chat and the session pane cannot drift
 * into two different-looking answers to the same question, which is exactly what
 * happened between the phone and the Electron client for a year.
 */

/** One [AttachmentController] per composer, cancelled when the composer goes away. */
@Composable
fun rememberAttachmentController(
    client: HuginnClient,
    scope: CoroutineScope,
    key: Any?,
): AttachmentController {
    val controller = remember(key) { AttachmentController(client, scope) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    return controller
}

/**
 * What is attached, as one line above the text box.
 *
 * Status is a suffix and a tint rather than a badge or a spinner — house rule, and
 * the state that matters (uploading vs ready) is legible from the ellipsis alone.
 */
@Composable
fun AttachChip(attachment: ComposerAttachment, onRemove: () -> Unit) {
    val failed = attachment.status == AttachStatus.FAILED
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (failed) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .padding(start = 10.dp, top = 2.dp, bottom = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A MATERIAL ICON, not the 📷/📎 the phone uses. Emoji in this chip render
        // as a tofu box on a machine with no emoji font — verified on this one — and
        // the desktop already ships these glyphs in its own icon font. The marker
        // text keeps the emoji, because that is `:core`'s shared wording and it is
        // rendered by whatever is reading the message, not by this window.
        Icon(
            if (attachment.image) Icons.Filled.Image else Icons.Filled.AttachFile,
            contentDescription = null,
            modifier = Modifier.padding(end = 6.dp).size(15.dp),
            tint = if (failed) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            buildString {
                append(attachment.label)
                when (attachment.status) {
                    AttachStatus.UPLOADING -> append('…')
                    AttachStatus.FAILED -> append(" — failed")
                    AttachStatus.READY -> Unit
                }
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        IconButton(onClick = onRemove, modifier = Modifier.padding(start = 2.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove attachment",
                modifier = Modifier.padding(2.dp),
            )
        }
    }
}

/**
 * The clip button, and the choice behind it. A TextButton so it sits on the
 * composer's baseline with Send.
 *
 * Two menus on ONE anchor, opened in sequence: the chooser ("Local file" /
 * "Notes page"), and then — if the page row is taken — the pages themselves. A
 * submenu proper would need a second anchor a few pixels off this one; hopping
 * the same popup between two lists keeps the whole interaction under the button
 * that was pressed.
 *
 * With pages unavailable there is only one row, and the button goes STRAIGHT to
 * the file dialog exactly as it did before this menu existed — [AttachChooser.direct].
 *
 * @param pads already gated by the caller's feature probe: empty means the daemon
 *   has no pages route, which is the only form this control sees that fact in.
 */
@Composable
fun AttachButton(
    enabled: Boolean = true,
    pads: List<Scratchpad> = emptyList(),
    padRefId: String? = null,
    onPadRef: (String?) -> Unit = {},
    onPickFile: () -> Unit,
) {
    var chooser by remember { mutableStateOf(false) }
    var pagePicker by remember { mutableStateOf(false) }

    val rows = AttachChooser.rows(
        own = listOf(
            AttachRow("local-file", "Local file", Icons.Outlined.AttachFile, onPickFile)
        ),
        padsAvailable = pads.isNotEmpty(),
        onNotesPage = { pagePicker = true },
    )
    val sole = AttachChooser.direct(rows)

    Box {
        TextButton(
            onClick = { if (sole != null) sole.onPick() else chooser = true },
            enabled = enabled,
        ) {
            Icon(
                Icons.Filled.AttachFile,
                contentDescription = if (sole != null) "Attach a file" else "Attach",
            )
        }
        DropdownMenu(expanded = chooser, onDismissRequest = { chooser = false }) {
            AttachChooserItems(rows) { chooser = false; it.onPick() }
        }
        DropdownMenu(expanded = pagePicker, onDismissRequest = { pagePicker = false }) {
            ScratchpadPickerItems(pads, padRefId) { pagePicker = false; onPadRef(it) }
        }
    }
}

/**
 * The native file chooser, shown while [visible] is true.
 *
 * `AwtWindow` rather than a Compose dialog: the OS picker is the one the owner
 * already knows, it can reach the places a JVM-drawn list would have to be taught
 * about (recent, bookmarks, network mounts), and `FileDialog` is what Compose
 * Desktop's own samples use. [onResult] is called with null on cancel.
 */
@Composable
fun AttachFilePicker(visible: Boolean, onResult: (File?) -> Unit) {
    if (!visible) return
    val callback by rememberUpdatedState(onResult)
    AwtWindow(
        create = {
            object : FileDialog(null as Frame?, "Attach a file", LOAD) {
                override fun setVisible(value: Boolean) {
                    super.setVisible(value)
                    // setVisible(true) BLOCKS until the dialog closes, so this runs
                    // once the user has chosen — the return path a modal AWT dialog
                    // gives you, and the reason there is no listener here.
                    if (value) {
                        val dir = directory
                        val chosen = file
                        callback(if (dir != null && chosen != null) File(dir, chosen) else null)
                    }
                }
            }
        },
        dispose = FileDialog::dispose,
    )
}

/**
 * Accepts files (and, failing that, text) dropped anywhere on the composer.
 *
 * The awt transferable rather than Compose's `DragData`: the fallback ladder in
 * [AwtTransfer] is the same one a clipboard paste needs, and one ladder that is
 * exercised by both paths is one ladder that gets fixed when a desktop turns out
 * to offer something unexpected.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun Modifier.attachmentDropTarget(
    controller: AttachmentController,
    onText: (String) -> Unit,
    onDragOver: (Boolean) -> Unit,
): Modifier {
    // rememberUpdatedState, because the target object is remembered across
    // recompositions and would otherwise keep calling the FIRST composition's
    // lambdas — which close over the first frame's draft text.
    val text by rememberUpdatedState(onText)
    val over by rememberUpdatedState(onDragOver)
    val target = remember(controller) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                over(false)
                val t = runCatching { event.awtTransferable }.getOrNull()
                return AwtTransfer.consume(t, controller, text)
            }

            override fun onEntered(event: DragAndDropEvent) = over(true)
            override fun onExited(event: DragAndDropEvent) = over(false)
            override fun onEnded(event: DragAndDropEvent) = over(false)
        }
    }
    return this.dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target)
}
