package com.silencelen.huginn.desktop.setup

import com.silencelen.huginn.settings.PreAnswers
import com.silencelen.huginn.settings.SetupFlow
import java.io.File

/**
 * THE INSTALLER'S ANSWERS, AND THE ONE FILE THAT CARRIES THEM.
 *
 * The Windows installer's components page asks which optional features somebody
 * wants; it installs none of them (the owner's rule — no elevation, nothing
 * extra on disk) and writes this file instead. The first launch reads it, hands
 * it to [SetupFlow], and deletes it.
 *
 * WHY A FILE AND NOT ARGV OR THE REGISTRY. `MUI_FINISHPAGE_RUN_PARAMETERS` was
 * the cheapest channel and is lost on a second launch and on the silent-update
 * path — which are exactly the two launches a fresh install often has before
 * anybody reads a screen. A registry value is Windows-only, so Linux would need
 * a second mechanism anyway. The settings directory is the one place BOTH
 * platforms already agree on, and the NSIS header documents that exact path
 * because its uninstaller already has to find `settings.json` there.
 *
 * ⚠ CONSUMED, NOT READ. The file is deleted once it has been turned into
 * answers, because a pre-answer that survives is a pre-answer that re-answers:
 * somebody who turned local serving OFF in Settings would find "Run setup again"
 * skipping the step forever on the strength of a tick they made at install time
 * eighteen months ago. Deletion failing is not an error — a read-only profile
 * still gets its answers; it merely gets them twice, which is recoverable.
 */
object FirstRun {

    /** ⚠ The name is a contract with `huginn-desktop-kt.nsi`, which writes it. */
    const val NAME: String = "first-run.json"

    fun file(configDir: File): File = File(configDir, NAME)

    /** The raw text, or null for absent/unreadable/empty. Never a throw. */
    fun read(configDir: File): String? {
        val f = file(configDir)
        if (!f.isFile) return null
        return runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * The answers, and the file gone.
     *
     * Every failure mode collapses to [PreAnswers.NONE]: no file, an empty file,
     * junk, a schema from a newer installer. The flow is perfectly usable
     * without pre-answers — it merely asks a question somebody already answered
     * — so refusing to launch, or showing an error about a hint, would be
     * absurd.
     */
    fun consume(configDir: File): PreAnswers {
        val raw = read(configDir)
        // Deleted whether or not it parsed: a file that cannot be read will not
        // start reading tomorrow, and leaving it means retrying the same junk on
        // every launch forever.
        if (raw != null || file(configDir).exists()) runCatching { file(configDir).delete() }
        return SetupFlow.parsePreAnswers(raw)
    }
}
