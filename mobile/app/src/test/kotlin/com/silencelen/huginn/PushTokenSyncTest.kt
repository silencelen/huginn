package com.silencelen.huginn

import com.silencelen.huginn.notify.HuginnMessagingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 3.9.0 crashed seven times on the Fold right after the update: a failed FCM
 * `getToken()` task THROWS from `getResult()`, and the callback read it before
 * asking whether the task had succeeded.
 */
class PushTokenSyncTest {
    @Test
    fun `a failed task is never asked for its result`() {
        var asked = false
        assertNull(HuginnMessagingService.usableToken(successful = false) { asked = true; "t" })
        assertEquals(false, asked)
    }

    @Test
    fun `a result that throws is no token, not a crash`() {
        assertNull(HuginnMessagingService.usableToken(true) { throw java.io.IOException("SERVICE_NOT_AVAILABLE") })
    }

    @Test
    fun `a blank token is no token, and a real one passes through`() {
        assertNull(HuginnMessagingService.usableToken(true) { " " })
        assertNull(HuginnMessagingService.usableToken(true) { null })
        assertEquals("abc", HuginnMessagingService.usableToken(true) { "abc" })
    }
}
