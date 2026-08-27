package com.silencelen.huginn.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * The open scratchpad and the writing of it: debounced, revision-aware, and the
 * same set/flush/close discipline [DraftBook] enforces for composer text.
 *
 * There is no Save button. That is a deliberate choice about what a page IS —
 * somewhere to put a thought mid-sentence, on whichever device is in your hand —
 * and it is why the rules below are stated as INVARIANTS rather than as
 * behaviour. Every one of them is a way a paragraph could disappear with nothing
 * on screen having looked wrong at any point.
 *
 * ## The invariants
 *
 *  1. **Server text never replaces newer local text.** A fetch is a photograph of
 *     the page at the moment the daemon read it, and it can arrive AFTER a write
 *     that is newer than it. So [open] carries the [generation] the caller
 *     captured before its fetch, and this refuses a copy that is older than what
 *     it already holds — older by that mark, by an unsent hold, by a write in the
 *     air, or by revision. **Callers must capture [generation] BEFORE the fetch,
 *     not after it.**
 *  2. **A revision is only ever attached to the content it belongs to.** A save
 *     answers with the new rev, and stamping that onto text the write did not
 *     produce is the worst failure this class can have: the next autosave then
 *     writes stale text on a current rev, the daemon has no reason to refuse it,
 *     and the loss is silent and permanent. Every write records the generation of
 *     the content it carries, and both the stamp and the rebase are refused when
 *     the on-screen text has stopped descending from it.
 *  3. **A hold belongs to a PAGE, not to the editor.** Unsent text, a failure and
 *     the "too large" verdict are kept per page id and survive switching away, so
 *     a write that failed for a page you have left is still retried by the next
 *     flush instead of being dropped on the floor. A failed write is re-held only
 *     when nothing newer has been typed for that page — a stale snapshot that
 *     replaces newer text, or resurrects after a newer write landed, would revert
 *     the page on the server while the screen still shows the new words.
 *  4. **The state line describes the page ON SCREEN.** It is read per page and
 *     republished on [open], so switching pages mid-write cannot leave the new
 *     page saying "Saving…" about the old one's write.
 *  5. **A refusal the daemon will repeat is TERMINAL.** A 413 means this exact
 *     text is too big for the route; retrying it on every keystroke is a loop
 *     against a wall. The text stays HELD and the line says so, and the next edit
 *     — which is how a page gets smaller — clears it and tries again.
 *  6. **The pending write is cancelled on close, never merely superseded**, and
 *     [scope] must outlive the view: the flush that matters happens as the editor
 *     is torn down, which is the exact moment a scope owned by that editor is
 *     cancelled. Same reason DraftBook is built at app level.
 *  7. **A conflict is an ANSWER, not an error.** Two devices autosaving one page
 *     is the ordinary case, so a 409 hands back the winner's text and this adopts
 *     it with a quiet line — see [note]. Losing silently, or throwing, are both
 *     worse than being told.
 *
 * ## Threading
 *
 * The public API is callable from any thread. All of the bookkeeping — the per
 * page holds, the debounce timer and the write chain — runs on [lane], a
 * single-parallelism context, so none of those fields is ever touched by two
 * threads at once. The desktop builds this on `Dispatchers.Default` while Compose
 * calls `set`/`flush`/`open` from the UI thread; without the lane those maps and
 * jobs were plain unsynchronised fields under exactly that pattern.
 *
 * The four published flows are the EXCEPTION and deliberately so: [pad] is what
 * the editor's text field is bound to, so a keystroke has to reach it without a
 * dispatch hop or the field fights the person typing into it. They are
 * StateFlows, which are atomic; the confinement is about the mutable maps, not
 * about them.
 *
 * @param save the wire call. Injected rather than taking a client so the rules
 *   above can be asserted without a socket.
 * @param lane where the bookkeeping runs. Tests pass `EmptyCoroutineContext` so
 *   the scope's own (virtual-clock) dispatcher drives it.
 */
