package com.silencelen.huginn.desktop.update

import java.io.File

/**
 * Which artifact key this desktop build should be looking for in a release
 * manifest.
 *
 * Desktop-only (it reads `os.name`/`os.arch`, JVM system properties, and
 * `/etc/os-release`), so it stays here rather than in :core with the shared
 * manifest model. The key is the platform, not the file name, so the release
 * script and the client never have to agree on a naming convention — only on
 * this string.
 *
 * Linux is two keys, because Linux is two package families: [LINUX_X64] is the
 * `.deb` and is what every Debian/Ubuntu client has always asked for, and
 * [LINUX_X64_ARCH] is the pacman package for Arch-family distributions (Arch,
 * Omarchy, Manjaro, EndeavourOS, CachyOS…). The family is read from os-release's
 * `ID`/`ID_LIKE` — the one place a distribution states its lineage — and not
 * from `command -v pacman`, which an Ubuntu box with the `pacman-package-manager`
 * build tools installed would answer wrongly.
 */
object UpdatePlatform {
    const val WINDOWS_X64 = "windows-x64"
    /** Debian family: the `.deb`. */
    const val LINUX_X64 = "linux-x64"
    /** Arch family: the pacman `.pkg.tar.zst`. */
    const val LINUX_X64_ARCH = "linux-x64-arch"

    fun current(
        osName: String = System.getProperty("os.name").orEmpty(),
        osArch: String = System.getProperty("os.arch").orEmpty(),
        osRelease: String? = readOsRelease(),
    ): String? {
        val arch = when (osArch.lowercase()) {
            "amd64", "x86_64" -> "x64"
            else -> return null // arm64 desktop is not built yet; say so rather than guess
        }
        val os = osName.lowercase()
        return when {
            os.startsWith("windows") -> "windows-$arch"
            os.startsWith("linux") -> if (isArchFamily(osRelease)) "linux-$arch-arch" else "linux-$arch"
            else -> null
        }
    }

    /**
     * True when os-release names `arch` as the distribution (`ID=arch`) or as
     * an ancestor (`ID_LIKE=arch`, Omarchy's answer: `ID=omarchy` / `ID_LIKE=arch`).
     * `ID_LIKE` is a space-separated list and values may be quoted; both are
     * handled. Null or unreadable text means "not Arch", which is the deb — the
     * answer every Linux client gave before this key existed.
     */
    fun isArchFamily(osRelease: String?): Boolean {
        if (osRelease.isNullOrBlank()) return false
        return osRelease.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("ID=") || it.startsWith("ID_LIKE=") }
            .map { it.substringAfter('=').trim().trim('"', '\'') }
            .flatMap { it.split(' ').asSequence() }
            .map { it.trim().lowercase() }
            .any { it == "arch" || it == "archlinux" }
    }

    /** `/etc/os-release`, else its `/usr/lib` fallback (the spec's own order); null when neither reads. */
    private fun readOsRelease(): String? = runCatching {
        listOf("/etc/os-release", "/usr/lib/os-release")
            .map(::File)
            .firstOrNull { it.isFile }
            ?.readText()
    }.getOrNull()
}
