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
enum class DeviceScope { GENERATE, LOOK, WORK, OWN }

object DevicePolicy {

    fun parse(raw: String?): DeviceScope = when (raw?.trim()?.lowercase()) {
        "own" -> DeviceScope.OWN
        "work" -> DeviceScope.WORK
        "look" -> DeviceScope.LOOK
        // Anything unrecognised is the FLOOR — generate, the exclusive rung. A
        // junk scope can run nothing a claude device runs, and no claude engine
        // will ever serve generate, so a typo in a settings file is a dead row
        // rather than a privilege escalation in either direction.
        else -> DeviceScope.GENERATE
    }

    fun wire(scope: DeviceScope): String = scope.name.lowercase()

    private val SESSION_ID = Regex("^[0-9a-fA-F-]{36}$")

    private fun rank(scope: DeviceScope): Int = DevicePolicyTable.SCOPES.indexOf(wire(scope))

    /**
     * An EXCLUSIVE scope (generate) matches only itself, in both directions: a
     * generate device runs only generate work, and generate work runs only on a
     * generate device. Rank ordering alone would let `own` satisfy generate —
     * and this machine would spawn claude to answer a local-model request, the
     * silent engine substitution the policy bans both ways.
     */
    private fun exclusive(scope: DeviceScope): Boolean =
        DevicePolicyTable.EXCLUSIVE_SCOPES.contains(wire(scope))

    /**
     * The scope in force right now.
     *
     * A locked machine drops to `look`. Not because a lock screen is a security
     * boundary — it is not — but because nobody is sitting there. A full-scope run
     * with its owner watching is a different proposition from the same run at 3am,
     * and this process is the only one that knows which is true.
     */
    fun effective(scope: DeviceScope, locked: Boolean): DeviceScope =
        // Exclusive scopes ignore the lock drop: a generate run mutates nothing,
        // so a lock has nothing to withdraw — and dropping generate to look would
        // sideways-GRANT ask, a claude mode this row has no engine for.
        if (locked && !exclusive(scope)) parse(DevicePolicyTable.LOCK_DROPS_TO) else scope

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
        val n = parse(need)
        if (exclusive(n) || exclusive(scope)) return scope == n
        return rank(scope) >= rank(n)
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
        // The lock is blamed only when unlocking would actually HELP — the
        // enrolled scope allows the mode and only the lock-drop is in the way.
        // Everything else names the scope, because the scope is what a person
        // would have to change.
        return if (locked && allows(scope, mode)) {
            DevicePolicyTable.REFUSAL_LOCKED
        } else {
            DevicePolicyTable.REFUSAL_SCOPE
                .replace("{scope}", wire(scope))
                .replace("{mode}", mode)
        }
    }

    /**
     * The engine fence — the one rule the policy table cannot hold, because it
     * cannot see which binary a runner would spawn. Generate work needs a local
     * model engine; a runner without one refuses, and never falls through to
     * claude. The Compose desktop runner NEVER has one — serving must survive
     * logout, so it is always the headless service's job — and passes
     * hasEngine = false unconditionally.
     */
    fun engineRefusal(mode: String, hasEngine: Boolean): String? =
        if (mode == "generate" && !hasEngine) DevicePolicyTable.REFUSAL_ENGINE else null

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
        // A granted generate argv is the bare stream flags plus the request: no
        // tool flags (the shim has no tool surface — absence is the fence), no
        // --effort, and NO persona — huginn's context never rides to a serving
        // box. A REFUSED generate row falls through and carries the look argv,
        // per the two-fences rule.
        if (work.mode == "generate" && allows(eff, "generate")) {
            val gen = DevicePolicyTable.STREAM_FLAGS.toMutableList()
            work.model?.takeIf { it.isNotBlank() }?.let { gen += listOf("--model", it) }
            work.resumeSessionId?.takeIf { SESSION_ID.matches(it) }?.let { gen += listOf("--resume", it) }
            return gen
        }
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
