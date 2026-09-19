package com.silencelen.huginn.desktop.attach

import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.UploadResult
import com.silencelen.huginn.desktop.diag.AppLog
import com.silencelen.huginn.ui.AttachBatch
import com.silencelen.huginn.ui.AttachChipItem
import com.silencelen.huginn.ui.AttachChipState
import com.silencelen.huginn.ui.AttachmentText
import com.silencelen.huginn.ui.TakeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.image.BufferedImage
import java.io.File
import kotlin.random.Random

/** Where an attachment has got to. The chip renders one of exactly these four. */
enum class AttachStatus { QUEUED, UPLOADING, READY, FAILED }

/**
 * One thing on its way to (or already on) huginn, as the composer shows it.
 *
 * [marker] is the bracketed text that will be appended to the outgoing message —
 * the daemon path travels IN the message, which is how Claude is told there is a
 * file and which tool opens it. It is non-null only in [AttachStatus.READY],
 * because a marker for a file that is not there yet is worse than no attachment.
 *
 * [id] is minted at intake and never reused. Identity by INDEX would be enough
 * for a single slot and is wrong for a list: uploads settle out of order, and
 * "remove the second chip" has to mean the same item a second later.
 */
data class ComposerAttachment(
    val id: String,
    val label: String,
    val image: Boolean,
    val status: AttachStatus,
    val marker: String? = null,
    val detail: String? = null,
    /** Source size at intake, stored size once the daemon has answered. */
    val bytes: Long? = null,
)

/** The chip's view of it — the shared row in `:ui` draws both shells from this. */
fun ComposerAttachment.chip(): AttachChipItem = AttachChipItem(
    id = id,
    label = label,
    image = image,
    state = when (status) {
        AttachStatus.QUEUED -> AttachChipState.QUEUED
        AttachStatus.UPLOADING -> AttachChipState.UPLOADING
        AttachStatus.READY -> AttachChipState.READY
        AttachStatus.FAILED -> AttachChipState.FAILED
    },
    bytes = bytes,
    detail = detail,
)

/**
 * What an intake path (a drop, a paste, the file dialog) hands its attachments to.
 *
 * An interface rather than the controller itself so [AwtTransfer]'s flavour ladder
 * — the fiddliest code in this package, and the one that silently took only the
 * FIRST of a multi-file drop for a year — can be asserted against a recording
 * sink instead of needing a client and a socket.
 */
interface AttachSink {
    fun attachFiles(files: List<File>)
    fun attachImage(image: BufferedImage, name: String = "pasted.png")
    fun attachImageBytes(bytes: ByteArray, name: String = "pasted.png")
}

/**
 * The composer's pending attachments: an ordered list, uploaded in the
 * background, consumed as a batch by the next send.
 *
 * This was ONE slot, and the header here used to say multi-attach was "not a
 * missing feature so much as a different message shape — several markers in one
 * message, each of which Claude may or may not open". That was right about the
 * shape, and the shape is now what this builds: order is preserved intake → chip
 * → marker, and a batch where some uploads fail still sends the ones that landed.
 *
 * Created per composer and [close]d with it, so leaving a chat cancels uploads
 * nobody is waiting for any more.
 */
