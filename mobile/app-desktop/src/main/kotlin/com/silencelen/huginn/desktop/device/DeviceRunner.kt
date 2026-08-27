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
                delay(2_000)
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
        val locked = LockProbe.locked()
        val scopeWire = DevicePolicy.wire(DevicePolicy.parse(settings.deviceScopeNow()))
        val id = enrol(scopeWire, locked)

        _status.value = DeviceStatus(
            enabled = true, deviceId = id, enrolled = true, locked = locked,
            note = if (locked) "Enrolled, read-only while locked" else "Enrolled, waiting for work",
        )

        // The beat is separate from the poll because they answer different
        // questions: the poll asks "is there work", the beat says "I am still here
        // and this is what I will do now". A machine that only polled would look
        // present but never report that it had been locked.
        val beat = scope.launch {
            while (isActive) {
                delay(60_000)
                runCatching {
                    val l = LockProbe.locked()
                    client.deviceBeat(id, locked = l, scope = scopeWire, version = appVersion)
                    _status.value = _status.value.copy(locked = l)
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
                    val w = client.pollWork(id, waitS = 25, locked = LockProbe.locked())
                    failures = 0
                    w
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures += 1
                    if (failures >= 5) throw e          // genuinely broken: re-enrol
                    _status.value = _status.value.copy(note = "Retrying: ${short(e)}")
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
        delay(Unenrol.backoffMs(unenrolAttempts))
        unenrolAttempts += 1
        return true
    }

    private suspend fun enrol(scopeWire: String, locked: Boolean): String {
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
        )
        if (dev.id != existing) settings.setDeviceId(dev.id)
        return dev.id
    }

    // ------------------------------------------------------------- one job

    private suspend fun runWork(deviceId: String, work: DeviceWork) {
        val enrolled = DevicePolicy.parse(settings.deviceScopeNow())
        val locked = LockProbe.locked()

        // Refused HERE, by the machine, and said out loud. The daemon pre-checks
        // the same rule so a person is told at the point they ask — but this is the
        // check that actually decides, because this is the process holding the
        // file system.
        DevicePolicy.refusal(enrolled, locked, work.mode)?.let { why ->
            _status.value = _status.value.copy(note = "Refused a job: $why", locked = locked)
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
            _status.value = _status.value.copy(note = "Refused a job: $why", locked = locked)
            runCatching {
                client.postWorkEvents(deviceId, work.id, emptyList(), done = true, exitCode = null,
                    error = why, locked = locked)
            }
            return
        }

        val argv = DevicePolicy.argvFor(work, enrolled, locked, settings.deviceRootNow())
        val cwd = DevicePolicy.cwdFor(enrolled, locked, settings.deviceRootNow(),
            System.getProperty("user.home") ?: ".")

        _status.value = _status.value.copy(busy = true, locked = locked, note = "Running a job")
        try {
            stream(deviceId, work, argv, cwd)
        } finally {
            current = null
            _status.value = _status.value.copy(busy = false, note =
                if (locked) "Enrolled, read-only while locked" else "Enrolled, waiting for work")
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
                val ack = runCatching { client.postWorkEvents(deviceId, work.id, batch) }.getOrNull()
                if (ack?.cancel == true && !cancelled) {
                    cancelled = true
                    proc.destroy()
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
