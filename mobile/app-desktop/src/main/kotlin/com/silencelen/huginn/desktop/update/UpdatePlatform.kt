package com.silencelen.huginn.desktop.update

/**
 * Which artifact key this desktop build should be looking for in a release
 * manifest.
 *
 * Desktop-only (it reads `os.name`/`os.arch`, JVM system properties), so it
 * stays here rather than in :core with the shared manifest model. The key is the
 * platform, not the file name, so the release script and the client never have
 * to agree on a naming convention — only on this string.
 */
object UpdatePlatform {
    const val WINDOWS_X64 = "windows-x64"
    const val LINUX_X64 = "linux-x64"

    /**
     * The per-user app-image tarball. A SEPARATE key, never a new meaning for
     * [LINUX_X64]: every client shipped before 1.13 looks `linux-x64` up and must
     * keep finding the `.deb` there.
     */
    const val LINUX_X64_PORTABLE = "linux-x64-portable"

    /** The native Arch package (`.pkg.tar.zst`), for a pacman-installed client. */
    const val LINUX_X64_ARCH = "linux-x64-arch"

    /**
     * The key THIS install should look up: the base [current] key, narrowed on
     * Linux by how the app was installed. A portable install wants the tarball
     * (the only Linux artifact it can apply itself), a pacman install the Arch
     * package; everything else keeps the `.deb` it always had.
     */
    fun forInstall(base: String?, kind: LinuxInstall.Kind): String? = when {
        base != LINUX_X64 -> base
        kind == LinuxInstall.Kind.PORTABLE -> LINUX_X64_PORTABLE
        kind == LinuxInstall.Kind.ARCH -> LINUX_X64_ARCH
        else -> base
    }

    fun current(
        osName: String = System.getProperty("os.name").orEmpty(),
        osArch: String = System.getProperty("os.arch").orEmpty(),
    ): String? {
        val arch = when (osArch.lowercase()) {
            "amd64", "x86_64" -> "x64"
            else -> return null // arm64 desktop is not built yet; say so rather than guess
        }
        val os = osName.lowercase()
        return when {
            os.startsWith("windows") -> "windows-$arch"
            os.startsWith("linux") -> "linux-$arch"
            else -> null
        }
    }
}
