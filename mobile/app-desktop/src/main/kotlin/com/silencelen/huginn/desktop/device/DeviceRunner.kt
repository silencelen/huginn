package com.silencelen.huginn.desktop.device

import com.silencelen.huginn.data.DeviceWork
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.desktop.DesktopSettings
import com.silencelen.huginn.device.DevicePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What this machine reports about being a device, for the Settings panel.
 *
 * `note` is deliberately one line of plain language rather than a code: every
 * state this can be in is one the owner may need to act on, and "not enrolled"
 * versus "enrolled, waiting for work" versus "claude was not found" are three
 * different actions.
 */
data class DeviceStatus(
    val enabled: Boolean = false,
    val deviceId: String? = null,
    val enrolled: Boolean = false,
    val busy: Boolean = false,
    val locked: Boolean = false,
    /**
     * Whether [locked] is something the probe SAID or the fence's default.
     *
     * ⚠ TWO FACTS AGAIN, and the same lesson as `locked`/`keep` below. A machine
     * with no logind session reports `locked = true` because that is the safe
     * answer, not because a screen is locked — and Settings used to choose its
     * sentence by asking `os.name`, so every LXC, container and `startx` box was
     * told to go and unlock a screen it does not have. See [LockProbe.Reading].
     */
    val lockKnown: Boolean = false,
    val note: String = "Off",
)

/**
 * Makes this machine available to Huginn as a place to run work.
 *
 * The transport is a pull: enrol, hold a long poll open, run what comes back,
 * post the results. Nothing listens on a port here — which is the reason this
 * works identically on a desktop in the next room and a laptop on hotel wi-fi,
 * and the reason "give Huginn access to this PC" needs no firewall change and no
 * inbound anything.
 *
 * The daemon sends a REQUEST. The argv is built here, from this machine's own
 * scope, by [DevicePolicy] — see that file for why the policy cannot live at the
 * other end.
 */
