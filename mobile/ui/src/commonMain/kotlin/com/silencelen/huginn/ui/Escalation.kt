package com.silencelen.huginn.ui

/**
 * The handoff when a person ESCALATES a local chat to Claude.
 *
 * The other half of the Kvasir conduits, and USER-DRIVEN by design: a local
 * model never calls Claude itself. Escalating creates a NEW Claude chat whose
 * composer is pre-filled with this draft — the person reads it, edits it, and
 * sends it themselves. Nothing is auto-sent, and the local chat is left
 * exactly as it was (its transcript lives on its machine; a started chat
 * never changes engines).
 *
 * In `:ui` because both clients must write the same handoff — a fork here
 * would teach Claude two different framings of the same conversation.
 */
object Escalation {

    /** Recent context matters most, so the CAP keeps the TAIL. */
    const val CAP = 12_000

    fun draft(modelLabel: String, turns: List<Pair<String, String>>): String {
        val body = turns.joinToString("\n\n") { (role, text) -> "$role: ${text.trim()}" }
        val kept = if (body.length <= CAP) body else "…" + body.takeLast(CAP)
        return buildString {
            append("Continuing from a local chat with ").append(modelLabel).append(".\n")
            append("The conversation so far:\n\n")
            append(kept)
            append("\n\nPick this up from here: ")
        }
    }
}
