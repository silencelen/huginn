package com.silencelen.huginn.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.SentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * What a shell does with a file Claude sent: fetch its bytes from the daemon, and
 * hand them to the platform. `:ui` holds no client and knows no file system, so
 * each shell provides one ([LocalSentFiles]); without one the card still says
 * what was sent, with no buttons.
 */
interface SentFileHost {
    /** The outbox copy's bytes (`GET /v1/outbox/<id>`). */
    suspend fun fetch(id: String): ByteArray

    /** Saves the bytes where this platform keeps downloads; returns the words to show ("Saved to Downloads"). */
    suspend fun download(file: SentFile, bytes: ByteArray): String

    /** Hands the bytes to the device's own viewer for the type; returns null when it opened, or why not. */
    suspend fun open(file: SentFile, bytes: ByteArray): String?
}

val LocalSentFiles = staticCompositionLocalOf<SentFileHost?> { null }

/**
 * A file Claude sent the person with its `send_file` tool, in place of that
 * tool's row: an image inline (tap for the full view), anything else as a card
 * with its name, type and size - and Download, and Open where [SentFiles.openable]
 * allows it (never for something that runs when opened).
 *
 * The actions WRAP as whole buttons (the 2026-10-30 list-pane lesson): a narrow
 * column moves a button to the next line rather than squeezing its label.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SentFileCard(file: SentFile, modifier: Modifier = Modifier) {
    val host = LocalSentFiles.current
    val loader = LocalAttachmentImages.current
    val scope = rememberCoroutineScope()
    var note by remember(file.id) { mutableStateOf<String?>(null) }
    var busy by remember(file.id) { mutableStateOf(false) }
    var thumb by remember(file.id) { mutableStateOf<ImageBitmap?>(null) }
    if (file.image && loader != null) {
        LaunchedEffect(file.id) { thumb = loader.loadOutbox(file.id) }
    }
    val viewer = remember { ImageViewerState() }

    fun act(verb: suspend (ByteArray) -> String?) {
        if (busy || host == null) return
        busy = true
        note = null
        scope.launch {
            note = try {
                verb(host.fetch(file.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                SentFiles.failure((e as? HuginnClient.HuginnException)?.code, e.message)
            } finally {
                busy = false
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.padding(vertical = 4.dp).widthIn(max = 420.dp).testTag(SENT_FILE_TAG),
    ) {
        Column(Modifier.padding(10.dp)) {
            val shown = thumb
            if (shown != null) {
                Image(
                    bitmap = shown,
                    contentDescription = file.caption ?: file.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .widthIn(max = 400.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { viewer.open(file.name, shown) },
                )
                Spacer(Modifier.size(6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (shown == null) {
                    Icon(
                        Icons.Outlined.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        file.name.ifBlank { file.id },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = SentFiles.meta(file)
                    if (meta.isNotEmpty()) {
                        Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            file.caption?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.size(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            if (host != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { act { host.download(file, it) } }, enabled = !busy, modifier = Modifier.testTag("$SENT_FILE_TAG.download")) {
                        Text("Download", maxLines = 1, softWrap = false)
                    }
                    if (SentFiles.openable(file)) {
                        TextButton(onClick = { act { host.open(file, it) } }, enabled = !busy, modifier = Modifier.testTag("$SENT_FILE_TAG.open")) {
                            Text("Open", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
            val said = note ?: if (busy) "Fetching…" else null
            if (said != null) {
                Text(said, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    FullImageViewer(viewer)
}

const val SENT_FILE_TAG: String = "sentFile"
