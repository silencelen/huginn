package com.silencelen.huginn.desktop.core

import com.silencelen.huginn.data.PinnedRoute
import com.silencelen.huginn.data.RouteKind
import com.silencelen.huginn.update.Semver
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.nio.file.Path

/**
 * Whether THIS RUN of the app goes through huginn core (owner decision
 * 2026-10-16, docs/design/desktop-via-core.md), decided once, before `AppStore`
 * builds its one `HuginnClient`: the client's engine is built once, so the
 * link is a property of the run, not of a moment.
 *
 * Through the core when it is running, new enough to carry everything
 * ([MIN_CORE]) and paired. On the way, an app that still holds its own link
 * hands it over:
 *  - the core not paired yet: the core is paired with this app's address and
 *    token (`/core/pair`; the core proves them, and keeps the token in its own
 *    secrets store);
 *  - this app's other addresses join the core's route book;
 *  - and only once a request THROUGH the core has worked are the token and
 *    pins deleted from this app's settings. A hand-over that cannot be proved
 *    leaves this run on its own link, to try again at the next start.
 *
 * Never migrated: a core paired with a DIFFERENT huginn than this app's (its
 * address is none of this app's addresses). Two installations on one machine
 * is a person's setup, not a migration's call; this run keeps its own link
 * and says why.
 */
object CoreBoot {
    /** The first core whose /appd proxy carries all of this app's traffic. */
    const val MIN_CORE = "2.5.1" // 2.5.0 carried it all; 2.5.1 also says which certificate it proved

    sealed interface Outcome {
        /** Through the core: [socket] carries every request. [note] says what was handed over, if anything. */
        data class ViaCore(val socket: Path, val coreVersion: String, val url: String, val note: String? = null) : Outcome

        /** This app's own link, this run. [why] is shown in Settings > Host and in diagnostics. */
        data class Direct(val why: String) : Outcome
    }

    /** The core's calls this decision makes ([CoreClient] in the app, a fake in tests). */
    interface Ops {
        val socket: Path
        fun present(): Boolean
        suspend fun status(): CoreStatus
        suspend fun pair(url: String, token: String)
        suspend fun addRoute(url: String, name: String, kind: String)
        suspend fun appdThroughCore(): Boolean
    }

    /** This app's own link, as settings hold it. */
    data class OwnLink(val baseUrl: String, val token: String, val routes: List<PinnedRoute>, val pins: List<String> = emptyList())

    /** At start: blocks briefly (a local socket; a first pairing proves huginn, which can take seconds). */
    fun decide(own: OwnLink, clearOwnCredentials: () -> Unit, timeoutMs: Long = 25_000): Outcome {
        // The way out if the core's link misbehaves on some machine: this run
        // keeps (or, after a hand-over, asks again for) a link of its own.
        if (System.getenv("HUGINN_DESKTOP_DIRECT") == "1") return Outcome.Direct("HUGINN_DESKTOP_DIRECT=1 keeps this app on its own link")
        val socket = CorePaths.socket() ?: return Outcome.Direct("this system has no place for huginn core's socket")
        val ops = opsFor(socket)
        // Wait out a core restart only where one is expected - an app with no token
        // of its own has no other link, and an installed service is coming back -
        // so a machine that simply has no core starts at once, as before.
        val waitMs = if (own.token.isBlank() || CoreSetup.serviceInstalled()) CORE_RESTART_WAIT_MS else 0L
        return runBlocking {
            withTimeoutOrNull(timeoutMs + waitMs) { decide(ops, own, waitMs, clearOwnCredentials) }
                ?: Outcome.Direct("huginn core did not answer within ${(timeoutMs + waitMs) / 1000} s")
        }
    }

    /**
     * How long a start waits for a core that is restarting. ⚠ THE UPDATE GAP
     * (2026-10-30): the new app's `self-install` restarts the core, which is gone
     * for up to ~16 s (its own shutdown, then systemd's RestartSec). A start in
     * that gap decided "not running" at once, took its own link - and, holding no
     * token since the hand-over, was dead for the whole run.
     */
    const val CORE_RESTART_WAIT_MS: Long = 30_000

    /** How often a waiting start looks for the core again. */
    private const val CORE_POLL_MS: Long = 250

