package com.silencelen.huginn.ui

import com.silencelen.huginn.data.ModelChoice

/**
 * How a model and an effort level are written for a person, and which rows a
 * picker offers at each SITE.
 *
 * In `:core` because the two clients must not disagree about it — and because
 * the local-model rules are exactly the kind of thing that must not fork: a
 * session picker offering a local model would be a fake control (a session chip
 * types `/model` keystrokes into a live Claude pane), and a chat picker hiding
 * them would hide the feature.
 */
object ModelLabels {

    /** Where a model menu is rendered; it decides which rows exist. */
    enum class PickerSite { SESSION, CHAT }

    /** Used only until the host's own list arrives, and if discovery ever fails. */
    val FALLBACK_MODELS: List<Pair<String, String>> = listOf(
        "fable" to "Fable",
        "opus" to "Opus",
        "sonnet" to "Sonnet",
        "haiku" to "Haiku",
    )

    val EFFORTS: List<String> = listOf("low", "medium", "high", "xhigh", "max")

    /** The reason the mode control is pinned on a local chat. One string, both platforms. */
    const val LOCAL_ASK_ONLY = "local models are Ask-only"

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
     * A local id shows its display name when the list carries it.
     */
    fun model(model: String?, models: List<ModelChoice> = emptyList()): String {
        val m = model?.trim()?.takeIf { it.isNotEmpty() } ?: return "Model"
        return models.firstOrNull { it.id == m }?.display?.takeIf { it.isNotBlank() } ?: m
    }

    /**
     * The rows a model menu offers.
     *
     * SESSION sites get Claude rows only — a session chip types `/model` into a
     * live pane, and a local row there could never work. CHAT sites also get
     * the AVAILABLE local rows (their display already names the machine, e.g.
     * "Qwen3 8B - DATATREEX"); an unreachable machine's rows are absent, the
     * same way an offline device reads in its own list — a pickable row that
     * always fails would be the fake control this object exists to forbid.
     *
     * The same rule is why a STARTED chat's menu shrinks: the daemon pins a
     * chat with history to its family and machine (the transcript lives where
     * it ran), so a started local chat offers only its own machine's rows and
     * a started Claude chat offers no machines at all. An unstarted chat may
     * re-decide freely — the daemon accepts that since 2.77.0 — so its menu
     * stays whole. Callers that do not know the chat's state keep the old
     * behaviour by omission, which is only correct for creation-time menus.
     */
    fun options(
        models: List<ModelChoice>,
        site: PickerSite,
        current: String? = null,
        started: Boolean = false,
    ): List<Pair<String, String>> {
        if (models.isEmpty()) return FALLBACK_MODELS
        val claude = models.filter { it.family != "local" }.map { it.id to it.display }
        if (site == PickerSite.SESSION) return claude.ifEmpty { FALLBACK_MODELS }
        if (started && isLocal(current, models)) {
            val host = models.firstOrNull { it.id == current }?.host
            val sameMachine = models.filter { it.family == "local" && it.available && it.host != null && it.host == host }
                .map { it.id to it.display }
            // The machine vanished from the catalog: the only honest menu is
            // the current row itself — nothing else can be switched to.
            return sameMachine.ifEmpty { listOf((current ?: "") to model(current, models)) }
        }
        if (started) return claude.ifEmpty { FALLBACK_MODELS }
        val local = models.filter { it.family == "local" && it.available }.map { it.id to it.display }
        return (claude.ifEmpty { FALLBACK_MODELS }) + local
    }

    /** Whether this chat's model is a local-family row (machine-pinned, Ask-only). */
    fun isLocal(model: String?, models: List<ModelChoice> = emptyList()): Boolean {
        val m = model?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (m.startsWith("local-")) return true
        return models.any { it.family == "local" && it.id == m }
    }

    /** The effort menu, as (value, label) pairs like [options]. */
    fun effortOptions(): List<Pair<String, String>> =
        EFFORTS.map { it to it.replaceFirstChar { c -> c.uppercase() } }
}