class ScratchpadSaver(
    private val scope: CoroutineScope,
    private val save: suspend (id: String, rev: Int, name: String?, content: String?) -> ScratchpadSave,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val lane: CoroutineContext = serialLane(),
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

    fun clearNote() {
        _note.value = null
        onLane { _pad.value?.id?.let { pageOf(it).note = null } }
    }

    /**
     * The tick of local truth: bumped by every local change this client makes to
     * any page — a keystroke, an adopted copy, a landed write.
     *
     * A caller captures this BEFORE it fetches and hands it back to [open], which
     * is the whole of invariant 1: a fetch that was read at generation 4 and
     * arrives at generation 7 is a photograph of a page that has moved.
     */
    private val _gen = MutableStateFlow(0L)

    fun generation(): Long = _gen.value

    /**
     * What one page still owes, and what its line says.
     *
     * Kept per PAGE and not per editor (invariant 3), and kept even when clean:
     * [mark] is how a fetch issued before this client's last write is recognised
     * as old, and a record dropped the moment a page went quiet would forget
     * exactly that. One small object per page touched this session; a person has
     * pages in the tens.
     */
    private class Page {
        /** Unsent text, with the rev it was typed against. */
        var held: Scratchpad? = null

        /** An unsent rename. */
        var heldName: String? = null

        /** The newest rev this client knows for the page, in its own lineage. */
        var rev: Int = 0

        /** Generation of the newest local change to this page. */
        var mark: Long = 0

        /**
         * Generation at which the text on screen stopped descending from what
         * this client wrote — an adopted fetch, or a conflict's copy. A write
         * issued before this may not stamp its rev (invariant 2).
         */
        var broke: Long = 0

        /** Writes issued for this page and not yet answered. */
        var inFlight: Int = 0

        var line: State = State.IDLE
        var note: String? = null

        /** The daemon refused this exact text and will again — see invariant 5. */
        var terminal: Boolean = false
    }

    private val pages = mutableMapOf<String, Page>()

    /**
     * One write, with the generation of the CONTENT it carries.
     *
     * [rev] is the fallback for a page this client has no revision of its own
     * for; the live rebase happens at send time and only within the lineage.
     */
    private class Write(
        val id: String,
        val text: String?,
        val name: String?,
        val rev: Int,
        val gen: Long,
    )

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
     * same rev means the second is refused by construction. A rename joins the
     * same chain, for the same reason — it PATCHes the same row with the same rev.
     */
    private var writeJob: Job? = null

    /**
     * A freshly fetched page becomes the open one — unless it is older than what
     * this client already holds (invariant 1).
     *
     * @param at the [generation] captured BEFORE the fetch that produced [fresh].
     *   Defaulted for callers that fetch and open in one breath, which cannot
     *   race themselves; anything that fetches over the network must capture.
     */
    fun open(fresh: Scratchpad, at: Long = generation()) = onLane {
        val page = pageOf(fresh.id)
        // Read BEFORE landing: land() consumes the holds, and whether this fetch
        // is old is a question about the moment it arrived.
        val older = page.mark > at ||
            page.held != null ||
            page.inFlight > 0 ||
            fresh.rev < page.rev
        val ours = page.held ?: _pad.value?.takeIf { it.id == fresh.id }

        // Whatever is still in the air lands under its OWN id first: content is
        // fetched on open and never polled, so this is not a merge point.
        land()

        // A stale fetch with nothing local to prefer is adopted WITH ITS OWN REV,
        // never with the newer one this client knows. The old rev is what makes
        // the next write conflict instead of overwriting the newer copy silently,
        // and a conflict is an answer the person gets to see.
        val shown = if (older) (ours ?: fresh) else fresh
        page.rev = if (shown === fresh) fresh.rev else maxOf(page.rev, shown.rev)
        page.mark = tick()
        page.broke = page.mark
        // A page with nothing owed has nothing to report: the server's copy IS
        // what is true, so an old line about a write that has since been settled
        // would be a claim about work that is over. A page that is still holding
        // something keeps its line — that is the point of holding it.
        if (!older) {
            page.line = State.IDLE
            page.note = null
        }
        _pad.value = shown
        _state.value = page.line
        _note.value = page.note
    }

    /** A keystroke. Debounced: a write per character is a lot of traffic for nothing. */
    fun set(text: String) {
        val current = _pad.value ?: return
        if (current.content == text) return
        val next = current.copy(content = text)
        // Published on the calling thread, ahead of the bookkeeping: the editor's
        // text field is bound to this flow, and a field whose value lands a
        // dispatch later drops characters under fast typing.
        _pad.value = next
        _state.value = State.PENDING
        onLane {
            val page = pageOf(next.id)
            page.held = next
            page.mark = tick()
            page.line = State.PENDING
            // New text is a new chance: whatever the daemon said about the last
            // version, it has not seen this one.
            page.terminal = false
            page.note = null
            if (showing(next.id)) _note.value = null
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
     * The editor is gone. Lands the last write and then holds no page at all.
     *
     * The per-page holds SURVIVE this: a write that failed for a page nobody is
     * looking at is still owed, and the next flush is what pays it (invariant 3).
     */
    fun close() = onLane {
        land()
        _pad.value = null
        _state.value = State.IDLE
        _note.value = null
    }

    /**
     * The page was deleted, or the daemon lost it: drop everything, INCLUDING the
     * write in the air and everything held for it. Recreating a page somebody has
     * just deleted, out of a timer, is the one outcome worse than losing the last
     * sentence.
     */
    fun forget() = onLane {
        timerJob?.cancel()
        timerJob = null
        writeJob?.cancel()
        writeJob = null
        _pad.value?.id?.let { pages.remove(it) }
        _pad.value = null
        _state.value = State.IDLE
        _note.value = null
    }

    /**
     * Renames a page ON THE WRITE CHAIN, and waits for the answer.
     *
     * ⚠ THIS IS WHY IT IS HERE rather than a PATCH of its own. A rename and an
     * autosave are the same row and the same rev, so a rename issued while a save
     * was in the air meant one of the two was refused: the autosave losing put the
     * server's older text back over what was being typed, the rename losing simply
     * did not happen. Joining the chain makes them a queue instead of a race.
     *
     * @param rev the revision the caller knows, used only when this client has
     *   never seen the page. The live rebase is this class's own.
     * @return the daemon's copy — including a CONFLICT's copy, which is an answer
     *   (invariant 7) and arrives with [note] set.
     */
    suspend fun rename(id: String, name: String, rev: Int): Result<Scratchpad> {
        val answer = CompletableDeferred<Result<Scratchpad>>()
        onLane {
            val page = pageOf(id)
            if (page.rev <= 0) page.rev = rev
            // The text first: it was typed before this was asked for, and it goes
            // out on its own rev rather than behind a rename that moved it.
            land()
            page.inFlight += 1
            page.line = State.SAVING
            if (showing(id)) _state.value = State.SAVING
            val write = Write(id, text = null, name = name, rev = page.rev, gen = tick())
            val previous = writeJob
            writeJob = scope.launch(lane) {
                previous?.join()
                answer.complete(persist(write))
            }.also { job ->
                // A cancelled scope must not leave the caller parked forever.
                job.invokeOnCompletion { cause ->
                    if (!answer.isCompleted) {
                        answer.complete(Result.failure(cause ?: CancellationException("the client went away")))
                    }
                }
            }
        }
        return answer.await()
    }

    // ------------------------------------------------------------- the lane
    //
    // Everything below runs on [lane] and nowhere else.

    private fun onLane(block: suspend () -> Unit): Unit {
        scope.launch(lane) { block() }
    }

    private fun pageOf(id: String): Page = pages.getOrPut(id) { Page() }

    private fun showing(id: String): Boolean = _pad.value?.id == id

    private fun tick(): Long {
        val next = _gen.value + 1
        _gen.value = next
        return next
    }

    /**
     * Issues everything owed, one write per page, on the single chain.
     *
     * EVERY page rather than just the open one: a failure for a page the editor
     * has left is still held, and this is the flush that retries it.
     */
    private fun land() {
        timerJob?.cancel()
        timerJob = null
        // Over a snapshot of the keys: a write answering can add a page record,
        // and this loop is not the place to discover that.
        for (id in pages.keys.toList()) {
            val page = pageOf(id)
            if (page.terminal) continue
            val text = page.held
            val name = page.heldName
            if (text == null && name == null) continue
            page.held = null
            page.heldName = null
            page.inFlight += 1
            val write = Write(id, text?.content, name, text?.rev ?: page.rev, page.mark)
            val previous = writeJob
            writeJob = scope.launch(lane) {
                // Single-file, so a burst of typing cannot put two PATCHes on the
                // same rev — the second of which the daemon would be right to
                // refuse.
                previous?.join()
                persist(write)
            }
        }
    }

    private suspend fun persist(w: Write): Result<Scratchpad> {
        val page = pageOf(w.id)
        val lineage = page.broke <= w.gen
        // The write goes out on the newest rev of ITS OWN lineage. A rev learned
        // from a copy this text does not descend from would turn a conflict the
        // daemon should refuse into an overwrite nobody sees (invariant 2).
        val rev = if (lineage) page.rev else w.rev
        page.line = State.SAVING
        if (showing(w.id)) _state.value = State.SAVING

        val result = runCatching { save(w.id, rev, w.name, w.text) }
        page.inFlight -= 1

        result.onSuccess { r ->
            page.rev = r.pad.rev
            if (r.conflict) {
                // The other device saved first, and their copy is what is true
                // now. The unsent tail goes with it — that is what losing a
                // conflict means — but never quietly: the line says so.
                page.held = null
                page.terminal = false
                page.note = OTHER_DEVICE
                page.line = State.SAVED
                page.broke = tick()
                page.mark = page.broke
                if (showing(w.id)) {
                    _pad.value = r.pad
                    _note.value = OTHER_DEVICE
                }
            } else {
                page.note = null
                page.line = if (page.held != null || page.heldName != null) State.PENDING else State.SAVED
                // Only the REV is news — and only for text this write produced.
                // Whatever has been typed since is still the truth on this screen,
                // and taking the server's copy of the content here would delete it
                // a keystroke at a time.
                val live = _pad.value
                if (live != null && live.id == w.id && lineage) {
                    // A rename's answer carries the name the daemon actually
                    // stored, which is the one it cleaned — so that half IS news.
                    _pad.value = live.copy(
                        rev = r.pad.rev,
                        name = if (w.name != null) r.pad.name else live.name,
                    )
                }
                if (showing(w.id)) _note.value = page.note
            }
        }.onFailure { e ->
            if (e is CancellationException) throw e
            val code = (e as? HuginnClient.HuginnException)?.code
            // HELD, not dropped, and held for the PAGE — the editor may have moved
            // on, and a page that has been left is exactly the one whose owner
            // will not notice the loss. Only when nothing newer is waiting, though:
            // this snapshot is older than anything typed since it went out.
            if (w.text != null && page.held == null && page.mark <= w.gen) {
                page.held = Scratchpad(id = w.id, content = w.text, rev = rev)
            }
            if (w.name != null && page.heldName == null) page.heldName = w.name
            // A 413 is the route's size cap, and the same text will meet the same
            // cap on every retry. Held, said out loud, and NOT retried until the
            // page changes — see invariant 5.
            page.terminal = code == 413
            page.note = if (code == 413) TOO_LARGE else (e.message ?: "could not save that")
            page.line = State.FAILED
            if (showing(w.id)) {
                _state.value = State.FAILED
                _note.value = page.note
            }
        }

        if (showing(w.id)) _state.value = page.line
        return result.map { it.pad }
    }

    companion object {
        /** DraftBook's number, for the same reason: one write per pause in typing. */
        const val DEBOUNCE_MS: Long = 400

        /**
         * What a 409 means in words. Quiet on purpose — the other device saving is
         * a normal thing to have happened, and the text on screen is now theirs.
         */
        const val OTHER_DEVICE: String = "Updated on another device"

        /**
         * What a 413 means in words. Actionable, because the only way out is the
         * person shortening the page — and the moment they do, the retry it names
         * happens on its own.
         */
        const val TOO_LARGE: String = "Too long to save — shorten the page and it saves itself"

        /**
         * One lane per saver: single-parallelism, so the bookkeeping is
         * single-threaded however many threads call in.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        internal fun serialLane(): CoroutineContext = Dispatchers.Default.limitedParallelism(1)
    }
}
