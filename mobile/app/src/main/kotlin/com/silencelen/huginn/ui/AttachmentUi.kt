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
import androidx.compose.material3.DropdownMenuItem
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
 * The attach menu: camera, photo library, or any file. All three are the
 * system's own UIs and none needs a runtime permission — TakePicture hands the
 * camera app a FileProvider URI to write into, the photo picker is
 * permissionless by design, and OpenDocument grants exactly the one document
 * picked.
 */
@Composable
fun AttachButton(onPickImage: (Uri) -> Unit, onPickFile: (Uri) -> Unit) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
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

    Box {
        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(46.dp)) {
            Icon(
                Icons.Outlined.AttachFile,
                contentDescription = "Attach",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Take photo") },
                leadingIcon = { Icon(Icons.Outlined.PhotoCamera, null) },
                onClick = {
                    menuOpen = false
                    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
                    val uri = FileProvider.getUriForFile(
                        context,
                        "com.silencelen.huginn.fileprovider",
                        File(dir, "cap-${System.currentTimeMillis()}.jpg"),
                    )
                    captureUri = uri
                    runCatching { takePicture.launch(uri) }
                },
            )
            DropdownMenuItem(
                text = { Text("Photo library") },
                leadingIcon = { Icon(Icons.Outlined.Image, null) },
                onClick = {
                    menuOpen = false
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
            )
            DropdownMenuItem(
                text = { Text("File") },
                leadingIcon = { Icon(Icons.Outlined.Description, null) },
                onClick = {
                    menuOpen = false
                    // The server allowlists what Read can genuinely open (images,
                    // pdf, text); anything else fails fast with its words.
                    pickFile.launch(arrayOf("*/*"))
                },
            )
        }
    }
}
