package com.silencelen.huginn.desktop.attach

import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.UploadResult
import com.silencelen.huginn.desktop.diag.AppLog
import com.silencelen.huginn.ui.AttachmentText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.image.BufferedImage
import java.io.File

/** Where an attachment has got to. The chip renders one of exactly these three. */
enum class AttachStatus { UPLOADING, READY, FAILED }

/**
 * One thing on its way to (or already on) huginn, as the composer shows it.
 *
 * [marker] is the bracketed text that will be appended to the outgoing message —
 * the daemon path travels IN the message, which is how Claude is told there is a
 * file and which tool opens it. It is non-null only in [AttachStatus.READY],
 * because a marker for a file that is not there yet is worse than no attachment.
 */
data class ComposerAttachment(
    val label: String,
    val image: Boolean,
    val status: AttachStatus,
    val marker: String? = null,
    val detail: String? = null,
)

/**
 * The composer's attachment slot: one item, uploaded in the background, consumed
 * by the next send.
 *
 * ONE at a time, matching the phone and the Electron client. Multi-attach is not
 * a missing feature so much as a different message shape — several markers in one
 * message, each of which Claude may or may not open — and none of the three
 * clients has needed it.
 *
 * Created per composer and [close]d with it, so leaving a chat cancels an upload
 * nobody is waiting for any more.
 */
class AttachmentController(
    private val client: HuginnClient,
    private val scope: CoroutineScope,
) {

    private val _current = MutableStateFlow<ComposerAttachment?>(null)
    val current: StateFlow<ComposerAttachment?> = _current.asStateFlow()

    /** The last thing that went wrong, for a line under the composer. Cleared on the next attach. */
    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    private var job: Job? = null

    fun dismissFailure() { _failure.value = null }

    /** Drops whatever is attached and cancels an upload still in flight. */
    fun clear() {
        job?.cancel()
        job = null
        _current.value = null
    }

    fun close() {
        job?.cancel()
        job = null
    }

    // ------------------------------------------------------------- intake

    /**
     * A file from the picker, a drop, or the clipboard's file list.
     *
     * Images go through [ImageTranscode]; everything else is STREAMED from disk and
     * never read into a ByteArray. The split is by what ImageIO can actually
     * decode rather than by extension alone, so a `.heic` — which the extension
     * says is an image and no stock JVM can read — falls through to the file path
     * and arrives as something a shell can look at instead of failing outright.
     */
    fun attachFile(file: File) {
        if (!file.isFile) {
            fail("that is not a file")
            return
        }
        val name = file.name
        start(label = name, image = FileKind.looksLikeImage(name) && ImageTranscode.canDecode(file)) {
            if (FileKind.looksLikeImage(name) && ImageTranscode.canDecode(file)) {
                val jpeg = withContext(kotlinx.coroutines.Dispatchers.Default) { ImageTranscode.fromFile(file) }
                    ?: error("could not read that image")
                val out = client.upload(jpeg, ImageTranscode.MIME, ImageScale.jpegName(name))
                Uploaded(out, AttachmentText.marker(out.path), true)
            } else {
                val out = client.uploadStream(FileKind.mime(file), name, FileByteStream(file))
                Uploaded(out, AttachmentText.fileMarker(out.path, name, out.readable), false)
            }
        }
    }

    /** A pasted or dropped image with no file behind it — a screenshot on the clipboard. */
    fun attachImage(image: BufferedImage, name: String = "pasted.png") {
        start(label = ImageScale.jpegName(name), image = true) {
            val jpeg = withContext(kotlinx.coroutines.Dispatchers.Default) { ImageTranscode.fromImage(image) }
                ?: error("could not encode that image")
            val out = client.upload(jpeg, ImageTranscode.MIME, ImageScale.jpegName(name))
            Uploaded(out, AttachmentText.marker(out.path), true)
        }
    }

    /** Image BYTES off the clipboard (a flavour that hands over `image/png` rather than an Image). */
    fun attachImageBytes(bytes: ByteArray, name: String = "pasted.png") {
        start(label = ImageScale.jpegName(name), image = true) {
            val jpeg = withContext(kotlinx.coroutines.Dispatchers.Default) { ImageTranscode.fromBytes(bytes) }
                ?: error("could not read that image")
            val out = client.upload(jpeg, ImageTranscode.MIME, ImageScale.jpegName(name))
            Uploaded(out, AttachmentText.marker(out.path), true)
        }
    }

    // -------------------------------------------------------------- send

    /**
     * The marker to append to the message being sent, waiting for an upload still
     * in flight, and clearing the slot either way.
     *
     * THE RACE THIS EXISTS FOR: hitting send a beat after dropping a file posts a
     * message that talks about a file the daemon has not finished receiving, and
     * Claude's Read comes back "no such file". Waiting is not optional; the cap is
     * only there so a wedged socket cannot hold the composer hostage.
     *
     * On a stall or a failure this returns NULL and the message goes without a
     * marker — never a marker for bytes that did not land. What went wrong is left
     * in [failure] for the composer to show, because a silently un-attached file is
     * the version of this bug that takes a week to notice.
     */
    suspend fun take(): String? {
        val running = job
        if (running != null && running.isActive) {
            val settled = withTimeoutOrNull(SETTLE_TIMEOUT_MS) { running.join() }
            if (settled == null) {
                // NonCancellable is not needed to cancel, but the state write below
                // must survive this function's caller being cancelled mid-send.
                withContext(NonCancellable) {
                    running.cancel()
                    _failure.value = "upload did not finish in ${SETTLE_TIMEOUT_MS / 1000}s — sent without the file"
                }
            }
        }
        val a = _current.value
        _current.value = null
        job = null
        if (a != null && a.status != AttachStatus.READY && _failure.value == null) {
            _failure.value = a.detail ?: "attachment failed — sent without the file"
        }
        return a?.marker
    }

    // ---------------------------------------------------------- plumbing

    private class Uploaded(val result: UploadResult, val marker: String, val image: Boolean)

    private fun start(label: String, image: Boolean, work: suspend () -> Uploaded) {
        // Logged because "I attached something and nothing happened" is a question
        // the diagnostics blob should be able to answer on its own.
        AppLog.info("attach", "uploading $label")
        job?.cancel()
        _failure.value = null
        _current.value = ComposerAttachment(label, image, AttachStatus.UPLOADING)
        job = scope.launch {
            runCatching { work() }
                .onSuccess { done ->
                    AppLog.info("attach", "uploaded $label -> ${done.result.bytes} bytes, readable=${done.result.readable}")
                    _current.value = ComposerAttachment(
                        label = label,
                        image = done.image,
                        status = AttachStatus.READY,
                        marker = done.marker,
                        // Binaries carry the "requires act mode" wording in the
                        // marker itself; saying so on the chip too is the only
                        // warning the sender gets BEFORE the message is written.
                        detail = if (done.result.readable) null else "binary — Claude will need act mode to inspect it",
                    )
                }
                .onFailure { t ->
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    val why = t.message ?: "upload failed"
                    AppLog.warn("attach", "upload of $label failed: $why")
                    _current.value = ComposerAttachment(label, image, AttachStatus.FAILED, detail = why)
                    _failure.value = why
                }
        }
    }

    private fun fail(message: String) {
        _current.value = null
        _failure.value = message
    }

    companion object {
        /**
         * How long a send will wait for an upload. The daemon caps what it accepts
         * well under this; anything past it is a socket that is not coming back.
         */
        const val SETTLE_TIMEOUT_MS: Long = 20_000
    }
}

