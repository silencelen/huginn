package com.silencelen.huginn.ui

import com.silencelen.huginn.data.ModelChoice

/**
 * How a model and an effort level are written for a person, and what the picker
 * offers when the host's own list has not arrived.
 *
 * In `:core` because the two clients must not disagree about it. The phone still
 * carries its own copies in `ui/SessionControls.kt` (`prettyModel`,
 * `prettyEffort`, `modelOptions`, `FALLBACK_MODELS`); those are the ones to
 * delete when the phone is next touched, not this.
 */
object ModelLabels {

    /** Used only until the host's own list arrives, and if discovery ever fails. */
    val FALLBACK_MODELS: List<Pair<String, String>> = listOf(
        "fable" to "Fable",
        "opus" to "Opus",
        "sonnet" to "Sonnet",
        "haiku" to "Haiku",
    )

    val EFFORTS: List<String> = listOf("low", "medium", "high", "xhigh", "max")

    /** `xhigh` reads as `Xhigh`; an unknown or missing level falls back to the word. */
    fun effort(effort: String?): String =
        effort?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: "Effort"

    /**
     * The label for the model control.
     *
     * Both sources already carry the version — the pane writes "Opus 5" and the
     * server formats ids into "Opus 4.8" — so this must NOT collapse them to a
     * family name. The phone's did once, which is why the control said "Opus"
     * when the difference between Opus 5 and Opus 4.8 is the whole question.
     */
    fun model(model: String?): String = model?.trim()?.takeIf { it.isNotEmpty() } ?: "Model"

    fun options(models: List<ModelChoice>): List<Pair<String, String>> =
        if (models.isEmpty()) FALLBACK_MODELS else models.map { it.id to it.display }

    /** The effort menu, as (value, label) pairs like [options]. */
    fun effortOptions(): List<Pair<String, String>> =
        EFFORTS.map { it to it.replaceFirstChar { c -> c.uppercase() } }
}
