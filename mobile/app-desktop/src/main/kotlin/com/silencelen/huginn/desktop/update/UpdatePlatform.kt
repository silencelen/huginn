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
