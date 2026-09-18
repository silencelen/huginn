package com.silencelen.huginn.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

// The attachment controls, shared verbatim by the chat composer and the session
// composer. One implementation on purpose: the two composers already drifted once
// (paste settle, send-enable rules), and "photo works in chats but not sessions"
// is precisely the kind of split this file exists to prevent.
//
// The CHIPS are no longer here: they are `:ui`'s AttachChipRow, which the desktop
// draws too. What was here was a one-line Row that could say "Uploading…" and
// nothing about WHICH of several files that was.

/**
 * The attach menu: camera, photo library, any file — and, when the daemon has
 * pages, one of those. The first three are the system's own UIs and none needs a
 * runtime permission: TakePicture hands the camera app a FileProvider URI to
 * write into, the photo picker is permissionless by design, and OpenDocument
 * grants exactly the one document picked.
 *
 * "Notes page" opens a SECOND menu on the same anchor rather than nesting one,
 * which is the same move the desktop's clip button makes — and it is why the row
 * list is built by the shared [AttachChooser] instead of being written out here:
 * the rule about whether that row exists at all belongs to the feature probe, not
 * to either shell.
 *
 * @param pads already gated by the caller's probe. Empty means an older daemon,
 *   and the menu is then exactly the three it has always been.
 */
@Composable
fun AttachButton(
    onPickImages: (List<Uri>) -> Unit,
    onPickFiles: (List<Uri>) -> Unit,
    /** The clipboard row; the view model owns the reading and the refusals. */
    onPasteImage: () -> Unit = {},
    pads: List<com.silencelen.huginn.data.Scratchpad> = emptyList(),
    padRefId: String? = null,
    onPadRef: (String?) -> Unit = {},
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var pagePicker by remember { mutableStateOf(false) }
    // Held across the camera round trip: TakePicture only returns a boolean, so
    // the URI it wrote into has to survive the app being backgrounded meanwhile.
    var captureUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    // MULTIPLE, capped where the contract caps it. The picker enforces the count
    // itself, so a person cannot select eleven and then be told about it after the
    // fact — which is the only place on this screen the cap can be stated before
    // it bites.
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AttachBatch.MAX_ITEMS)
    ) { uris -> if (uris.isNotEmpty()) onPickImages(uris) }

    // One camera, one photo: TakePicture stays single.
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok -> captureUri?.takeIf { ok }?.let { onPickImages(listOf(it)) } }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) onPickFiles(uris) }

    val rows = AttachChooser.rows(
        own = listOf(
            AttachRow("take-photo", "Take photo", Icons.Outlined.PhotoCamera) {
                val dir = File(context.cacheDir, "captures").apply { mkdirs() }
                // Yesterday's captures have been uploaded or abandoned; either
                // way the full-res original has no further use here. Pruned on
                // the next use rather than a schedule — a dir that only grows
                // when the camera is used only needs sweeping then.
                dir.listFiles()?.forEach {
                    if (it.lastModified() < System.currentTimeMillis() - 86_400_000L) it.delete()
                }
                val uri = FileProvider.getUriForFile(
                    context,
                    "com.silencelen.huginn.fileprovider",
                    File(dir, "cap-${System.currentTimeMillis()}.jpg"),
                )
                captureUri = uri
                runCatching { takePicture.launch(uri) }
            },
            AttachRow("photo-library", "Photo library", Icons.Outlined.Image) {
                pickImages.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            AttachRow("local-file", "Local file", Icons.Outlined.Description) {
                // The server allowlists what Read can genuinely open (images,
                // pdf, text); anything else fails fast with its words.
                pickFiles.launch(arrayOf("*/*"))
            },
            // A ROW HERE, not a fourth icon button. The composer line already
            // carries attach, mic, voice and send on a phone; the question
            // "where does this image come from" is the one this menu already
            // answers, and the clipboard is one more answer to it.
            AttachRow("paste-image", "Paste image", Icons.Outlined.ContentPaste, onPasteImage),
        ),
        padsAvailable = pads.isNotEmpty(),
        onNotesPage = { pagePicker = true },
    )
    // Cannot fire here — the phone always offers its own three — but it is the
    // same call the desktop leans on, and special-casing it would be a second
    // rule about the same menu.
    val sole = AttachChooser.direct(rows)

    Box {
        IconButton(
            onClick = { if (sole != null) sole.onPick() else menuOpen = true },
            modifier = Modifier.size(46.dp),
        ) {
            Icon(
                Icons.Outlined.AttachFile,
                contentDescription = "Attach",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            AttachChooserItems(rows) { menuOpen = false; it.onPick() }
        }
        DropdownMenu(expanded = pagePicker, onDismissRequest = { pagePicker = false }) {
            ScratchpadPickerItems(pads, padRefId) { pagePicker = false; onPadRef(it) }
        }
    }
}
