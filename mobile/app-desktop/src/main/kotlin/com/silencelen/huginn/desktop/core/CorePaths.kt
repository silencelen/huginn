package com.silencelen.huginn.desktop.core

import java.nio.file.Files
import java.nio.file.Path

/**
 * Where this user's `huginn core` listens. MUST match the Go side —
 * `core/internal/store/paths.go` (Runtime) + `daemon.SocketPath` — or the GUI
 * looks for a core in the wrong place and calls a running one missing.
 *
 *  - Linux: `$XDG_RUNTIME_DIR/huginn/core.sock`, falling back to
 *    `/run/user/<uid>/huginn/core.sock` (a relative XDG value is ignored, as the
 *    spec says and as the core does).
 *  - Windows: `%LOCALAPPDATA%\huginn\run\core.sock` (AF_UNIX, Windows 10 1803+).
 */
internal object CorePaths {
    fun socket(env: (String) -> String? = System::getenv, osName: String = System.getProperty("os.name") ?: ""): Path? {
        if (osName.startsWith("Windows")) {
            val local = env("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: return null
            return Path.of(local, "huginn", "run", "core.sock")
        }
        val xdg = env("XDG_RUNTIME_DIR")?.takeIf { it.startsWith("/") }
        if (xdg != null) return Path.of(xdg, "huginn", "core.sock")
        val uid = runCatching { Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid") as Int }.getOrNull()
            ?: return null
        return Path.of("/run/user/$uid", "huginn", "core.sock")
    }
}