/**
 * The outgoing message text: what was typed, then the attachment marker.
 *
 * Pure and separate because it is the join everything downstream depends on — the
 * marker regex in [AttachmentText.displayText] has to match what this produces, or
 * the sender sees a raw daemon path in their own message. A blank draft sends the
 * marker alone, which is the "here, look at this" case and is deliberately allowed.
 *
 * @param separator a blank line for a chat, and [PANE_SEPARATOR] for a tmux pane —
 *   where the text is TYPED and a newline is the submit key, so a paragraph break
 *   would send half the message and leave the marker sitting on the next prompt.
 */
fun composeMessage(text: String, marker: String?, separator: String = "\n\n"): String {
    val t = text.trim()
    if (marker.isNullOrBlank()) return t
    return if (t.isEmpty()) marker else "$t$separator$marker"
}

/** See [composeMessage]: a pane cannot take a newline that is not a submit. */
const val PANE_SEPARATOR: String = " "

/**
 * Text dropped onto the composer, folded into what is already there.
 *
 * Selected text from a browser, the transcript or another editor lands on the
 * composer and used to VANISH in the Electron client, because the drop handler
 * only ever looked at `files`. Appended with a blank line when there is already a
 * paragraph, so a dropped quote does not run into the sentence being typed.
 */
fun appendDropped(current: String, dropped: String): String {
    val add = dropped.trim()
    if (add.isEmpty()) return current
    if (current.isBlank()) return add
    val sep = if (current.endsWith("\n")) "" else if (current.trimEnd() == current) "\n\n" else "\n"
    return current + sep + add
}
