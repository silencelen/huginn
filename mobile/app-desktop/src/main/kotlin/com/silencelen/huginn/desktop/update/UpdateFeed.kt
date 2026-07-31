package com.silencelen.huginn.desktop.update

/**
 * WHERE updates come from, and the answer is: not from anywhere the user can
 * type.
 *
 * This is a security boundary, not a convenience. These builds are UNSIGNED —
 * there is no Authenticode certificate and no notarisation — so the only thing
 * standing between this app and arbitrary code execution on the owner's Windows
 * machine is that it will fetch an installer from these addresses and no others.
 * The app's ordinary server setting is user-editable (it has to be: routes change
 * with which VPN holds the tunnel slot), and deriving the feed from it would mean
 * one typo'd address in Settings is enough to hand a stranger the "download and
 * run this .exe" primitive. `DESKTOP-MIGRATION.md`, carry-over list, security:
 * *"The update feed must be pinned, never derived from a user setting."*
 *
 * There are TWO pinned bases rather than one for the same reason the app has two
 * routes — only one VpnService can hold the tunnel at a time, so the tailnet
 * address is unreachable while Yggdrasil is up and vice versa. Both are compile-time
 * constants, which is what "pinned" means here; the count is not the point, the
 * unwritability is.
 *
 * A CHANNEL OF ITS OWN, separate from `/v1/desktop`. That path is the Electron
 * client's feed and the owner is running Electron 0.4.0 against it right now.
 * Publishing Compose artifacts there would hand a running application an
 * "update" that is a different program.
 */
object UpdateFeed {

    /** The Compose desktop channel. NOT `/v1/desktop` — see the class note. */
    const val PATH: String = "/v1/desktop-kt"

    val PINNED_BASES: List<String> = listOf(
        "http://100.97.198.90:8787", // huginn, tailnet
        "http://192.168.2.117:8787", // huginn, VLAN 2 via the yggdrasil mesh gateway
    )

    /**
     * Mirrors the daemon's own `NAME_RE` (server/appd/lib/desktop.js). The server
     * validates too — this is not a substitute for that — but a name that came
     * back inside a manifest is still untrusted input, and it is about to become
     * a path on the LOCAL disk and then be EXECUTED. Refusing separators here
     * means a hostile or corrupted manifest cannot steer the download out of the
     * cache directory.
     */
    private val SAFE_NAME = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]{1,80}$""")

    fun isPinned(base: String): Boolean = normalize(base) in PINNED_BASES

    fun isSafeArtifactName(name: String): Boolean = SAFE_NAME.matches(name)

    /**
     * @throws IllegalArgumentException when [base] is not one of [PINNED_BASES].
     *   Throwing rather than falling back to a default is deliberate: a caller
     *   that got here with an arbitrary address has a bug, and a silent
     *   substitution would hide it until the day the substitution stopped.
     */
    fun manifestUrl(base: String): String {
        require(isPinned(base)) { REFUSED }
        return normalize(base) + PATH + "/manifest"
    }

    /** @throws IllegalArgumentException on an unpinned base or an unsafe name. */
    fun artifactUrl(base: String, name: String): String {
        require(isPinned(base)) { REFUSED }
        require(isSafeArtifactName(name)) { "refusing artifact name: $name" }
        return normalize(base) + PATH + "/" + name
    }

    private fun normalize(base: String): String = base.trim().trimEnd('/')

    const val REFUSED: String =
        "refusing that update feed — huginn only updates from its own pinned channel"
}
