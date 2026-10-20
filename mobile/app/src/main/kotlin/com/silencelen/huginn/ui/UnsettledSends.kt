package com.silencelen.huginn.ui

/**
 * Messages that have left the box but that the host has not yet taken.
 *
 * ⚠⚠ NOTHING TYPED IS EVER SILENTLY LOST. The box empties at the tap (see
 * [SendPresses]), and a send can then wait up to [AttachmentSlots.SETTLE_TIMEOUT_MS]
 * for its uploads before it even reaches the host. Closing the app out in that
 * time, or before the host answered, left the words nowhere. So every message is
 * held here, and folded into the drafts the store keeps ([persisted]), from the tap
 * until the host takes it; a refusal puts it back in the box, as it always did.
 * A cold start or a restore therefore finds an unsettled message in the composer
 * it was sent from, after anything typed there since, exactly where a refusal
 * would have put it ([foldBack] is that one rule).
 *
 * It is never sent again on its own. Whether it reached the host is not known, so
 * only the person can tell, and a message shown twice is better than one acted on
 * twice or lost.
 *
 * A plain class for the reason [AttachmentSlots] is one: the view model is an
 * `AndroidViewModel`, and nothing that needs an `Application` can be tested here.
 */
class UnsettledSends {

    private data class Entry(val id: Long, val owner: String, val text: String)

    private val lock = Any()
    private var entries: List<Entry> = emptyList()
    private var nextId: Long = 1

    /** A message has left [owner]'s box; its words are [text] until it is composed. */
    fun open(owner: String, text: String): Long = synchronized(lock) {
        val id = nextId++
        entries = entries + Entry(id, owner, text)
        id
    }

    /** The message as composed, its attachment markers included: what a retry needs. */
    fun update(id: Long, text: String) = synchronized(lock) {
        entries = entries.map { if (it.id == id) it.copy(text = text) else it }
    }

    /** The host took it, or a refusal has put it back in the box. Either way it is not ours to keep. */
    fun settle(id: Long) = synchronized(lock) {
        entries = entries.filterNot { it.id == id }
    }

    /**
     * What the drafts store must hold: every box as it is now, with every message
     * still unsettled folded in after it, in the order they were sent.
     */
    fun persisted(visible: Map<String, String>): Map<String, String> = synchronized(lock) {
        entries.filter { it.text.isNotBlank() }.fold(visible) { kept, e ->
            kept + (e.owner to foldBack(kept[e.owner].orEmpty(), e.text))
        }
    }
}

/**
 * A message put back after what the box holds now. Appended, never clobbered:
 * anything typed since the tap outranks it. The one rule for a refusal and for a
 * message that comes back on a cold start, so both read the same.
 */
fun foldBack(current: String, text: String): String = if (current.isBlank()) text else current + "\n" + text
