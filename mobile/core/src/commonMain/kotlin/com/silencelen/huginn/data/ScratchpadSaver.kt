package com.silencelen.huginn.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The open scratchpad and the writing of it: debounced, revision-aware, and the
 * same set/flush/close discipline [DraftBook] enforces for composer text.
 *
 * There is no Save button. That is a deliberate choice about what a page IS —
 * somewhere to put a thought mid-sentence, on whichever device is in your hand —
 * and it is exactly why every rule below matters more here than it would for a
 * form:
 *
 *  * **The pending write is cancelled on close, never merely superseded.** The
 *    Electron client's version of this bug put a sent message back in the
 *    composer; here it would put a stale paragraph over whatever the OTHER device
 *    has since saved, because a timer that fires after you have switched away is
 *    a timer carrying a page you have stopped looking at.
 *  * **[scope] must outlive the view.** The flush that matters happens as the
 *    editor is torn down, so a scope owned by that editor is cancelled at the
 *    exact moment it has work to do. Same reason DraftBook is built at app level.
 *  * **A conflict is an ANSWER, not an error.** Two devices autosaving one page is
 *    the ordinary case, so a 409 hands back the winner's text and this adopts it
 *    with a quiet line — see [note]. Losing silently, or throwing, are both worse
 *    than being told.
 *
 * @param save the wire call. Injected rather than taking a client so the rules
 *   above can be asserted without a socket.
 */
