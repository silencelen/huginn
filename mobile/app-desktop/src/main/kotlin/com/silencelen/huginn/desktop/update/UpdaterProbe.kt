package com.silencelen.huginn.desktop.update

import com.silencelen.huginn.update.GithubReleases
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * Drives [DesktopUpdater] once, headlessly, against the REAL GitHub releases, and
 * prints what happened.
 *
 * This exists because the unit tests cannot prove the thing that actually breaks.
 * They prove semver, parsing, hashing and release-picking against fixtures; what
 * they cannot prove is that the published release carries the manifest the script
 * wrote, under the asset names it claims, and that the bytes GitHub serves hash
 * to what the manifest says. Every one of those is a different system's opinion,
 * and the only way to check they agree is to make the request.
 *
 * Run by scripts/release-desktop.sh as its last gate, AFTER the GitHub release is
 * published — verifying through the client rather than through curl, because curl
 * is not what will be running on the owner's machine.
 *
 * Usage: [--current <version>] [--platform <key>] [--cache-dir <dir>]
 *        [--expect <version>] [--repo <owner/name>]
 */
object UpdaterProbe {

    @JvmStatic
    fun main(args: Array<String>) {
        val opts = parse(args)

        // Defaults to a version older than anything ever released, so the probe
        // genuinely exercises the download + verify path instead of reporting
        // "up to date" and proving nothing.
        val current = opts["current"] ?: "0.0.0"
        val platform = opts["platform"] ?: UpdatePlatform.current() ?: fail("unknown platform")
        val cacheDir = File(opts["cache-dir"] ?: DesktopUpdater.defaultCacheDir().path)
        val repo = opts["repo"] ?: GithubReleases.REPO

        println("[probe] installed=$current platform=$platform cache=$cacheDir")
        println("[probe] source: github.com/$repo releases (${GithubReleases.DESKTOP_TAG_PREFIX}*)")

        val updater = DesktopUpdater(
            currentVersion = current,
            platform = platform,
            feed = GithubReleases(repo = repo),
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
                    fail("expected version $want, release offered ${state.version}")
                }
                println("[probe] OK")
                // EXPLICIT. The Ktor/OkHttp client this built holds a dispatcher
                // whose threads outlive `main`, so returning normally leaves the
                // JVM sitting there for the better part of a minute after saying
                // OK — which, inside a release gate, is indistinguishable from a
                // hang and invites someone to kill the release.
                exitProcess(0)
            }
            is UpdateState.UpToDate -> fail("release had nothing newer than $current — the probe proved nothing")
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