class AttachmentController(
    private val client: HuginnClient,
    private val scope: CoroutineScope,
) : AttachSink {

    /**
     * EVERY WRITE TO THIS IS A CAS, never `_items.value = _items.value.<edit>`.
     *
     * Both composers hand this controller a `rememberCoroutineScope()`, which is
     * Compose's single UI thread, so today nothing here runs at once — but the
     * plain read-modify-write it used to do is only correct because of that, and
     * nothing says so at the call sites. Under a multi-threaded scope (which is
     * what a test gets for free the moment it uses `Dispatchers.Unconfined`:
     * every upload resumes on whichever engine thread answered it) two uploads
     * settling together lose a write, and a lost write is either a chip that
     * never reaches READY or an item that vanishes from the list — a silently
     * un-attached file, the exact failure the batch path exists to prevent.
     */
    private val _items = MutableStateFlow<List<ComposerAttachment>>(emptyList())
    val items: StateFlow<List<ComposerAttachment>> = _items.asStateFlow()

    /** The last thing that went wrong, for a line under the composer. Cleared on the next attach. */
    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    private val jobs = LinkedHashMap<String, Job>()

    /**
     * Ten files dropped at once are ten POSTs, and the daemon prunes its uploads
     * directory on each one. Four at a time keeps the batch moving without making
     * a drop look like a load test; the rest sit in [AttachStatus.QUEUED], which
     * is a state the chip can honestly show.
     */
    private val gate = Semaphore(MAX_IN_FLIGHT)

    fun dismissFailure() { _failure.value = null }

    /** Drops one item and cancels its upload if it is still in flight. */
    fun remove(id: String) {
        jobs.remove(id)?.cancel()
        _items.update { list -> list.filterNot { it.id == id } }
    }

    /** Drops everything and cancels whatever is still in flight. */
    fun clear() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _items.value = emptyList()
    }

    fun close() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    // ------------------------------------------------------------- intake

    /**
     * Files from the picker, a drop, or the clipboard's file list — ALL of them,
     * in the order the OS handed them over.
     *
     * Images go through [ImageTranscode]; everything else is STREAMED from disk and
     * never read into a ByteArray. The split is by what ImageIO can actually
     * decode rather than by extension alone, so a `.heic` — which the extension
     * says is an image and no stock JVM can read — falls through to the file path
     * and arrives as something a shell can look at instead of failing outright.
     */
    override fun attachFiles(files: List<File>) {
        val real = files.filter { it.isFile }
        if (real.isEmpty()) {
            if (files.isNotEmpty()) fail("that is not a file")
            return
        }
        val taking = AttachBatch.accept(_items.value.size, real)
        AttachBatch.refusedNote(_items.value.size, real.size)?.let { _failure.value = it }
        taking.forEach { attachOne(it) }
    }

    /** One file — the same path, so there is no second intake rule to drift. */
    fun attachFile(file: File) = attachFiles(listOf(file))

    private fun attachOne(file: File) {
        val name = file.name
        val isImage = FileKind.looksLikeImage(name) && ImageTranscode.canDecode(file)
        start(label = name, image = isImage, bytes = file.length()) {
            if (isImage) {
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

    /**
     * A pasted or dropped image with no file behind it — a screenshot on the
     * clipboard. Single, because a clipboard holds one image.
     */
    override fun attachImage(image: BufferedImage, name: String) {
        if (!hasRoom()) return
        start(label = ImageScale.jpegName(name), image = true, bytes = null) {
            val jpeg = withContext(kotlinx.coroutines.Dispatchers.Default) { ImageTranscode.fromImage(image) }
                ?: error("could not encode that image")
            val out = client.upload(jpeg, ImageTranscode.MIME, ImageScale.jpegName(name))
            Uploaded(out, AttachmentText.marker(out.path), true)
        }
    }

    /** Image BYTES off the clipboard (a flavour that hands over `image/png` rather than an Image). */
    override fun attachImageBytes(bytes: ByteArray, name: String) {
        if (!hasRoom()) return
        start(label = ImageScale.jpegName(name), image = true, bytes = bytes.size.toLong()) {
            val jpeg = withContext(kotlinx.coroutines.Dispatchers.Default) { ImageTranscode.fromBytes(bytes) }
                ?: error("could not read that image")
            val out = client.upload(jpeg, ImageTranscode.MIME, ImageScale.jpegName(name))
            Uploaded(out, AttachmentText.marker(out.path), true)
        }
    }

    private fun hasRoom(): Boolean {
        if (AttachBatch.room(_items.value.size) > 0) return true
        _failure.value = AttachBatch.refusedNote(_items.value.size, 1)
        return false
    }

    // -------------------------------------------------------------- send

    /**
     * The markers to append to the message being sent, waiting out uploads still
     * in flight, and clearing the composer either way.
     *
     * THE RACE THIS EXISTS FOR: hitting send a beat after dropping a file posts a
     * message that talks about a file the daemon has not finished receiving, and
     * Claude's Read comes back "no such file". Waiting is not optional; the cap is
     * only there so a wedged socket cannot hold the composer hostage.
     *
     * [SETTLE_TIMEOUT_MS] IS A WHOLE-BATCH BUDGET, not 20s per item. Ten files
     * settled one at a time could hold the composer for three minutes, which is
     * precisely the wedged-socket case the timeout exists to prevent.
     *
     * Returns the READY markers IN ATTACH ORDER and the labels of everything else.
     * The composer SENDS WHAT LANDED and names the rest — it never sends nothing
     * because one file failed, and it never silently drops one. What went wrong is
     * also left in [failure], because a silently un-attached file is the version
     * of this bug that takes a week to notice.
     */
    suspend fun take(): TakeResult {
        val running = jobs.values.toList()
        if (running.any { it.isActive }) {
            val settled = settleAll(running, SETTLE_TIMEOUT_MS)
            if (!settled) {
                // Said out loud: the composer's own line names WHICH files went
                // without landing, but only this distinguishes "the daemon
                // refused them" from "the socket never came back".
                AppLog.warn("attach", "batch did not settle in ${SETTLE_TIMEOUT_MS / 1000}s — sending without it")
                // NonCancellable is not needed to cancel, but the state writes
                // below must survive this function's caller being cancelled
                // mid-send.
                withContext(NonCancellable) { running.forEach { it.cancel() } }
            }
        }
        // Read and clear in ONE step: a plain read-then-clear drops an item
        // attached between the two, which is the send that loses an attachment
        // and never says so.
        val all = _items.getAndUpdate { emptyList() }
        jobs.clear()
        // BOTH lists come off the one ordered list, so both are in ATTACH order —
        // the failure line reads as "these attachments", and a line whose names
        // are in whatever order the uploads happened to give up in reads as a
        // different set of files.
        val markers = all.filter { it.status == AttachStatus.READY }.mapNotNull { it.marker }
        val failed = all.filter { it.status != AttachStatus.READY }.map { it.label }
        withContext(NonCancellable) {
            AttachBatch.failureLine(failed, all.size)?.let { _failure.value = it }
        }
        return TakeResult(markers, failed)
    }

    // ---------------------------------------------------------- plumbing

    private class Uploaded(val result: UploadResult, val marker: String, val image: Boolean)

    private fun start(label: String, image: Boolean, bytes: Long?, work: suspend () -> Uploaded) {
        // Logged because "I attached something and nothing happened" is a question
        // the diagnostics blob should be able to answer on its own.
        AppLog.info("attach", "uploading $label")
        val id = newId()
        // NOT cancelling what is already here. The single-slot version cancelled
        // the previous job unconditionally, which is the entire reason a second
        // attach used to replace the first rather than joining it.
        _items.update { it + ComposerAttachment(id, label, image, AttachStatus.QUEUED, bytes = bytes) }
        jobs[id] = scope.launch {
            gate.withPermit {
                update(id) { it.copy(status = AttachStatus.UPLOADING) }
                runCatching { work() }
                    .onSuccess { done ->
                        AppLog.info("attach", "uploaded $label -> ${done.result.bytes} bytes, readable=${done.result.readable}")
                        update(id) {
                            it.copy(
                                image = done.image,
                                status = AttachStatus.READY,
                                marker = done.marker,
                                bytes = done.result.bytes.takeIf { b -> b > 0 } ?: it.bytes,
                                // Binaries carry the "requires act mode" wording in
                                // the marker itself; saying so on the chip too is the
                                // only warning the sender gets BEFORE the message is
                                // written.
                                detail = if (done.result.readable) null
                                else "binary — Claude will need act mode to inspect it",
                            )
                        }
                    }
                    .onFailure { t ->
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        val why = t.message ?: "upload failed"
                        AppLog.warn("attach", "upload of $label failed: $why")
                        update(id) { it.copy(status = AttachStatus.FAILED, detail = why) }
                    }
            }
        }
    }

    private fun update(id: String, edit: (ComposerAttachment) -> ComposerAttachment) {
        _items.update { list -> list.map { if (it.id == id) edit(it) else it } }
    }

    private fun fail(message: String) {
        _failure.value = message
    }

    companion object {
        /**
         * How long a SEND will wait for the whole batch. The daemon caps what it
         * accepts well under this; anything past it is a socket that is not
         * coming back.
         */
        const val SETTLE_TIMEOUT_MS: Long = 20_000

        /** How many uploads run at once; the rest wait in [AttachStatus.QUEUED]. */
        const val MAX_IN_FLIGHT: Int = 4

        /** Mint-on-attach, never reused. Short because it only has to be unique here. */
        fun newId(): String = "a-" + Random.nextInt(0, 0x10000000).toString(16).padStart(7, '0')
    }
}

/**
 * Waits out a whole batch under ONE budget.
 *
 * Pure and top-level so the budget's SHAPE can be asserted without a client: the
 * difference between this and the obvious `for (j in jobs) withTimeoutOrNull(b)
 * { j.join() }` is invisible in a one-item batch and is three minutes in a
 * ten-item one.
 *
 * @return true when everything settled inside [budgetMs].
 */
suspend fun settleAll(jobs: List<Job>, budgetMs: Long): Boolean =
    withTimeoutOrNull(budgetMs) { jobs.joinAll(); true } == true

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
