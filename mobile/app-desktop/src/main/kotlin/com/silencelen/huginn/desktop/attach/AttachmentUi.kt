package com.silencelen.huginn.desktop.attach

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.AwtWindow
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.Scratchpad
import com.silencelen.huginn.desktop.Composer
import com.silencelen.huginn.desktop.ComposerLayout
import com.silencelen.huginn.ui.AttachChooser
import com.silencelen.huginn.ui.AttachChooserItems
import com.silencelen.huginn.ui.AttachRow
import com.silencelen.huginn.ui.ScratchpadPickerItems
import kotlinx.coroutines.CoroutineScope
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * The composer's attachment affordances: the clip button, the drop target and
 * the file picker. The CHIPS are `:ui`'s `AttachChipRow` — the phone draws the
 * same ones, which is the whole reason they are not here any more.
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
 * The clip button, and the choice behind it. A TextButton at desk widths, so it
 * sits on the composer's baseline with a labelled Send.
 *
 * ⚠ AND AN ICON BUTTON UNDER THE BREAKPOINT, WHICH IS NOT COSMETIC. THE OWNER
 * REPORTED THE TEXTBUTTON-IN-BOTH-SHAPES VERSION: *"the send button in desktop,
 * when shrunk to its icon due to scaling, is not aligned properly with the
 * attachment icon to its left, it should be centered vertically on the same
 * horizontal plane as the attachment button."*
 *
 * A clickable M3 `Surface` — which is what a `TextButton` is — carries
 * `minimumInteractiveComponentSize()`, so this reported a 48dp box with its glyph
 * centred at 24dp from the top of the control line, while Send and Interrupt were
 * [Composer.CONTROL_DP] icon buttons sitting at the TOP of that line with their
 * glyphs at 16dp. Measured on a 560px window: 8px of disagreement, on the one
 * line whose whole job is to be three symbols in a row.
 *
 * Centring the row would have hidden that rather than fixed it — a 58dp-wide
 * labelled button beside two 32dp squares is still two different controls, and it
 * spends horizontal space on the window shape that had none. So the compact form
 * is built from the SAME constant the other two are, which is what makes one
 * vertical centre a property of the line rather than a coincidence of alignment.
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
 * @param layout which shape the composer is in, handed down by `ComposerFrame`
 *   exactly as it is to `ComposerAction`. Defaulted to [ComposerLayout.FULL] so a
 *   caller outside a composer gets the labelled button it always had.
 * @param pads already gated by the caller's feature probe: empty means the daemon
 *   has no pages route, which is the only form this control sees that fact in.
 */
@Composable
fun AttachButton(
    layout: ComposerLayout = ComposerLayout.FULL,
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
    val label = if (sole != null) "Attach files" else "Attach"
    val open = { if (sole != null) sole.onPick() else chooser = true }

    Box {
        if (layout == ComposerLayout.COMPACT) {
            IconButton(
                onClick = open,
                enabled = enabled,
                modifier = Modifier.size(Composer.CONTROL_DP.dp),
                // The clip keeps the PRIMARY tint it has in the labelled form. An
                // `IconButton` defaults to `LocalContentColor`, so the compact clip
                // came out grey on the first build of this — a control that changes
                // COLOUR as the window narrows reads as a different control, and the
                // complaint being fixed here was already that the two shapes of one
                // line did not look like one line.
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(
                    Icons.Filled.AttachFile,
                    contentDescription = label,
                    modifier = Modifier.size(Composer.CONTROL_GLYPH_DP.dp),
                )
            }
        } else {
            TextButton(onClick = open, enabled = enabled) {
                Icon(Icons.Filled.AttachFile, contentDescription = label)
            }
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
 * Desktop's own samples use. [onResult] is called with an EMPTY list on cancel.
 *
 * MULTIPLE MODE, and it is one line rather than a second menu row on purpose: a
 * "Several files" row beside "Local file" would make [AttachChooser.direct]
 * return null on a daemon with no pages, which turns the clip button from a
 * one-press file dialog into a popup with two entries in it.
 * `AttachChooserTest` pins exactly that.
 *
 * ⚠ `FileDialog.getFile()` returns only the FIRST selection even in multiple
 * mode, and `getFiles()` returns absolute files — so the `directory + file` join
 * below is gone rather than looped, and must not come back.
 */
@Composable
fun AttachFilePicker(visible: Boolean, onResult: (List<File>) -> Unit) {
    if (!visible) return
    val callback by rememberUpdatedState(onResult)
    AwtWindow(
        create = {
            object : FileDialog(null as Frame?, "Attach files", LOAD) {
                init { isMultipleMode = true }

                override fun setVisible(value: Boolean) {
                    super.setVisible(value)
                    // setVisible(true) BLOCKS until the dialog closes, so this runs
                    // once the user has chosen — the return path a modal AWT dialog
                    // gives you, and the reason there is no listener here.
                    if (value) callback(files?.toList().orEmpty())
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
