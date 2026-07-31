package com.silencelen.huginn.desktop.update

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * Drives [DesktopUpdater] once, headlessly, against the REAL channel, and prints
 * what happened.
 *
 * This exists because the unit tests cannot prove the thing that actually breaks.
 * They prove semver, parsing, hashing and feed pinning against fixtures; what
 * they cannot prove is that the daemon serves the manifest the release script
 * wrote, at the path the client asks for, with the auth it sends, and that the
 * bytes that come back hash to what the manifest claims. Every one of those is a
 * different program's opinion, and the only way to check they agree is to make
 * the request.
 *
 * Run by scripts/release-desktop.sh as its last gate, after the wire check —
 * verifying through the client rather than through curl, because curl is not
 * what will be running on the owner's machine.
 *
 * Usage: --token-file <path> [--current <version>] [--platform <key>]
 *        [--cache-dir <dir>] [--expect <version>]
 */
object UpdaterProbe {

    @JvmStatic
    fun main(args: Array<String>) {
        val opts = parse(args)
        val tokenFile = opts["token-file"] ?: "/etc/huginn-appd/token"
        val token = runCatching { File(tokenFile).readText().trim() }.getOrElse {
            fail("cannot read token from $tokenFile: ${it.message}")
        }
        if (token.length < 16) fail("token in $tokenFile looks empty")

        // Defaults to a version older than anything that has ever been released,
        // so the probe genuinely exercises the download + verify path instead of
        // reporting "up to date" and proving nothing.
        val current = opts["current"] ?: "0.0.0"
        val platform = opts["platform"] ?: UpdatePlatform.current() ?: fail("unknown platform")
        val cacheDir = File(opts["cache-dir"] ?: DesktopUpdater.defaultCacheDir().path)

        println("[probe] installed=$current platform=$platform cache=$cacheDir")
        // Each base printed WITH the path — a joined list with the path appended
        // once reads as though only the last base carried it.
        println("[probe] pinned feeds: " + UpdateFeed.PINNED_BASES.joinToString(", ") { it + UpdateFeed.PATH })

        val updater = DesktopUpdater(
            currentVersion = current,
            tokenProvider = { token },
            platform = platform,
            cacheDir = cacheDir,
            // Never on this path. The probe proves fetch + verify; running an
            // installer is a user action and a headless gate must not be one.
            isWindows = false,
        )

        val state = runBlocking { updater.check() }
        when (state) {
            is UpdateState.Ready -> {
                println("[probe] READY ${state.version}")
                println("[probe]   file   ${state.file}")
                println("[probe]   bytes  ${state.file.length()}")
                println("[probe]   sha256 ${Sha256.ofFile(state.file)} (verified against the manifest)")
                val want = opts["expect"]
                if (want != null && want != state.version) {
                    fail("expected version $want, feed offered ${state.version}")
                }
                println("[probe] OK")
                // EXPLICIT. The Ktor/OkHttp client this built holds a dispatcher
                // whose threads outlive `main`, so returning normally leaves the
                // JVM sitting there for the better part of a minute after saying
                // OK — which, inside a release gate, is indistinguishable from a
                // hang and invites someone to kill the release.
                exitProcess(0)
            }
            is UpdateState.UpToDate -> fail("feed had nothing newer than $current — the probe proved nothing")
            is UpdateState.Error -> fail(state.message)
            else -> fail("ended in $state")
        }
    }

    private fun parse(args: Array<String>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a.startsWith("--") && i + 1 < args.size) {
                out[a.removePrefix("--")] = args[i + 1]; i += 2
            } else {
                fail("bad argument: $a")
            }
        }
        return out
    }

    private fun fail(message: String): Nothing {
        System.err.println("[probe] FAIL: $message")
        exitProcess(1)
    }
}
