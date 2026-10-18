package com.silencelen.huginn.desktop

/**
 * ⚠ GARBLED FIRST FRAMES, AND THE ONE THING THAT CLEARED THEM.
 *
 * On the first launch after an install or an update the window showed garbled or
 * black areas until the person un-maximized it and maximized it again (Windows 11,
 * 250% scaling, Direct3D; built-in screen and external monitor alike). Repainting
 * did not help (2.6.1 tried four times in the first six seconds): the surface keeps
 * the size it was created at until the window really changes size, and a real
 * resize is what the manual fix was. So the window does that once itself, a moment
 * after it opens, and only on the launch that follows an install or an update,
 * which is the only launch that showed it. Other launches are left alone, because
 * the re-maximize is visible.
 */
object FirstFrames {

    /** How long after the window opens it settles itself. */
    const val SETTLE_AFTER_MS: Long = 1_500

    /**
     * This launch's build as the settle gate compares it: the version, and when
     * the launcher was installed. A reinstall of the same version rewrites the
     * launcher, and a fresh install showed the garbling as much as an update.
     * Unpackaged (a development run) there is no launcher and the version alone
     * is the build.
     */
    fun build(version: String, launcherModifiedMs: Long?): String =
        if (launcherModifiedMs == null || launcherModifiedMs <= 0) version.trim()
        else "${version.trim()}@$launcherModifiedMs"

    /** True on the first launch of [build] here: a fresh install, a reinstall or an update. */
    fun needsSettle(lastLaunchBuild: String, build: String): Boolean =
        build.isNotBlank() && !build.startsWith("@") && lastLaunchBuild.trim() != build.trim()

    /** The jpackage launcher's install time, or null when this is not a packaged run. */
    fun launcherModifiedMs(): Long? =
        System.getProperty("jpackage.app-path")?.let { java.io.File(it) }?.takeIf { it.isFile }?.lastModified()
}
