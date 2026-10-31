package com.silencelen.huginn.ui

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.SentFile
import com.silencelen.huginn.data.huginnIoDispatcher
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The phone's [SentFileHost]: Download into the shared Downloads collection
 * (MediaStore, no storage permission on API 29+), Open through the app's own
 * FileProvider with a one-shot read grant, in whatever app claims the type.
 *
 * The type Open declares comes from the file's EXTENSION, never the server's
 * header: the daemon serves every non-image as octet-stream on purpose, and an
 * intent typed that way finds no viewer.
 */
class AndroidSentFiles(private val context: Context, private val client: () -> HuginnClient) : SentFileHost {

    override suspend fun fetch(id: String): ByteArray = client().outboxBytes(id)

    override suspend fun download(file: SentFile, bytes: ByteArray): String = withContext(huginnIoDispatcher) {
        val name = SentFiles.saveName(file)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(name))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext "Could not save it: Downloads refused the file"
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            "Saved to Downloads as $name"
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            "Could not save it: ${e.message ?: "write failed"}"
        }
    }

    override suspend fun open(file: SentFile, bytes: ByteArray): String? {
        if (!SentFiles.openable(file)) return "This type is download-only"
        val name = SentFiles.saveName(file)
        val copy = withContext(huginnIoDispatcher) {
            val dir = File(context.cacheDir, "sent").apply { mkdirs() }
            // One file per open, overwritten: the cache is for the viewer's moment.
            File(dir, name).apply { writeBytes(bytes) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", copy)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeOf(name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(Intent.createChooser(view, "Open ${file.name.ifBlank { name }}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        } catch (e: ActivityNotFoundException) {
            "No app on this phone opens this type - Download keeps it"
        }
    }

    private fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
}
