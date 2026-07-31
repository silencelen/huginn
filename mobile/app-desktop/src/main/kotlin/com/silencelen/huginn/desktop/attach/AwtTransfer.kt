package com.silencelen.huginn.desktop.attach

import com.silencelen.huginn.desktop.diag.AppLog
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import java.net.URI

/**
 * Reading an attachment out of an AWT [Transferable] — which is what BOTH a
 * clipboard paste and an OS drag-and-drop hand over.
 *
 * One place, because the flavour ladder is the fiddly part and it is identical for
 * the two of them. Compose's own `DragData` covers the common cases, but only the
 * raw transferable exposes the `image/png` byte flavour that a screenshot tool on
 * Linux offers instead of `imageFlavor`, and that is the single most likely thing
 * the owner will paste.
 */
object AwtTransfer {

    /**
     * Hands whatever [t] carries to [controller].
     *
     * @param textFallback called instead when the transferable holds only text —
     *   dropped selection from a browser or another editor, which the Electron
     *   client used to swallow because its handler only ever looked at `files`.
     * @return true when something was consumed.
     */
    fun consume(
        t: Transferable?,
        controller: AttachmentController,
        textFallback: (String) -> Unit = {},
    ): Boolean {
        if (t == null) return false
        // FILES FIRST. A file manager offers a file list AND a text/uri-list AND a
        // string flavour for the same drop; taking the string would attach the
        // path as prose instead of the file.
        files(t).firstOrNull()?.let { controller.attachFile(it); return true }
        image(t)?.let { controller.attachImage(it); return true }
        imageBytes(t)?.let { (bytes, name) -> controller.attachImageBytes(bytes, name); return true }
        text(t)?.takeIf { it.isNotBlank() }?.let { textFallback(it); return true }
        return false
    }

    /**
     * The same, for the system clipboard. Used by Ctrl+V in the composer.
     *
     * Returns false — rather than throwing — for a clipboard this process cannot
     * read, which happens for real: an X11 selection whose owner has gone away
     * times out inside AWT. False means "let the text field do its own paste",
     * which is the right answer in every one of those cases.
     */
    fun consumeClipboard(controller: AttachmentController): Boolean {
        val t = runCatching { Toolkit.getDefaultToolkit().systemClipboard?.getContents(null) }
            .onFailure { AppLog.warn("attach", "clipboard unreadable: ${it.message ?: it::class.simpleName}") }
            .getOrNull()
        if (t == null) {
            AppLog.warn("attach", "paste: the clipboard could not be read at all")
            return false
        }
        // An X11 selection transfer that TIMED OUT comes back as a transferable
        // with no flavours rather than as an error, which is indistinguishable from
        // an empty clipboard unless it is said out loud. Seen for real on this
        // machine under Xvfb, and the honest answer to "I pasted and nothing
        // happened" in that case is "the clipboard did not answer".
        val flavours = runCatching { t.transferDataFlavors.size }.getOrDefault(0)
        if (flavours == 0) {
            AppLog.warn("attach", "paste: the clipboard offered nothing (selection transfer failed or empty)")
            return false
        }
        // NO text fallback here: the text field's own Ctrl+V already pastes text,
        // and consuming it would mean a paste that inserts nothing.
        val took = consumeImageOrFile(t, controller)
        // Logged ONLY when the clipboard plainly held something attachable and this
        // still came away empty — the "I pasted a screenshot and nothing happened"
        // case, which is otherwise indistinguishable from a key that never arrived.
        // A plain text paste is silent, so the ring does not fill with typing.
        if (!took && looksAttachable(t)) {
            AppLog.warn(
                "attach",
                "paste: could not take " +
                    t.transferDataFlavors.joinToString(", ") { it.mimeType.substringBefore(';') },
            )
        }
        return took
    }

    private fun looksAttachable(t: Transferable): Boolean = runCatching {
        t.transferDataFlavors.any {
            it.mimeType.startsWith("image/") || DataFlavor.javaFileListFlavor.equals(it)
        }
    }.getOrDefault(false)

    private fun consumeImageOrFile(t: Transferable, controller: AttachmentController): Boolean {
        files(t).firstOrNull()?.let { controller.attachFile(it); return true }
        image(t)?.let { controller.attachImage(it); return true }
        imageBytes(t)?.let { (bytes, name) -> controller.attachImageBytes(bytes, name); return true }
        return false
    }

    // ------------------------------------------------------------ flavours

    fun files(t: Transferable): List<File> {
        runCatching {
            if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                @Suppress("UNCHECKED_CAST")
                val list = t.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                if (!list.isNullOrEmpty()) return list
            }
        }
        // text/uri-list: what GTK and several Linux desktops offer when AWT could
        // not build a java file list. `file:///path` entries only — a dragged http
        // URL is a link, not something to upload.
        runCatching {
            val flavor = t.transferDataFlavors.firstOrNull { it.mimeType.startsWith("text/uri-list") }
                ?: return emptyList()
            val raw = readString(t, flavor) ?: return emptyList()
            return raw.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("file:") }
                .mapNotNull { runCatching { File(URI(it)) }.getOrNull() }
                .filter { it.isFile }
                .toList()
        }
        return emptyList()
    }

    private fun image(t: Transferable): BufferedImage? = runCatching {
        if (!t.isDataFlavorSupported(DataFlavor.imageFlavor)) return null
        when (val data = t.getTransferData(DataFlavor.imageFlavor)) {
            is BufferedImage -> data
            is Image -> toBuffered(data)
            else -> null
        }
    }.getOrNull()

    /**
     * An `image/<subtype>` flavour whose representation class is a stream. Screenshot tools
     * on Linux commonly offer `image/png` this way and NOT `imageFlavor`, so
     * without this branch Ctrl+V of a fresh screenshot does nothing at all.
     */
    private fun imageBytes(t: Transferable): Pair<ByteArray, String>? = runCatching {
        val flavor = t.transferDataFlavors.firstOrNull {
            it.mimeType.startsWith("image/") && it.representationClass == InputStream::class.java
        } ?: return null
        val subtype = flavor.subType?.substringBefore(';')?.takeIf { it.isNotBlank() } ?: "png"
        val bytes = (t.getTransferData(flavor) as? InputStream)?.use { it.readBytes() } ?: return null
        if (bytes.isEmpty()) null else bytes to "pasted.$subtype"
    }.getOrNull()

    private fun text(t: Transferable): String? = runCatching {
        if (!t.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
        t.getTransferData(DataFlavor.stringFlavor) as? String
    }.getOrNull()

    private fun readString(t: Transferable, flavor: DataFlavor): String? = runCatching {
        when (val data = t.getTransferData(flavor)) {
            is String -> data
            is InputStream -> data.use { String(it.readBytes()) }
            is java.io.Reader -> data.use { it.readText() }
            else -> null
        }
    }.getOrNull()

    private fun toBuffered(image: Image): BufferedImage? {
        val w = image.getWidth(null)
        val h = image.getHeight(null)
        if (w <= 0 || h <= 0) return null
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            g.drawImage(image, 0, 0, null)
        } finally {
            g.dispose()
        }
        return out
    }
}