    /** [Ops] over the core at [socket]. */
    internal fun opsFor(socket: Path): Ops {
        val core = CoreClient(socket)
        return object : Ops {
            override val socket = socket
            override fun present() = core.socketPresent()
            override suspend fun status() = core.status()
            override suspend fun pair(url: String, token: String) = core.pair(url, token)
            override suspend fun addRoute(url: String, name: String, kind: String) { core.addRoute(url, name, kind) }
            override suspend fun appdThroughCore() = core.appdThroughCore()
        }
    }

    /**
     * @param waitForCoreMs how long a core that is not there yet - or whose socket
     *   is up but not answering, mid-restart - is looked for again before this run
     *   goes its own way. Zero decides on the first look.
     */
    suspend fun decide(core: Ops, own: OwnLink, waitForCoreMs: Long = 0, clearOwnCredentials: () -> Unit): Outcome {
        val deadline = System.currentTimeMillis() + waitForCoreMs
        var st: CoreStatus? = null
        var lastError: String? = null
        while (true) {
            if (core.present()) {
                runCatching { core.status() }
                    .onSuccess { st = it }
                    .onFailure { lastError = it.message }
            }
            if (st != null || System.currentTimeMillis() >= deadline) break
            delay(CORE_POLL_MS)
        }
        val status = st ?: return Outcome.Direct(
            if (!core.present()) "huginn core is not running" else "huginn core did not answer: $lastError",
        )
        return decideWith(core, own, clearOwnCredentials, status)
    }

    private suspend fun decideWith(core: Ops, own: OwnLink, clearOwnCredentials: () -> Unit, st: CoreStatus): Outcome {
        if (Semver.parse(st.version) == null || Semver.isNewer(MIN_CORE, st.version)) {
            return Outcome.Direct("huginn core ${st.version.ifBlank { "(unknown)" }} is older than $MIN_CORE: huginn update brings it up to date")
        }
        val holdsOwn = own.token.isNotBlank() && own.baseUrl.isNotBlank()
        val notes = mutableListOf<String>()
        var url = st.url
        if (!st.paired) {
            if (!holdsOwn) return Outcome.Direct("huginn core is not paired with huginn yet")
            runCatching { core.pair(own.baseUrl, own.token) }
                .getOrElse { return Outcome.Direct("could not pair huginn core with this app's link (${it.message}); this run keeps its own") }
            url = own.baseUrl
            notes += "paired huginn core with ${own.baseUrl}"
        } else if (holdsOwn && !sameHuginn(st, own)) {
            return Outcome.Direct("huginn core is paired with ${st.url}, which is not this app's huginn (${own.baseUrl}); this run keeps its own link")
        }
        if (holdsOwn) {
            for (r in own.routes) {
                if (sameAddress(r.url, url)) continue
                if (runCatching { core.addRoute(r.url, r.name, kindName(r.kind)) }.isSuccess) notes += "added ${r.name.ifBlank { r.url }} to its route book"
            }
            if (!core.appdThroughCore()) {
                return Outcome.Direct("huginn did not answer through huginn core yet; this run keeps its own link, and tries the hand-over again at the next start")
            }
            clearOwnCredentials()
            notes += "this app no longer keeps a token of its own"
        }
        return Outcome.ViaCore(core.socket, st.version, url, notes.joinToString("; ").ifBlank { null })
    }

    /**
     * The same huginn: a certificate both proved (the same daemon on another
     * address - a LAN and a mesh address of one host), or failing pins on
     * either side, the core's address is one of this app's.
     */
    private fun sameHuginn(st: CoreStatus, own: OwnLink): Boolean {
        val mine = own.pins.map { it.lowercase() }.toSet()
        if (mine.isNotEmpty() && st.pins.isNotEmpty()) return st.pins.any { it.lowercase() in mine }
        return sameAddress(st.url, own.baseUrl) || own.routes.any { sameAddress(it.url, st.url) }
    }

    private fun sameAddress(a: String, b: String): Boolean {
        fun key(u: String) = runCatching { URI(u.trim().trimEnd('/')).let { "${it.host?.lowercase()}:${if (it.port < 0) 443 else it.port}" } }.getOrNull()
        val ka = key(a)
        return ka != null && ka == key(b)
    }

    private fun kindName(k: RouteKind): String = when (k) {
        RouteKind.LAN -> "lan"
        RouteKind.MESH -> "mesh"
        RouteKind.TAILNET -> "tailnet"
        else -> "custom"
    }
}
