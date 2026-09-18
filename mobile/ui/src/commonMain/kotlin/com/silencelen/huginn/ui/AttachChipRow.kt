package com.silencelen.huginn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The pending attachments, drawn the same way in every composer.
 *
 * ONE row for three composers. The phone drew a full-width `Row` with a text
 * label ("Uploading…", "Photo attached") and the desktop drew a tinted chip with
 * a Material glyph; the two answers to "what is riding out with this message"
 * had drifted far enough that the phone could not say WHICH of several files had
 * failed, because it could only ever hold one.
 *
 * The chip carries name, size and state, and owns its own remove control —
 * removing a single item is the whole reason a list needs chips rather than a
 * sentence.
 */

/** Where one attachment has got to. [QUEUED] is accepted but not yet started. */
enum class AttachChipState { QUEUED, UPLOADING, READY, FAILED }

/**
 * One pending attachment, as a composer shows it.
 *
 * Shell-neutral on purpose: the desktop's `ComposerAttachment` holds an AWT-ish
 * upload job and the phone's holds a content URI, and neither belongs in `:ui`.
 * What both can say is this.
 *
 * @param id stable for the life of the attachment, so removing the second of
 *   three cannot remove the third — index identity is exactly the bug a list of
 *   in-flight uploads reorders itself into.
 * @param bytes the source size where it is known at intake, the stored size once
 *   the daemon has answered. Null while neither is known.
 */
data class AttachChipItem(
    val id: String,
    val label: String,
    val image: Boolean,
    val state: AttachChipState,
    val bytes: Long? = null,
    val detail: String? = null,
)

/** The pure rules behind [AttachChipRow], so they can be asserted without a window. */
object AttachChips {

    /**
     * How many chips are drawn before the row collapses into a count.
     *
     * Six fits two lines of a narrow composer. Past that the chips ARE the
     * composer, and the person attaching a tenth file does not need to read the
     * names of the first nine.
     */
    const val VISIBLE_MAX: Int = 6

    fun shown(items: List<AttachChipItem>): List<AttachChipItem> = items.take(VISIBLE_MAX)

    fun overflow(items: List<AttachChipItem>): Int = (items.size - VISIBLE_MAX).coerceAtLeast(0)

    /**
     * The chip's text: the name, its size, and what is happening to it.
     *
     * Status is a suffix rather than a badge or a spinner — house rule, and the
     * state that matters (uploading vs ready vs failed) is legible from the
     * ellipsis alone.
     */
    fun chipText(item: AttachChipItem): String = buildString {
        append(item.label)
        sizeWords(item.bytes)?.let { append("  ").append(it) }
        when (item.state) {
            AttachChipState.QUEUED -> append(" — waiting")
            AttachChipState.UPLOADING -> append('…')
            AttachChipState.FAILED -> append(" — failed")
            AttachChipState.READY -> Unit
        }
    }

    /**
     * A byte count in the unit a person would say it in.
     *
     * Whole numbers below 10 MB and one decimal above, because "9.7 MB" is the
     * size that decides whether to attach it and "9728 KB" is not. Null for an
     * unknown size — a chip that says "0 B" while the file is being read is a
     * claim, and an empty gap is not.
     */
    fun sizeWords(bytes: Long?): String? {
        if (bytes == null || bytes < 0) return null
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${kb.toLong()} KB"
        val mb = kb / 1024.0
        if (mb < 10) return "${((mb * 10).toLong()) / 10.0} MB"
        if (mb < 1024) return "${mb.toLong()} MB"
        return "${(((mb / 1024.0) * 10).toLong()) / 10.0} GB"
    }
}

/**
 * The chips themselves, wrapping rather than scrolling.
 *
 * A `FlowRow` because two chips sit side by side where there is room and stack
 * where there is not: the alternative — one full-width row per attachment — costs
 * a band of composer per file on the window shape that had none to spare.
 *
 * @param leading anything else riding out with this message that belongs on the
 *   SAME wrapping line — the desktop's page-reference badge. A slot rather than a
 *   second row above, for the band reason above: a page and two files are one
 *   line of composer, not two. Nesting this row inside the caller's own FlowRow
 *   would not do it — a FlowRow measures a child against the whole width, so the
 *   chips would always land on the line below the badge.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttachChipRow(
    items: List<AttachChipItem>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    if (items.isEmpty() && leading == null) return
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        leading?.invoke()
        AttachChips.shown(items).forEach { item ->
            AttachChip(item) { onRemove(item.id) }
        }
        val more = AttachChips.overflow(items)
        if (more > 0) {
            Text(
                "+$more more",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, top = 6.dp),
            )
        }
    }
}

/**
 * One chip.
 *
 * A MATERIAL ICON, not the 📷/📎 the phone's old bar used. Emoji here render as a
 * tofu box on a machine with no emoji font — verified on this one — and both
 * shells already ship these glyphs in their icon font. The MARKER text keeps the
 * emoji, because that is `:core`'s shared wording and it is rendered by whatever
 * is reading the message, not by this composer.
 */
@Composable
fun AttachChip(item: AttachChipItem, onRemove: () -> Unit) {
    val failed = item.state == AttachChipState.FAILED
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (failed) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .padding(start = 10.dp, top = 2.dp, bottom = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (item.image) Icons.Filled.Image else Icons.Filled.AttachFile,
            contentDescription = null,
            modifier = Modifier.padding(end = 6.dp).size(15.dp),
            tint = if (failed) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            AttachChips.chipText(item),
            style = MaterialTheme.typography.labelMedium,
            color = if (failed) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        IconButton(onClick = onRemove, modifier = Modifier.padding(start = 2.dp).size(24.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove ${item.label}",
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
