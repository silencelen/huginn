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
 * The goals and notes kept beside a session, and the writing of them.
 *
 * A twin of [ScratchpadSaver] rather than a reuse of it, and the difference is
 * the whole reason: a pad is one text with a REVISION, and this is TWO texts with
 * none. The rev is what lets two devices autosave a pad safely; here the daemon
 * takes each field on its own and leaves the other alone, so the safety property
 * is "never send a field you are not editing" — which a rev cannot express and a
 * shared implementation would have to fake.
 *
 * Everything else is the same discipline, for the same reasons:
 *
 *  * **[scope] must outlive the view.** The flush that matters happens as the tab
 *    is torn down, so a scope owned by that tab is cancelled at the exact moment
 *    it has work to do.
 *  * **The debounce and the write are separate jobs.** A keystroke cancels the
 *    timer, never a PATCH already in flight — cancelling the write is how the
 *    editor loses the sentence that was being saved.
 *  * **A failed write is HELD.** The next keystroke or the next flush retries it.
 */
class SessionMetaSaver(
    private val scope: CoroutineScope,
    private val save: suspend (name: String, goals: String?, notes: String?) -> SessionMeta,
    private val debounceMs: Long = DEBOUNCE_MS,
) {

    enum class Field { GOALS, NOTES }

    enum class State { IDLE, PENDING, SAVING, SAVED, FAILED }

    private val _goals = MutableStateFlow("")
    val goals: StateFlow<String> = _goals.asStateFlow()

    private val _notes = MutableStateFlow("")
    val notes: StateFlow<String> = _notes.asStateFlow()

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _note = MutableStateFlow<String?>(null)

    /** One quiet line about something the state alone cannot say. */
    val note: StateFlow<String?> = _note.asStateFlow()

    fun clearNote() { _note.value = null }

    private val _session = MutableStateFlow<String?>(null)

    /**
     * Which session these texts belong to.
     *
     * Published because re-opening a session already held is how the editor
     * flashes: the caller's copy of the saved meta is whatever the last poll
     * returned, which after a save from this very client is the text the person
     * had BEFORE they typed. A caller that can ask holds its peace instead.
     */
    val session: StateFlow<String?> = _session.asStateFlow()

    /** Captured so a write lands on the right session. */
    private var open: String? = null

    /** Fields typed since the last write went out, with the session they belong to. */
    private val dirty = mutableSetOf<Field>()

    private var timerJob: Job? = null
    private var writeJob: Job? = null

    /**
     * A session's saved text becomes the open one.
     *
     * Whatever is still in the air lands under its OWN session first. Switching
     * sessions with an unsent sentence is the ordinary case here — the tab is left
     * by tapping another one — and dropping it would be losing work silently.
     */
    fun open(name: String, meta: SessionMeta) {
        if (open != null && open != name) landPending()
        open = name
        _session.value = name
        _goals.value = meta.goals
        _notes.value = meta.notes
        dirty.clear()
        _state.value = State.IDLE
        _note.value = null
    }

    /**
     * The server's copy, adopted only where nothing local is waiting.
     *
     * The overview is polled, so this arrives every few seconds while somebody may
     * be mid-sentence. A poll that overwrote a field being typed would be an
     * editor that types back.
     */
    fun refresh(name: String, meta: SessionMeta) {
        if (open != name) return
        if (Field.GOALS !in dirty && _state.value != State.SAVING) _goals.value = meta.goals
        if (Field.NOTES !in dirty && _state.value != State.SAVING) _notes.value = meta.notes
    }

    fun setGoals(text: String) = set(Field.GOALS, text)

    fun setNotes(text: String) = set(Field.NOTES, text)

    private fun set(field: Field, text: String) {
        if (open == null) return
        val flow = if (field == Field.GOALS) _goals else _notes
        if (flow.value == text) return
        flow.value = text
        dirty += field
        _state.value = State.PENDING
        timerJob?.cancel()
        timerJob = scope.launch {
            delay(debounceMs)
            landPending()
        }
    }

    /** Leaving the view: land whatever is still in the air, now. */
    fun flush() = landPending()

    /** The editor is gone. Lands the last write, then holds nothing. */
    fun close() {
        landPending()
        open = null
        _session.value = null
        _goals.value = ""
        _notes.value = ""
        _state.value = State.IDLE
        _note.value = null
    }

    /** The session is gone: drop everything, INCLUDING the write in the air. */
    fun forget() {
        timerJob?.cancel(); timerJob = null
        writeJob?.cancel(); writeJob = null
        dirty.clear()
        open = null
        _session.value = null
        _goals.value = ""
        _notes.value = ""
        _state.value = State.IDLE
        _note.value = null
    }

    private fun landPending() {
        timerJob?.cancel()
        timerJob = null
        val name = open ?: return
        if (dirty.isEmpty()) return
        // ONLY the fields that were typed. Sending both would write whatever this
        // client last read into the field somebody is not editing — on the other
        // device, over the paragraph they just saved.
        val goals = if (Field.GOALS in dirty) _goals.value else null
        val notes = if (Field.NOTES in dirty) _notes.value else null
        dirty.clear()
        val previous = writeJob
        writeJob = scope.launch {
            previous?.join()
            persist(name, goals, notes)
        }
    }

    private suspend fun persist(name: String, goals: String?, notes: String?) {
        _state.value = State.SAVING
        runCatching { save(name, goals, notes) }
            .onSuccess {
                if (open != name) return@onSuccess
                _state.value = if (dirty.isEmpty()) State.SAVED else State.PENDING
            }
            .onFailure { e ->
                if (e is CancellationException) throw e
                if (open != name) return@onFailure
                // HELD, not dropped: the next keystroke or the next flush retries.
                if (goals != null) dirty += Field.GOALS
                if (notes != null) dirty += Field.NOTES
                _state.value = State.FAILED
                _note.value = e.message ?: "could not save that"
            }
    }

    companion object {
        /** ScratchpadSaver's number, for the same reason: one write per pause in typing. */
        const val DEBOUNCE_MS: Long = 400
    }
}