class DeviceRunner(
    private val client: HuginnClient,
    private val settings: DesktopSettings,
    private val scope: CoroutineScope,
    private val appVersion: String,
    private val hostName: String = defaultName(),
) {

    private val _status = MutableStateFlow(DeviceStatus())
    val status: StateFlow<DeviceStatus> = _status.asStateFlow()

    private var job: Job? = null

    /** The child currently running, so a cancel from the far end can reach it. */
    @Volatile
    private var current: Process? = null

    /** How many times the pending unenrol has been refused, for the backoff. */
    private var unenrolAttempts = 0

    /**
     * IDEMPOTENT, and that is the whole point.
     *
     * This is called from the app's 5-second poll so the runner self-heals if the
     * setting changes underneath it. The first version cancelled unconditionally
     * and started a fresh job — which meant the loop was torn down every five
     * seconds and could NEVER hold a 25-second long poll open. Measured against
     * the live host: 518 registrations in 45 minutes and 4 work polls, so every
     * job queued to that machine sat untouched until the daemon declared it
     * silent. It looked healthy the whole time, because registering and beating
     * are short requests that always succeeded.
     */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { supervise() }
    }

    fun stop() {
        job?.cancel()
        job = null
        // The child does not die with the coroutine: a cancelled `stream()` stops
        // reading, but the process it spawned keeps running with nobody listening.
        current?.destroy()
        _status.value = DeviceStatus(note = "Off")
    }

    private suspend fun supervise() {
        while (scope.isActive) {
            if (!settings.deviceEnabledNow()) {
                // Off is not always idle. A toggle-off owes the daemon a DELETE,
                // and this loop is what pays it — see retireIfOwed and [Unenrol].
                if (retireIfOwed()) continue
                _status.value = DeviceStatus(note = "Off")
                waitOrWake(IDLE_POLL_MS) { settings.deviceEnabledNow() }
                continue
            }
            try {
                serve()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never fatal. A daemon that is down, a token that was rotated, a
                // sleeping laptop — all of these are "try again shortly", and a
                // runner that gave up on the first failure would need the owner to
                // notice and restart the app.
                _status.value = _status.value.copy(
                    enrolled = false, busy = false,
                    note = "Not reaching huginn: ${short(e)}",
                )
                delay(15_000)
            }
        }
    }

    private suspend fun serve() {
        // ⚠ TWO FACTS, ONE WORD. `locked` is what the SCREEN is doing and is
        // reported honestly on every frame — the fleet list is entitled to know.
        // `keep` is the owner's standing answer to whether that withdraws
        // anything here, and [DevicePolicy.lockWithdraws] is where the two meet.
        // Collapsing them (reporting "not locked" because the setting is on)
        // would need no new field at all, and would make every surface describe
        // a locked machine as one nobody had locked.
        val reading = LockProbe.read()
        val locked = reading.locked
        val keep = settings.deviceActWhileLockedNow()
        val scopeWire = DevicePolicy.wire(DevicePolicy.parse(settings.deviceScopeNow()))
        val id = enrol(scopeWire, locked, keep)

        _status.value = DeviceStatus(
            enabled = true, deviceId = id, enrolled = true, locked = locked,
            lockKnown = reading.known,
            note = idleNote(locked, keep),
        )

        // The beat is separate from the poll because they answer different
        // questions: the poll asks "is there work", the beat says "I am still here
        // and this is what I will do now". A machine that only polled would look
        // present but never report that it had been locked.
        val beat = scope.launch {
            while (isActive) {
                delay(60_000)
                runCatching {
                    val probe = LockProbe.read()
                    val l = probe.locked
                    // Re-read rather than captured: the setting is a row somebody
                    // can flip while this loop is running, and the whole reason
                    // the beat exists is to say "this is what I will do NOW".
                    val k = settings.deviceActWhileLockedNow()
                    val r = client.deviceBeat(
                        id, locked = l, scope = scopeWire, version = appVersion, actWhileLocked = k,
                    )
                    _status.value = _status.value.copy(locked = l, lockKnown = probe.known)
                    // A Stop reaches a run sitting in a long QUIET tool HERE: the
                    // beat is the one channel still flowing when there are no event
                    // batches to carry the cancel on their ack. Kill the in-flight
                    // child; its finally posts the terminal frame, so the ending is
                    // still something this device SAYS, not something the daemon
                    // infers from a dropped connection.
                    if (r.cancel) current?.destroy()
                }
            }
        }

        try {
            var failures = 0
            while (scope.isActive && settings.deviceEnabledNow()) {
                // Caught HERE rather than letting it reach supervise(): a blip on
                // one poll is not a reason to re-enrol, and re-enrolling on every
                // hiccup is what turns a flaky link into a stream of registrations.
                val work = try {
                    val probe = LockProbe.read()
                    val w = client.pollWork(id, waitS = 25, locked = probe.locked)
                    failures = 0
                    // ⚠ CLEARING THE COUNTER IS NOT CLEARING THE SENTENCE, and for
                    // 45 minutes of a measured run it was mistaken for it. The note
                    // is only ever WRITTEN on the failure branch below, so the first
                    // blip in a session was the last word Settings had on this
                    // machine: "Retrying: Failed to connect to …" sat under
                    // "Available to huginn" while /work and /beat both returned 200
                    // continuously, and only a restart of the app took it off.
                    // A success owes the note, exactly as it owes the counter.
                    val idle = idleNote(probe.locked, settings.deviceActWhileLockedNow())
                    val next = noteAfterPoll(_status.value.note, idle)
                    if (next != _status.value.note || _status.value.locked != probe.locked) {
                        _status.value = _status.value.copy(
                            locked = probe.locked, lockKnown = probe.known, note = next,
                        )
                    }
                    w
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures += 1
                    if (failures >= 5) throw e          // genuinely broken: re-enrol
                    _status.value = _status.value.copy(note = RETRYING + short(e))
                    delay(3_000)
                    continue
                }
                if (work == null) continue
                runWork(id, work)
            }
        } finally {
            beat.cancel()
        }
    }

    /**
     * Pays off a pending unenrol, one attempt per pass.
     *
     * ⚠ THE RETRY IS THE POINT. The toggle going off is a local fact; the row it
     * created lives at the daemon and only a DELETE removes it. Doing that once,
     * at the moment of the click, loses to the commonest case there is — the
     * laptop being closed, the VPN being down, the daemon restarting — and the
     * row then sits "not reachable" for thirty days. So this runs while the
     * toggle is OFF (which is why [AppStore.syncDeviceRunner] keeps the runner
     * alive while a debt is outstanding) and keeps the id until the daemon says
     * the row is gone.
     *
     * @return true when this pass handled the debt and the caller should loop
     *   again immediately rather than fall through to the idle wait.
     */
    private suspend fun retireIfOwed(): Boolean {
        val step = Unenrol.step(settings.deviceUnenrolPendingNow(), settings.deviceIdNow())
        if (step == Unenrol.Step.IDLE) return false
        if (step == Unenrol.Step.SETTLE) {
            settings.clearDeviceUnenrolPending()
            return true
        }

        val id = settings.deviceIdNow()
        val failure: Exception? = try {
            client.deleteDevice(id)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }

        if (Unenrol.landed(failure)) {
            // Only now: the row is gone, so the handle has nothing left to hold.
            // A later toggle-on enrols fresh, and the daemon folds the new row
            // into the same machine by its machine key, so nothing reads as a
            // second computer.
            settings.setDeviceId("")
            settings.clearDeviceUnenrolPending()
            unenrolAttempts = 0
            _status.value = DeviceStatus(note = "Off")
            return true
        }

        _status.value = DeviceStatus(
            deviceId = id,
            note = Unenrol.note(step, failure?.let { short(it) }) ?: "Off",
        )
        // ⚠ NOT a plain delay. This backoff reaches five minutes, and it runs
        // inside the loop that also watches the toggle — so a flat wait meant
        // turning the device back ON did nothing at all for up to 300 seconds,
        // with Settings still reading "Off" and no way to tell whether the click
        // had registered. Sliced, so the answer to "I changed my mind" is a second.
        waitOrWake(Unenrol.backoffMs(unenrolAttempts)) { settings.deviceEnabledNow() }
        unenrolAttempts += 1
        return true
    }

    /**
     * What this machine says about itself while it is sitting there.
     *
     * Three states, not two. "Read-only while locked" is wrong on a machine whose
     * owner has turned that rule off, and it is exactly the line they would read
     * while wondering why the setting appears to have done nothing.
     */
    private fun idleNote(locked: Boolean, actWhileLocked: Boolean): String = when {
        !locked -> "Enrolled, waiting for work"
        actWhileLocked -> "Enrolled, locked — still acting, as this machine is set to"
        else -> "Enrolled, read-only while locked"
    }

    private suspend fun enrol(scopeWire: String, locked: Boolean, actWhileLocked: Boolean): String {
        val existing = settings.deviceIdNow().takeIf { it.isNotBlank() }
        val dev = client.registerDevice(
            name = hostName,
            platform = platform(),
            scope = scopeWire,
            id = existing,
            root = settings.deviceRootNow().takeIf { it.isNotBlank() },
            version = appVersion,
            locked = locked,
            machine = machineKey(hostName),
            // Always stated, never omitted: this build HAS an answer, and a
            // daemon that kept the last one it heard would go on offering act to
            // a machine whose owner had just taken the permission back.
            actWhileLocked = actWhileLocked,
        )
        if (dev.id != existing) settings.setDeviceId(dev.id)
        return dev.id
    }

    // ------------------------------------------------------------- one job

    private suspend fun runWork(deviceId: String, work: DeviceWork) {
        val enrolled = DevicePolicy.parse(settings.deviceScopeNow())
        // What the screen is doing (reported home on every frame below) and what
        // the POLICY is shown (the owner's standing answer applied to it).
        val reading = LockProbe.read()
        val locked = reading.locked
        val keep = settings.deviceActWhileLockedNow()
        val gate = DevicePolicy.lockWithdraws(locked, keep)

        // Refused HERE, by the machine, and said out loud. The daemon pre-checks
        // the same rule so a person is told at the point they ask — but this is the
        // check that actually decides, because this is the process holding the
        // file system.
        DevicePolicy.refusal(enrolled, gate, work.mode)?.let { why ->
            _status.value = _status.value.copy(
                note = "Refused a job: $why", locked = locked, lockKnown = reading.known,
            )
            runCatching {
                client.postWorkEvents(deviceId, work.id, emptyList(), done = true, exitCode = null,
                    error = why, locked = locked)
            }
            return
        }

        // This runner only ever spawns claude; serving local models is the
        // headless service's job (it must survive logout), so generate work is
        // refused here unconditionally rather than fed to the wrong engine.
        DevicePolicy.engineRefusal(work.mode, hasEngine = false)?.let { why ->
            _status.value = _status.value.copy(
                note = "Refused a job: $why", locked = locked, lockKnown = reading.known,
            )
            runCatching {
                client.postWorkEvents(deviceId, work.id, emptyList(), done = true, exitCode = null,
                    error = why, locked = locked)
            }
            return
        }

        val argv = DevicePolicy.argvFor(work, enrolled, gate, settings.deviceRootNow())
        val cwd = DevicePolicy.cwdFor(enrolled, gate, settings.deviceRootNow(),
            System.getProperty("user.home") ?: ".")

        // Refused rather than run somewhere else. See [cwdRefusal].
        cwdRefusal(hostName, cwd)?.let { why ->
            _status.value = _status.value.copy(
                note = "Refused a job: $why", locked = locked, lockKnown = reading.known,
            )
            runCatching {
                client.postWorkEvents(deviceId, work.id, emptyList(), done = true, exitCode = null,
                    error = why, locked = locked)
            }
            return
        }

        _status.value = _status.value.copy(
            busy = true, locked = locked, lockKnown = reading.known, note = "Running a job",
        )
        try {
            stream(deviceId, work, argv, cwd)
        } finally {
            current = null
            // The same three states serve() names, not two of them: a machine
            // that had just RUN something while locked would otherwise settle
            // back onto the line "read-only while locked".
            _status.value = _status.value.copy(busy = false, note = idleNote(locked, keep))
        }
    }

    private suspend fun stream(
        deviceId: String,
        work: DeviceWork,
        argv: List<String>,
        cwd: String,
    ) = withContext(Dispatchers.IO) {
        val proc = try {
            ProcessBuilder(listOf(claudeCommand()) + argv)
                .directory(File(cwd).takeIf { it.isDirectory })
                .redirectErrorStream(false)
                .start()
        } catch (e: Exception) {
            // The most likely first-run failure by a wide margin, and worth saying
            // precisely: the daemon shows this text in the chat, and "claude is not
            // on this machine's PATH" is actionable where "spawn failed" is not.
            val why = "could not start claude on $hostName: ${short(e)}"
            _status.value = _status.value.copy(note = why)
            client.postWorkEvents(deviceId, work.id, emptyList(), done = true, error = why)
            return@withContext
        }
        current = proc

        proc.outputStream.use { it.write(work.prompt.toByteArray()) }

        val pending = ArrayDeque<String>()
        var cancelled = false

        // Batched, not one long upload: a home network drops, and a dropped stream
        // is indistinguishable from a finished run. Every batch is also where a
        // cancel from the far end arrives, so the flush interval is the worst-case
        // latency of a stop button.
        val flusher = launch {
            while (isActive) {
                delay(500)
                val batch = synchronized(pending) {
                    if (pending.isEmpty()) emptyList() else pending.toList().also { pending.clear() }
                }
                if (batch.isEmpty()) continue
                try {
                    val ack = client.postWorkEvents(deviceId, work.id, batch)
                    if (ack.cancel && !cancelled) {
                        cancelled = true
                        proc.destroy()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isPermanentPostFailure(e)) {
                        // The daemon will NEVER accept this run again — a restart lost
                        // it (404), a rotated token (401/403), an over-cap batch (413).
                        // Retrying would hammer the same status twice a second for the
                        // life of the child, so stop; there is nothing left to deliver
                        // these lines to.
                        cancelled = true
                        proc.destroy()
                    } else {
                        // A transient blip (wifi drop, daemon restarting) must NOT
                        // silently eat the answer: the old code cleared `pending`
                        // before the post and dropped the batch on any failure. Put it
                        // back at the FRONT so the next flush re-sends it, in order,
                        // ahead of newer lines — mirroring the headless huginn-device.
                        synchronized(pending) {
                            val rest = pending.toList()
                            pending.clear()
                            pending.addAll(batch)
                            pending.addAll(rest)
                        }
                    }
                }
            }
        }

        proc.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                synchronized(pending) { pending.addLast(line) }
            }
        }
        val exit = runCatching { proc.waitFor() }.getOrDefault(-1)
        flusher.cancel()

        val tail = synchronized(pending) { pending.toList().also { pending.clear() } }
        runCatching {
            client.postWorkEvents(
                deviceId, work.id, tail,
                done = true,
                exitCode = exit,
                error = when {
                    cancelled -> "cancelled on $hostName"
                    exit != 0 -> "claude exited $exit on $hostName"
                    else -> null
                },
            )
        }
    }

    // ------------------------------------------------------------ platform

    private fun claudeCommand(): String =
        settings.deviceClaudePathNow().takeIf { it.isNotBlank() } ?: "claude"

    private fun platform(): String {
        val os = System.getProperty("os.name")?.lowercase().orEmpty()
        return when {
            os.contains("win") -> "windows"
            os.contains("linux") -> "linux"
            os.contains("mac") -> "macos"
            else -> "other"
        }
    }

    private fun short(e: Exception): String =
        (e.message ?: e::class.simpleName ?: "unknown").take(120)

    companion object {

        /**
         * What a poll that is failing says about itself, as a PREFIX rather than a
         * whole sentence: the reason follows it, and [noteAfterPoll] recognises the
         * note by it. Written once, so the writer and the clearer cannot drift.
         */
        const val RETRYING: String = "Retrying: "

        /**
         * The note after a poll that CAME BACK.
         *
         * Only a retry note is replaced. Everything else this field can hold is
         * either still true or belongs to a different writer — "Running a job" is
         * set by the work path either side of this call, "Refused a job: …" is the
         * last thing a refusal said and is not undone by the next poll, and the
         * idle sentence is already what this would write.
         */
        internal fun noteAfterPoll(previous: String, idle: String): String =
            if (previous.startsWith(RETRYING)) idle else previous

        /**
         * HTTP statuses on which a work-events POST is NOT worth retrying: the run
         * is gone (404), the token no longer authorises it (401/403), or the batch
         * is over the daemon's cap (400/413). Anything else — a timeout, a 5xx, a
         * dropped socket — is a transient blip whose batch is restored to the front.
         */
        /**
         * Why this job cannot start here, or null when it can.
         *
         * ⚠ CHECKED BEFORE THE SPAWN, because ProcessBuilder.directory(null)
         * means "inherit the JVM's own working directory" rather than "fail".
         * A `work`-scope device whose configured root no longer resolves — a
         * typo in the unvalidated Settings field, an unmounted drive, a renamed
         * folder, a stale drive letter — therefore ran the job in the app's own
         * install tree and reported SUCCESS: `ask` read and answered about the
         * wrong tree, `act` (Bash/Edit/Write, empty deny list) would have
         * written into it, and the Devices row went on printing the declared
         * root the whole time.
         *
         * The sentence is the headless runner's, verbatim
         * (`client/huginn-device`): the same device answering the same poll
         * must not explain itself differently depending on which runner picked
         * it up.
         */
        internal fun cwdRefusal(name: String, cwd: String): String? =
            if (File(cwd).isDirectory) null else "$name cannot start work: $cwd is not a directory"

        internal fun isPermanentPostFailure(e: Throwable): Boolean =
            e is HuginnClient.HuginnException && e.code in setOf(400, 401, 403, 404, 413)

        /** How long the disabled loop rests between passes. */
        const val IDLE_POLL_MS: Long = 2_000

        /**
         * How often a wait looks up to see whether it is still worth waiting.
         * A second: fast enough that a toggle feels like a toggle, slow enough
         * that five minutes of backoff is 300 checks of one boolean.
         */
        const val WAKE_SLICE_MS: Long = 1_000

        /**
         * Waits [totalMs] — but in slices, and gives up the moment [wake] is true.
         *
         * The whole point is that a long wait must not swallow a decision made
         * during it. A `delay(300_000)` is unreachable from the outside except by
         * cancelling the coroutine that holds it, and cancelling this one would
         * take the unenrol debt down with it.
         *
         * @return true when it woke early, false when it waited the whole time.
         */
        internal suspend fun waitOrWake(
            totalMs: Long,
            sliceMs: Long = WAKE_SLICE_MS,
            wake: () -> Boolean,
        ): Boolean {
            var left = totalMs
            while (left > 0) {
                // Asked BEFORE the first sleep: a toggle that flipped while the
                // last request was in flight is already true, and sleeping on it
                // once would be a second nobody needs to wait.
                if (wake()) return true
                val step = if (left < sliceMs) left else sliceMs
                delay(step)
                left -= step
            }
            return wake()
        }

        /**
         * The name that appears in the device list. The computer's own name, because
         * that is what the owner calls it — a uuid in a list of machines is a list
         * nobody can read.
         */
        fun defaultName(): String {
            val env = System.getenv("COMPUTERNAME")
                ?: System.getenv("HOSTNAME")
                ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
            return (env ?: "this machine").take(40)
        }

        /**
         * The daemon groups rows by this into one machine object (the claude
         * enrolment and a serving sibling fold into one device). Normalised
         * the way the daemon normalises, so a reported key and a derived one
         * for the same box can never disagree.
         */
        fun machineKey(host: String): String? =
            host.lowercase().replace(Regex("[^a-z0-9-]+"), "-")
                .replace(Regex("-+"), "-").trim('-').take(40).ifEmpty { null }
    }
}