class ScratchpadSaver(
    private val scope: CoroutineScope,
    private val save: suspend (id: String, rev: Int, content: String) -> ScratchpadSave,
    private val debounceMs: Long = DEBOUNCE_MS,
) {

    /** What the state line under the editor is saying. */
    enum class State {
        /** Nothing typed since the last write. */
        IDLE,

        /** Typed, and the write has not gone out yet. */
        PENDING,
        SAVING,
        SAVED,

        /** The write failed; the text is still held and the next one retries. */
        FAILED,
    }

    private val _pad = MutableStateFlow<Scratchpad?>(null)

    /** The page as it is ON SCREEN — local edits included, ahead of the server. */
    val pad: StateFlow<Scratchpad?> = _pad.asStateFlow()

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _note = MutableStateFlow<String?>(null)

    /** One quiet line about something true that the state alone cannot say. */
    val note: StateFlow<String?> = _note.asStateFlow()

    fun clearNote() { _note.value = null }

    /**
     * The write that still has to happen, captured with the id and rev it belongs
     * to. Carried rather than re-read from [_pad] at write time because switching
     * pages must land the OLD page's text under the OLD page's id — the one case
     * where reading current state at write time would send the wrong text to the
     * wrong file.
     */
    private var pending: Scratchpad? = null

    /**
     * The debounce. Cancelled on every keystroke, which is the whole of what
     * debouncing is.
     */
    private var timerJob: Job? = null

    /**
     * The write itself, kept SEPARATE from the timer and never cancelled by
     * typing.
     *
     * ⚠ One job for both was a real bug and a quiet one: a keystroke arriving
     * while a PATCH was in flight cancelled the PATCH — which the daemon may have
     * already applied — so the new rev was never learned, the next write went out
     * on the old one, and every burst of typing that outran a round trip came back
     * 409. The editor's answer to a 409 is to adopt the server's copy, so the
     * symptom was your own paragraph disappearing mid-sentence.
     *
     * Each write also joins the one before it: two PATCHes in flight against the
     * same rev means the second is refused by construction.
     */
    private var writeJob: Job? = null

    /** The newest (id, rev) any write has come back with — see [rebase]. */
    private var latest: Pair<String, Int>? = null

    /**
     * A freshly fetched page becomes the open one.
     *
     * Content is fetched on open and never polled, so this is not a merge point:
     * whatever was still in the air lands under its own id first, and then the new
     * page replaces it wholesale.
     */
    fun open(fresh: Scratchpad) {
        landPending()
        _pad.value = fresh
        _state.value = State.IDLE
        _note.value = null
    }

    /** A keystroke. Debounced: a write per character is a lot of traffic for nothing. */
    fun set(text: String) {
        val current = _pad.value ?: return
        if (current.content == text) return
        val next = current.copy(content = text)
        _pad.value = next
        pending = next
        _state.value = State.PENDING
        timerJob?.cancel()
        timerJob = scope.launch {
            delay(debounceMs)
            landPending()
        }
    }

    /** Leaving the view: land whatever is still in the air, now. */
    fun flush() = landPending()

    /** The editor is gone. Lands the last write and then holds no page at all. */
    fun close() {
        landPending()
        _pad.value = null
        _state.value = State.IDLE
        _note.value = null
    }

    /**
     * The page was deleted, or the daemon lost it: drop everything, INCLUDING the
     * write in the air. Recreating a page somebody has just deleted, out of a
     * timer, is the one outcome worse than losing the last sentence.
     */
    fun forget() {
        timerJob?.cancel()
        timerJob = null
        writeJob?.cancel()
        writeJob = null
        pending = null
        _pad.value = null
        _state.value = State.IDLE
        _note.value = null
    }

    /**
     * The write is sent on the NEWEST rev this client knows of, not the one that
     * was current when the key was pressed.
     *
     * `pending` is built at keystroke time and a write may have answered since; a
     * PATCH on a superseded rev is refused, and this editor's answer to a refusal
     * is to adopt the server's copy — so a stale rev here reads to the person as
     * their own paragraph vanishing. [latest] is the fallback for the one case
     * `_pad` cannot answer: a write that outlives the editor being closed.
     */
    private fun rebase(p: Scratchpad): Scratchpad {
        val live = _pad.value
        if (live != null && live.id == p.id) return p.copy(rev = live.rev)
        val known = latest
        return if (known != null && known.first == p.id) p.copy(rev = known.second) else p
    }

    private fun landPending() {
        timerJob?.cancel()
        timerJob = null
        val held = pending ?: return
        pending = null
        val previous = writeJob
        writeJob = scope.launch {
            // Single-file, so a burst of typing cannot put two PATCHes on the same
            // rev — the second of which the daemon would be right to refuse.
            previous?.join()
            persist(rebase(held))
        }
    }

    private suspend fun persist(p: Scratchpad) {
        _state.value = State.SAVING
        val result = runCatching { save(p.id, p.rev, p.content) }
        result.onSuccess { r ->
            latest = r.pad.id to r.pad.rev
            // The editor has moved on. The write still landed — that is the point
            // of capturing the id — but nothing on screen is about this page any
            // more, so nothing on screen changes.
            if (_pad.value?.id != p.id) return@onSuccess
            if (r.conflict) {
                _pad.value = r.pad
                pending = null
                _note.value = OTHER_DEVICE
                _state.value = State.SAVED
                return@onSuccess
            }
            // Only the REV is news. Whatever has been typed since the write went
            // out is still the truth on this screen, and taking the server's copy
            // of the content here would delete it a keystroke at a time.
            _pad.value = _pad.value?.copy(rev = r.pad.rev)
            _state.value = if (pending == null) State.SAVED else State.PENDING
        }.onFailure { e ->
            if (e is CancellationException) throw e
            if (_pad.value?.id != p.id) return@onFailure
            // HELD, not dropped. The next keystroke or the next flush retries it,
            // which is the whole difference between a network blip and lost work.
            pending = p
            _state.value = State.FAILED
            _note.value = e.message ?: "could not save that"
        }
    }

    companion object {
        /** DraftBook's number, for the same reason: one write per pause in typing. */
        const val DEBOUNCE_MS: Long = 400

        /**
         * What a 409 means in words. Quiet on purpose — the other device saving is
         * a normal thing to have happened, and the text on screen is now theirs.
         */
        const val OTHER_DEVICE: String = "Updated on another device"
    }
}
