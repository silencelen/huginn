package com.silencelen.huginn.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

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
 * Everything else is the same discipline, and the invariants read the same way:
 *
 *  1. **Polled text never replaces newer local text.** This one has no revision
 *     to arbitrate with, so the GENERATION is the whole defence: the overview is
 *     polled every few seconds, a poll is read server-side before it is
 *     delivered, and one that was read before the save it crosses would type the
 *     old paragraph back over the new one. Callers capture [generation] BEFORE
 *     the fetch and hand it to [refresh], which drops anything older. The
 *     field-level "not while somebody is in it" guard stays as a floor.
 *  2. **A hold belongs to a SESSION.** Unsent fields and a failure are kept by
 *     name and survive switching tabs, so a write that failed for the session you
 *     have left is retried by the next flush rather than dropped. A failed write
 *     is re-held only where nothing newer is waiting.
 *  3. **The state line describes the session ON SCREEN**, read per session and
 *     republished on [open] — switching tabs mid-write must not leave the new
 *     one saying "Saving…" about the old one's write.
 *  4. **[scope] must outlive the view.** The flush that matters happens as the
 *     tab is torn down, so a scope owned by that tab is cancelled at the exact
 *     moment it has work to do.
 *  5. **The debounce and the write are separate jobs.** A keystroke cancels the
 *     timer, never a PATCH already in flight — cancelling the write is how the
 *     editor loses the sentence that was being saved.
 *
 * Threading is [ScratchpadSaver]'s: the public API is callable from any thread,
 * the bookkeeping runs on a single-parallelism [lane], and the published flows
 * are atomic StateFlows that the two text fields are bound to directly.
 */
