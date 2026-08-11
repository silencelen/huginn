package com.silencelen.huginn.ui

/**
 * The multi-select card's reconciliation rule, extracted so it can be tested.
 *
 * The card keeps LOCAL checkbox state (nothing reaches the pane until Answer),
 * but the same dialog can also be toggled directly in tmux, and the pane frame
 * reports those checkboxes. The old behavior seeded local state once and never
 * looked again — so an external toggle was silently reverted by Answer. The
 * naive fix (re-seed every frame) stomps the selection being made right now.
 *
 * The rule: apply only the DELTA between two pane observations to the local set.
 * What changed on the pane since we last looked is external and wins; what the
 * pane still reports unchanged says nothing about local edits, which stand.
 */
object PromptChoices {
    fun mergeBaseline(prev: Set<Int>, next: Set<Int>, chosen: Set<Int>): Set<Int> {
        val newlyChecked = next - prev
        val newlyUnchecked = prev - next
        return (chosen + newlyChecked) - newlyUnchecked
    }
}
