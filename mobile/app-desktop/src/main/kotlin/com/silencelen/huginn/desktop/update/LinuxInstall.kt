package com.silencelen.huginn.desktop.update

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * HOW THIS COPY OF THE APP WAS INSTALLED, on Linux — the question the updater has
 * to answer before it can say anything true about an update.
 *
 * Three shapes exist and they want three different answers:
 *
 * - **Portable** (`huginn desktop install`): the app-image unpacked per user under
 *   `$XDG_DATA_HOME/huginn-desktop/versions/<ver>/`, with `current` a symlink to
 *   the live one and a `.huginn-portable` marker at the root. Everything is owned
 *   by the user, so the app can update ITSELF — unpack beside, swap the link,
 *   relaunch. This is the only Linux shape where "Install and restart" is real.
 * - **A distro package** under `/opt/huginn-desktop-kt` (the `.deb`, or the native
 *   Arch `.pkg.tar.zst`): root-owned, so the app parks the verified package and
 *   tells the person the one command that installs it. ⚠ It never asks for a
 *   password — an app that prompts for root to update itself is an app teaching
 *   its owner to type one into whatever asks.
 * - **Dev** (a Gradle run): no `jpackage.app-path`, nothing installed, nothing to
 *   update.
 *
 * ⚠ DETECTED FROM THE LAUNCHER'S REAL PATH, never from a setting. jpackage's
 * Linux launcher stamps `-Djpackage.app-path=` with `/proc/self/exe`, which the
 * kernel has already resolved through every symlink — so a launch through
 * `~/.local/bin/huginn-desktop` or `R/current/bin/…` both arrive here as
 * `R/versions/<ver>/bin/huginn-desktop-kt`. [detect] canonicalises anyway, so a
 * future launcher that stops resolving cannot silently flip the answer.
 *
 * ⚠ THE MARKER AND WRITABILITY ARE BOTH REQUIRED. A directory that merely LOOKS
 * like `…/versions/<ver>/bin` (somebody's own unpack, a root-owned copy) must not
 * be mistaken for a tree this app may rewrite: the marker says "huginn laid this
 * out", writability says "and you can change it without root".
 */
object LinuxInstall {

    enum class Kind { PORTABLE, DEB, ARCH, OTHER, DEV }

    /** The family a distro package would come from, read from os-release. */
    enum class Distro { DEBIAN, ARCH, OTHER }

    /** [root] and [versionDir] are set for [Kind.PORTABLE] only. */
    data class Mode(val kind: Kind, val root: File? = null, val versionDir: File? = null)

    const val MARKER: String = ".huginn-portable"
    const val ROOT_NAME: String = "huginn-desktop"
    const val LAUNCHER_REL: String = "bin/huginn-desktop-kt"
    const val ICON_REL: String = "lib/huginn-desktop-kt.png"

    /** `R` — `${XDG_DATA_HOME:-~/.local/share}/huginn-desktop`, the CLI's layout root. */
    fun portableRoot(env: (String) -> String? = System::getenv, home: String? = System.getProperty("user.home")): File? {
        val base = env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: home?.let { "$it/.local/share" } ?: return null
        return File(base, ROOT_NAME)
    }

    /**
     * Pure apart from the filesystem probes on the launcher's own tree.
     *
     * @param appPath `jpackage.app-path`, or null for a Gradle run.
     * @param osRelease the text of `/etc/os-release`, or null when unreadable.
     */
    fun detect(appPath: String?, osRelease: String?): Mode {
        if (appPath.isNullOrBlank()) return Mode(Kind.DEV)
        val real: Path = runCatching { File(appPath).toPath().toRealPath() }
            .getOrElse { File(appPath).toPath().toAbsolutePath().normalize() }
        // R/versions/<ver>/bin/huginn-desktop-kt — four names up from the binary.
        val bin = real.parent
        val vdir = bin?.parent
        val versions = vdir?.parent
        val root = versions?.parent
        if (bin != null && vdir != null && versions != null && root != null &&
            bin.fileName?.toString() == "bin" &&
            versions.fileName?.toString() == "versions" &&
            Files.isRegularFile(root.resolve(MARKER)) &&
            Files.isWritable(root) && Files.isWritable(versions)
        ) {
            return Mode(Kind.PORTABLE, root.toFile(), vdir.toFile())
        }
        return when (distro(osRelease)) {
            Distro.ARCH -> Mode(Kind.ARCH)
            Distro.DEBIAN -> Mode(Kind.DEB)
            Distro.OTHER -> Mode(Kind.OTHER)
        }
    }

    /** [detect] for this process. */
    fun current(): Mode = detect(System.getProperty("jpackage.app-path"), readOsRelease())

    /**
     * `ID` first, then every word of `ID_LIKE`. Arch derivatives (Omarchy, EndeavourOS,
     * Manjaro, CachyOS) carry `ID_LIKE=arch`; Ubuntu and Mint carry `debian`.
     */
    fun distro(osRelease: String?): Distro {
        if (osRelease == null) return Distro.OTHER
        fun field(k: String): List<String> = osRelease.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$k=") }
            ?.substringAfter('=')?.trim()?.trim('"', '\'')
            ?.lowercase()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }
            .orEmpty()
        val ids = field("ID") + field("ID_LIKE")
        return when {
            ids.any { it == "arch" || it == "archlinux" } -> Distro.ARCH
            ids.any { it == "debian" || it == "ubuntu" } -> Distro.DEBIAN
            else -> Distro.OTHER
        }
    }

    fun readOsRelease(): String? =
        listOf("/etc/os-release", "/usr/lib/os-release").firstNotNullOfOrNull { p ->
            runCatching { File(p).takeIf { it.isFile }?.readText() }.getOrNull()
        }

    /**
     * The launcher path anything OUTSIDE this process should remember — the
     * `huginn://` handler, the autostart entry, a relaunch.
     *
     * ⚠ In portable mode that is `R/current/bin/…`, NEVER the versioned path the
     * kernel reports. A menu entry or scheme handler pinned to `versions/1.12.1`
     * keeps launching 1.12.1 after the update swapped `current`, and then breaks
     * outright the update after that, when the prune deletes it. Everything
     * written outside the tree goes through `current` so an update never has to
     * find and rewrite it.
     */
    fun stableLauncher(appPath: String?, mode: Mode = detect(appPath, null)): String? {
        if (appPath.isNullOrBlank()) return null
        val root = mode.root
        return if (mode.kind == Kind.PORTABLE && root != null) File(root, "current/$LAUNCHER_REL").path else appPath
    }

    /**
     * [stableLauncher] for THIS process — what [com.silencelen.huginn.desktop.notify.SchemeRegistrar]
     * and [com.silencelen.huginn.desktop.setup.Autostart] write into the files
     * that outlive it. Null for a Gradle run; the raw launcher on Windows.
     */
    fun stableLauncherForThisProcess(): String? {
        val p = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() } ?: return null
        if (!System.getProperty("os.name").orEmpty().lowercase().contains("linux")) return p
        return stableLauncher(p, detect(p, null))
    }

    /**
     * The sentence under a parked, not-installable update. Names THIS distro's
     * command for the file that was actually downloaded, and always offers the
     * per-user install that would make the next update a button.
     */
    fun manualHint(kind: Kind, file: File): String {
        val path = file.absolutePath
        val switch = "or switch to the self-updating install: huginn desktop install"
        return when (kind) {
            Kind.ARCH -> "downloaded to $path — install it with: sudo pacman -U $path ($switch)"
            Kind.DEB -> "downloaded to $path — install it with: sudo apt install $path ($switch)"
            Kind.OTHER -> "downloaded to $path — install it by hand, $switch"
            Kind.PORTABLE, Kind.DEV -> "downloaded to $path — install it by hand on this platform"
        }
    }
}
