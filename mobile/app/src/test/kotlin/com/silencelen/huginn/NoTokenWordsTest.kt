package com.silencelen.huginn

import com.silencelen.huginn.data.PinBook
import com.silencelen.huginn.ui.errorTextFor
import com.silencelen.huginn.ui.gatedClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⚠ A PHONE WITH NO TOKEN IS TOLD SO; ONLY A 401 FROM HUGINN READS AS A REJECTION.
 *
 * With no token the client sends nothing and raises a 401 of its own. That was
 * worded as the daemon turning a token down — "Rejected by huginn: check the
 * token in Settings" — on a phone that had never sent one. The two are told apart
 * by where the 401 came from, and each failure here is the client's REAL one, not
 * a hand-made exception, so the test breaks if the client's message moves.
 *
 * NOTE this module is on org.junit: `assertEquals(message, expected, actual)`.
 */
class NoTokenWordsTest {

    private fun TestScope.transport(status: HttpStatusCode, body: String) =
        OrderedTransport(StandardTestDispatcher(testScheduler), TOKEN) { _ ->
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

    private fun client(token: String, wire: OrderedTransport) =
        gatedClient({ BASE }, { token }, { "" }, MutableStateFlow(true), wire, PinBook())

    @Test
    fun `no token set says so, and nothing was sent`() = runTest {
        val wire = transport(HttpStatusCode.OK, """{"events":[]}""")
        val failure = runCatching { client(token = "", wire).sessionTranscript("s1") }.exceptionOrNull()
        assertEquals("No token set — add it in Settings", failure?.let(::errorTextFor))
        assertEquals("nothing left the device", emptyList<String>(), wire.wire)
    }

    @Test
    fun `a 401 from huginn still says the token was rejected`() = runTest {
        // What the daemon answers a bearer it does not accept.
        val wire = transport(HttpStatusCode.Unauthorized, """{"error":"unauthorized"}""")
        val failure = runCatching { client(token = TOKEN, wire).sessionTranscript("s1") }.exceptionOrNull()
        assertEquals("Rejected by huginn: check the token in Settings", failure?.let(::errorTextFor))
        assertEquals("the request did reach huginn", 1, wire.wire.size)
    }

    private companion object {
        const val BASE = "http://appd.test"
        const val TOKEN = "tok-1"
    }
}
