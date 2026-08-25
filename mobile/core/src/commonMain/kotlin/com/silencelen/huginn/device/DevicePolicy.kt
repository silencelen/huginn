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
 * The RULES are here; the TABLE they read is [DevicePolicyTable], generated from
 * `shared/device-policy.json`. There is a second runner — Node, for headless
 * machines with no desktop app — which reads that same policy at runtime, and the
 * two are asserted against one shared case matrix. A device must not mean
 * something different depending on what kind of machine it is.
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

    private val SESSION_ID = Regex("^[0-9a-fA-F-]{36}$")

    private fun rank(scope: DeviceScope): Int = DevicePolicyTable.SCOPES.indexOf(wire(scope))

    /**
     * The scope in force right now.
     *
     * A locked machine drops to `look`. Not because a lock screen is a security
     * boundary — it is not — but because nobody is sitting there. A full-scope run
     * with its owner watching is a different proposition from the same run at 3am,
     * and this process is the only one that knows which is true.
     */
    fun effective(scope: DeviceScope, locked: Boolean): DeviceScope =
        if (locked) parse(DevicePolicyTable.LOCK_DROPS_TO) else scope

    /**
     * Whether a mode can run at this scope. `act` mutates; `look` does not permit that.
     *
     * ⚠ AN UNKNOWN MODE IS REFUSED, not mapped to a default. A Kotlin Map has no
     * prototype so the JS failure mode cannot happen here, but the old `?: "work"`
     * meant a mode this code has never heard of ran on any machine enrolled at
     * work or own. A mode it cannot reason about is one it must not run.
     */
    fun allows(scope: DeviceScope, mode: String): Boolean {
        val need = DevicePolicyTable.MODE_NEEDS[mode] ?: return false
        return rank(scope) >= rank(parse(need))
    }

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
            DevicePolicyTable.REFUSAL_LOCKED
        } else {
            DevicePolicyTable.REFUSAL_SCOPE
                .replace("{scope}", wire(scope))
                .replace("{mode}", mode)
        }
    }

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
        val argv = DevicePolicyTable.STREAM_FLAGS.toMutableList()
        work.model?.takeIf { it.isNotBlank() }?.let { argv += listOf("--model", it) }
        work.effort?.takeIf { it.isNotBlank() }?.let { argv += listOf("--effort", it) }
        // ⚠ A UUID, or nothing. `--resume` takes its value optionally, so a string
        // beginning with "--" does not become the session id — it becomes the next
        // FLAG. The value originates in an event the far end posted, and this
        // machine builds its own argv, so it validates rather than assuming.
        work.resumeSessionId?.takeIf { SESSION_ID.matches(it) }?.let { argv += listOf("--resume", it) }
        persona?.takeIf { it.isNotBlank() }?.let { argv += listOf("--append-system-prompt", it) }
        argv += listOf(
            "--allowedTools",
            if (act) DevicePolicyTable.ACT_ALLOWED else DevicePolicyTable.LOOK_ALLOWED,
        )
        // Denied as well as un-granted — see the note in shared/device-policy.json.
        // Claude Code's safe-Bash classification is content-dependent, so an
        // un-granted Bash is a fence that works and then does not.
        val denied = if (act) DevicePolicyTable.ACT_DENIED else DevicePolicyTable.LOOK_DENIED
        if (denied.isNotEmpty()) argv += listOf("--disallowedTools", denied)
        return argv
    }

    /** Where a run starts. `work` uses its declared root; anything else, the home dir. */
    fun cwdFor(scope: DeviceScope, locked: Boolean, root: String?, home: String): String {
        val eff = effective(scope, locked)
        val r = root?.trim()
        return if (eff == DeviceScope.WORK && !r.isNullOrEmpty()) r else home
    }
}
