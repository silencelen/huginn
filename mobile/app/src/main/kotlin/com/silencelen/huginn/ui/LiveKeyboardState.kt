package com.silencelen.huginn.ui

/**
 * What the hidden live-typing field last showed us, and what one change to it
 * means in keystrokes.
 *
 * ⚠⚠ THIS EXISTS BECAUSE A FAST BURST WAS DUPLICATED. The field used to snap
 * back to [LiveInput.SENTINEL] inside `onValueChange` and diff every change
 * against that constant. A snap-back is a state write that has to travel through
 * recomposition and back out to the IME; an IME that delivered its next change
 * first was still working from the grown buffer, so the diff read the whole
 * buffer as new again. `ZZZZZ` typed in one burst arrived as fifteen Z —
 * 1+2+3+4+5 — into a live Claude Code prompt, which is a command the person did
 * not write.
 *
 * The fix is to stop having a baseline that can be stale: whatever the field
 * says now is diffed against whatever it said last, so a change mid-burst means
 * exactly the characters it added no matter when the reset lands. The snap-back
 * then becomes a housekeeping job that can wait for quiet rather than a
 * correctness requirement racing every keystroke.
 *
 * ⚠ THE ONE PLACE IT STILL HAS TO BE IMMEDIATE is an emptied field. The sentinel
 * is what makes backspace visible at all — with nothing in the box most IMEs
 * express a delete as no change whatsoever — so once it has been consumed the
 * runway is put back at once. That reset is safe in a way the old one was not:
 * the only edit an IME can still be holding against an empty field is another
 * delete, and an empty field diffed against a restored sentinel reads as exactly
 * that, one more backspace.
 *
 * Not a composable and not Android-shaped on purpose: this is the part with a
 * failure mode, so it is the part that gets a unit test.
 */
class LiveKeyboardState {

    /** The text the field last reported, and the baseline the next change is read against. */
    var seen: String = LiveInput.SENTINEL
        private set

    /** Reads one field change as keystrokes and takes it as the new baseline. */
    fun change(next: String): LiveInput.Typed {
        val typed = LiveInput.diff(seen, next)
        seen = next
        return typed
    }

    /**
     * True when the sentinel has been deleted, so there is nothing left for the
     * next backspace to consume. The caller must put the runway back NOW.
     */
    val needsRunway: Boolean get() = !seen.startsWith(LiveInput.SENTINEL)

    /** Back to the resting value. Returns what the field should be set to. */
    fun reset(): String {
        seen = LiveInput.SENTINEL
        return LiveInput.SENTINEL
    }
}

/**
 * How long the field must be quiet before it is snapped back to the sentinel.
 *
 * Long enough that no burst — gesture typing, an autocorrect replacement, a
 * clipboard paste, a dictation commit — is still in flight when the reset goes
 * out, which is the whole reason the old per-change reset was a race. Short
 * enough that the hidden buffer never grows past a sentence.
 */
const val LIVE_SNAPBACK_QUIET_MS: Long = 400L