class SessionMetaSaver(
    private val scope: CoroutineScope,
    private val save: suspend (name: String, goals: String?, notes: String?) -> SessionMeta,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val lane: CoroutineContext = ScratchpadSaver.serialLane(),
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

    fun clearNote() {
        _note.value = null
        onLane { open?.let { held(it).note = null } }
    }

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

    /**
     * The tick of local truth — see invariant 1. Bumped by a keystroke, by an
     * adopted copy, and by a write landing.
     */
    private val _gen = MutableStateFlow(0L)

    fun generation(): Long = _gen.value

    /** Captured so a write lands on the right session. Lane-only. */
    private var open: String? = null

    /**
     * What one FIELD still owes.
     *
     * Per field rather than per session, because the two are edited
     * independently and polled together: a keystroke in Goals must not stop the
     * poll from delivering what the other device just wrote into Notes. That is
     * the same argument as "only send the field you are editing", one layer up.
     */
    private class FieldState {
        /** Unsent text. Null means "not typed since the last write". */
        var held: String? = null

        /**
         * Generation of the newest local change to this field — a keystroke or an
         * adopted copy. Kept apart from [settled] because the two answer different
         * questions: this one decides whether a FAILED write's snapshot is still
         * the newest thing anybody has (invariant 2), and a write landing must not
         * make it look as though somebody typed.
         */
        var mark: Long = 0

        /** Generation at which a write carrying this field last landed. */
        var settled: Long = 0

        /** Writes carrying this field that have not answered yet. */
        var inFlight: Int = 0
    }

    /** What one session still owes, and what its line says. */
    private class Held {
        val goals = FieldState()
        val notes = FieldState()

        var line: State = State.IDLE
        var note: String? = null

        fun owes(): Boolean = goals.held != null || notes.held != null

        fun busy(): Boolean = goals.inFlight > 0 || notes.inFlight > 0
    }

    private val sessions = mutableMapOf<String, Held>()

    private var timerJob: Job? = null
    private var writeJob: Job? = null

    /**
     * A session's saved text becomes the open one.
     *
     * Whatever is still in the air lands under its OWN session first. Switching
     * sessions with an unsent sentence is the ordinary case here — the tab is left
     * by tapping another one — and dropping it would be losing work silently. Text
     * this client is still HOLDING for the session being opened outranks the copy
     * handed in: the hold is newer by construction.
     */
    fun open(name: String, meta: SessionMeta) = onLane {
        val h = held(name)
        // Read BEFORE landing: land() consumes the holds to issue them, and what
        // this session is still holding is a question about the moment it was
        // opened. Reading after put an empty editor over unsent notes.
        val heldGoals = h.goals.held
        val heldNotes = h.notes.held
        if (open != null && open != name) land()
        open = name
        _session.value = name
        _goals.value = heldGoals ?: meta.goals
        _notes.value = heldNotes ?: meta.notes
        if (heldGoals == null && heldNotes == null && !h.busy()) {
            h.line = State.IDLE
            h.note = null
        }
        h.goals.mark = tick()
        h.notes.mark = h.goals.mark
        _state.value = h.line
        _note.value = h.note
    }

    /**
     * The server's copy, adopted only where nothing local is newer.
     *
     * @param at the [generation] captured BEFORE the fetch that produced [meta].
     *   A poll read before a save landed but delivered after it is the exact shape
     *   of invariant 1, and no field-level guard can see it: by then the write has
     *   finished, the dirty set is empty and the state is no longer SAVING.
     */
    fun refresh(name: String, meta: SessionMeta, at: Long = generation()) = onLane {
        if (open != name) return@onLane
        val h = held(name)
        adopt(h.goals, at, _goals, meta.goals)
        adopt(h.notes, at, _notes, meta.notes)
    }

    /** One field of a poll, taken only where nothing local is newer than it. */
    private fun adopt(f: FieldState, at: Long, into: MutableStateFlow<String>, incoming: String) {
        // The floor, for a caller that captured no generation: a field with unsent
        // text in it, or one whose write has not answered, is never typed back
        // over. The generation is what catches the case those two cannot see — a
        // poll read before a save that has since landed.
        if (f.held != null || f.inFlight > 0) return
        if (maxOf(f.mark, f.settled) > at) return
        into.value = incoming
    }

    fun setGoals(text: String) = set(Field.GOALS, text)

    fun setNotes(text: String) = set(Field.NOTES, text)

    private fun set(field: Field, text: String) {
        // The published mirror of `open`, because this half runs on the caller's
        // thread: nothing typed with no session open is anybody's text.
        if (_session.value == null) return
        val flow = if (field == Field.GOALS) _goals else _notes
        if (flow.value == text) return
        // Published on the calling thread: these two flows are what the editors'
        // text fields are bound to, and a value that lands a dispatch later drops
        // characters under fast typing.
        flow.value = text
        _state.value = State.PENDING
        onLane {
            val name = open ?: return@onLane
            val h = held(name)
            val f = if (field == Field.GOALS) h.goals else h.notes
            f.held = text
            f.mark = tick()
            h.line = State.PENDING
            h.note = null
            _note.value = null
            timerJob?.cancel()
            timerJob = scope.launch(lane) {
                delay(debounceMs)
                land()
            }
        }
    }

    /** Leaving the view: land whatever is still in the air, now. */
    fun flush() = onLane { land() }

    /**
     * The editor is gone. Lands the last write, then holds nothing on screen —
     * the per-session holds survive, because a failed write is still owed.
     */
    fun close() = onLane {
        land()
        open = null
        _session.value = null
        _goals.value = ""
        _notes.value = ""
        _state.value = State.IDLE
        _note.value = null
    }

    /** The session is gone: drop everything, INCLUDING the write in the air. */
    fun forget() = onLane {
        timerJob?.cancel(); timerJob = null
        writeJob?.cancel(); writeJob = null
        open?.let { sessions.remove(it) }
        open = null
        _session.value = null
        _goals.value = ""
        _notes.value = ""
        _state.value = State.IDLE
        _note.value = null
    }

    // ------------------------------------------------------------- the lane

    private fun onLane(block: suspend () -> Unit): Unit {
        scope.launch(lane) { block() }
    }

    private fun held(name: String): Held = sessions.getOrPut(name) { Held() }

    private fun tick(): Long {
        val next = _gen.value + 1
        _gen.value = next
        return next
    }

    /**
     * Issues everything owed, one write per session, on the single chain — every
     * session and not just the open one, because a failure for a tab that has been
     * left is exactly the one nobody would notice.
     */
    private fun land() {
        timerJob?.cancel()
        timerJob = null
        for (name in sessions.keys.toList()) {
            val h = held(name)
            // ONLY the fields that were typed. Sending both would write whatever
            // this client last read into the field somebody is not editing — on
            // the other device, over the paragraph they just saved.
            val goals = h.goals.held
            val notes = h.notes.held
            if (goals == null && notes == null) continue
            val gen = maxOf(h.goals.mark, h.notes.mark)
            h.goals.held = null
            h.notes.held = null
            if (goals != null) h.goals.inFlight += 1
            if (notes != null) h.notes.inFlight += 1
            val previous = writeJob
            writeJob = scope.launch(lane) {
                previous?.join()
                persist(name, goals, notes, gen)
            }
        }
    }

    private suspend fun persist(name: String, goals: String?, notes: String?, gen: Long) {
        val h = held(name)
        h.line = State.SAVING
        if (open == name) _state.value = State.SAVING
        runCatching { save(name, goals, notes) }
            .onSuccess {
                if (goals != null) h.goals.inFlight -= 1
                if (notes != null) h.notes.inFlight -= 1
                // The write landed, so every poll that was read before now is a
                // photograph of the text as it was BEFORE it — invariant 1.
                val settled = tick()
                if (goals != null) h.goals.settled = settled
                if (notes != null) h.notes.settled = settled
                h.note = null
                h.line = if (h.owes()) State.PENDING else State.SAVED
            }
            .onFailure { e ->
                if (goals != null) h.goals.inFlight -= 1
                if (notes != null) h.notes.inFlight -= 1
                if (e is CancellationException) throw e
                // HELD, not dropped, and held for the SESSION: the next keystroke
                // or the next flush retries it. Only where nothing newer is
                // waiting — this snapshot is older than anything typed since it
                // went out, and putting it back would revert the field on the
                // server while the screen still shows the new words.
                if (goals != null && h.goals.held == null && h.goals.mark <= gen) h.goals.held = goals
                if (notes != null && h.notes.held == null && h.notes.mark <= gen) h.notes.held = notes
                h.note = e.message ?: "could not save that"
                h.line = State.FAILED
            }
        if (open == name) {
            _state.value = h.line
            _note.value = h.note
        }
    }

    companion object {
        /** ScratchpadSaver's number, for the same reason: one write per pause in typing. */
        const val DEBOUNCE_MS: Long = 400
    }
}
