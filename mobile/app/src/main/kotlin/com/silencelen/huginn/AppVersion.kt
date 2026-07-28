package com.silencelen.huginn

import android.content.Context

/**
 * This APK's own versionName.
 *
 * Exists because the app and the host daemon version independently — app 2.36.0
 * beside appd 2.33.0 is a correct, ordinary state — and a screen that shows only
 * "appd 2.33.0" reads as "my app is 2.33.0" to anyone who has not internalised
 * that split. Wherever appd's number appears, this one belongs next to it.
 *
 * Read from PackageManager rather than BuildConfig so it needs no build-config
 * generation and can never drift from what is actually installed.
 */
fun appVersion(context: Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"
