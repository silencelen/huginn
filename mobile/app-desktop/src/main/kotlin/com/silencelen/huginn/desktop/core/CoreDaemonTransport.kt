package com.silencelen.huginn.desktop.core

import com.silencelen.huginn.data.DaemonTransport
import com.silencelen.huginn.data.TlsProbeReply
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.nio.file.Path

/**
 * Everything this app asks huginn goes THROUGH huginn core (owner decision
 * 2026-10-16, docs/DESKTOP-VIA-CORE.md): `HuginnClient` keeps every call it
 * makes, and this transport carries them to the core's `/appd/…` proxy over the
 * core's AF_UNIX socket instead of to appd over TLS.
 *
 * So this app holds no token and pins no certificate. The core adds its own
 * token on its own proven, pinned link, picks the address from its route book
 * and answers with appd's status, headers and body as appd sent them. The
 * socket admits only this user (the core checks the peer per connection).
 *
 * The base URL is [APPD_BASE]: plain http to a loopback name, so
 * `HuginnClient`'s pinning step (https only) never engages, and its host is
 * never dialled: [CoreSocketFactory] connects every socket to the core.
 */
internal class CoreDaemonTransport(
    private val socket: Path,
    private val configure: OkHttpConfig.() -> Unit = {},
) : DaemonTransport {

    companion object {
        /** What `HuginnClient` is pointed at while this transport carries it. */
        const val APPD_BASE = "http://127.0.0.1/appd"
    }

    override fun engine(pins: () -> Collection<String>): HttpClientEngine = OkHttp.create {
        configure()
        config {
            socketFactory(CoreSocketFactory(socket))
            dns { listOf(InetAddress.getLoopbackAddress()) } // the host name is never resolved
            proxy(Proxy.NO_PROXY)
            addInterceptor { it.proceed(it.request().newBuilder().header("X-Huginn-Core-Proto", CoreClient.PROTO).build()) }
        }
    }

    /**
     * There is nothing for this app to probe: the core proves huginn's
     * certificate itself. Nothing calls this on this transport (the setup and
     * route screens are the core's while it carries the app); a caller that did
     * gets a plain refusal rather than a silent pass.
     */
    override suspend fun probe(url: String, timeoutMs: Long): TlsProbeReply =
        throw IOException("huginn core holds the link to huginn; this app does not probe it directly")

    override fun unpinnedLeaf(failure: Throwable): String? = null
}
