package com.silencelen.huginn.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

// The attachment controls, shared verbatim by the chat composer and the session
// composer. One implementation on purpose: the two composers already drifted once
// (paste settle, send-enable rules), and "photo works in chats but not sessions"
// is precisely the kind of split this file exists to prevent.

/**
 * The staged photo, visible and killable. State chips rather than silence: an
 * upload that fails while the user types must say so BEFORE they send a message
 * that would then arrive without the thing it talks about.
 */
@Composable
fun AttachmentBar(attachment: HuginnViewModel.Attachment?, onClear: () -> Unit) {
    val (label, isError) = when (attachment) {
        is HuginnViewModel.Attachment.Uploading -> "Uploading…" to false
        is HuginnViewModel.Attachment.Ready ->
            (if (attachment.image) "Photo attached"
             else "Attached: ${attachment.name ?: "file"}") to false
        is HuginnViewModel.Attachment.Failed -> "Attachment failed: ${attachment.why}" to true
        null -> return
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove attachment",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

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
    onPickImage: (Uri) -> Unit,
    onPickFile: (Uri) -> Unit,
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

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onPickImage(uri) }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok -> captureUri?.takeIf { ok }?.let(onPickImage) }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onPickFile(uri) }

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
                pickImage.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            AttachRow("local-file", "Local file", Icons.Outlined.Description) {
                // The server allowlists what Read can genuinely open (images,
                // pdf, text); anything else fails fast with its words.
                pickFile.launch(arrayOf("*/*"))
            },
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
