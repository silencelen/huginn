package com.silencelen.huginn.data

import com.silencelen.huginn.ui.TimeFormat
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual fun huginnHttpEngine(): HttpClientEngine = OkHttp.create()

actual val huginnIoDispatcher: CoroutineDispatcher get() = Dispatchers.IO

/**
 * One read of the machine's zone table and one of its locale. Identical on both
 * platforms on purpose: Android's `DateFormat.is24HourFormat` needs a Context
 * this layer does not have, and the locale's own answer is the one the rest of
 * the system's dates already follow.
 */
actual fun localTimeFormat(atMs: Long): TimeFormat = TimeFormat(
    tzOffsetSec = java.util.TimeZone.getDefault().getOffset(atMs) / 1000,
    // 'h' is the 1-12 hour and 'K' the 0-11 one; a pattern carrying either is a
    // 12-hour locale. Anything else (H, k, or an unreadable pattern) is 24-hour,
    // which is also the house default.
    hour24 = (java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
        as? java.text.SimpleDateFormat)
        ?.toPattern()
        ?.let { p -> !p.contains('h') && !p.contains('K') }
        ?: true,
)
