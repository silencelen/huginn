package com.silencelen.huginn.device

import com.silencelen.huginn.data.DeviceWork

/**
 * What this machine is willing to let a remote request do to it.
 *
 * This is the enforcement, and it lives HERE rather than on the daemon on purpose.
 * The daemon sends a request; the machine that owns the file system decides what
 * that request becomes. If the daemon sent tool grants instead, then whoever held
 * its bearer token would hold this computer, and one leaked credential would stop
 * meaning "the homelab" and start meaning "my PC". Widening what a device will do
 * requires touching the device.
 *
 * Pure, so the argv a remote request turns into can be asserted in a test rather
 * than discovered in a log after the fact.
 */
enum class DeviceScope { LOOK, WORK, OWN }

object DevicePolicy {

    fun parse(raw: String?): DeviceScope = when (raw?.trim()?.lowercase()) {
        "own" -> DeviceScope.OWN
        "work" -> DeviceScope.WORK
        // Anything unrecognised is the NARROWEST, never the widest. A typo in a
        // settings file must not be a privilege escalation.
        else -> DeviceScope.LOOK
    }

    fun wire(scope: DeviceScope): String = scope.name.lowercase()

    /**
     * The scope in force right now.
     *
     * A locked machine drops to [DeviceScope.LOOK]. Not because a lock screen is a
     * security boundary — it is not — but because nobody is sitting there. A
     * full-scope run with its owner watching is a different proposition from the
     * same run at 3am, and this process is the only one that knows which is true.
     */
    fun effective(scope: DeviceScope, locked: Boolean): DeviceScope =
        if (locked) DeviceScope.LOOK else scope

    /** Whether a mode can run at this scope. `act` mutates; `look` does not permit that. */
    fun allows(scope: DeviceScope, mode: String): Boolean =
        if (mode == "act") scope != DeviceScope.LOOK else true

    /**
     * Why a request is being refused, in words a person can act on.
     *
     * Two different refusals need two different actions — unlock the machine, or
     * change what it is enrolled to do — and a single "not permitted" would leave
     * the reader guessing which.
     */
    fun refusal(scope: DeviceScope, locked: Boolean, mode: String): String? {
        val eff = effective(scope, locked)
        if (allows(eff, mode)) return null
        return if (locked) {
            "this machine is locked, so it is read-only until someone unlocks it"
        } else {
            "this machine is set to ${wire(scope)}, which cannot run $mode"
        }
    }

    /**
     * Read-only tools. Note what is NOT here: no Bash.
     *
     * Bash is denied rather than merely un-granted, which is the same lesson the
     * daemon's own ask mode learned the hard way — Claude Code's safe-Bash
     * classification is content-dependent, so two near-identical commands one
     * minute apart were approved and then refused. From the far end that reads as
     * a fence that works and then doesn't. Deny is deterministic.
     */
    private const val LOOK_ALLOWED = "Skill Read Glob Grep WebFetch WebSearch"
    private const val LOOK_DENIED = "Bash Edit Write NotebookEdit"

    private const val ACT_ALLOWED = "Skill Bash Read Edit Write Glob Grep WebFetch WebSearch"

    /**
     * The argv a work item becomes on this machine.
     *
     * @param root the directory a `work`-scoped run happens in.
     *
     * ⚠ WORK IS NOT A JAIL, and pretending otherwise would be worse than not
     * having it. The difference between `work` and `own` is the working directory
     * the run starts in, not a kernel boundary: a granted Bash can leave any
     * directory, and no flag Claude Code takes will stop it. `work` means "start
     * here, and this is what I expect you to touch"; `own` means "the machine".
     * The real fences are the scope gate above (which decides whether a mutating
     * run happens at all) and the lock rule.
     */
    fun argvFor(
        work: DeviceWork,
        scope: DeviceScope,
        locked: Boolean,
        root: String?,
        persona: String? = null,
    ): List<String> {
        val eff = effective(scope, locked)
        val act = work.mode == "act" && allows(eff, "act")
        val argv = mutableListOf(
            "-p", "--output-format", "stream-json", "--verbose", "--include-partial-messages",
        )
        work.model?.takeIf { it.isNotBlank() }?.let { argv += listOf("--model", it) }
        work.effort?.takeIf { it.isNotBlank() }?.let { argv += listOf("--effort", it) }
        work.resumeSessionId?.takeIf { it.isNotBlank() }?.let { argv += listOf("--resume", it) }
        persona?.takeIf { it.isNotBlank() }?.let { argv += listOf("--append-system-prompt", it) }
        argv += listOf("--allowedTools", if (act) ACT_ALLOWED else LOOK_ALLOWED)
        if (!act) argv += listOf("--disallowedTools", LOOK_DENIED)
        return argv
    }

    /** Where a run starts. `work` uses its declared root; anything else, the home dir. */
    fun cwdFor(scope: DeviceScope, locked: Boolean, root: String?, home: String): String {
        val eff = effective(scope, locked)
        val r = root?.trim()
        return if (eff == DeviceScope.WORK && !r.isNullOrEmpty()) r else home
    }
}
