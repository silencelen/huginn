package com.silencelen.huginn.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
        is HuginnViewModel.Attachment.Uploading -> "Uploading photo…" to false
        is HuginnViewModel.Attachment.Ready -> "Photo attached" to false
        is HuginnViewModel.Attachment.Failed -> "Photo failed: ${attachment.why}" to true
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

/** Opens the system photo picker — the system's own UI, no permission needed. */
@Composable
fun AttachPhotoButton(onPick: (Uri) -> Unit) {
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onPick(uri) }
    IconButton(
        onClick = {
            pickImage.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
        modifier = Modifier.size(46.dp),
    ) {
        Icon(
            Icons.Outlined.Image,
            contentDescription = "Attach a photo",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
