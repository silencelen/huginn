package com.silencelen.huginn.data

/**
 * What a route's address is allowed to be, and what kind of path it describes.
 *
 * ⚠ THIS IS NOT COSMETIC VALIDATION. There is ONE bearer token for every pinned
 * route (all of them address the same daemon), and that bearer follows the base
 * URL on every single request. An unchecked address field is therefore a
 * one-field path to handing a root-equivalent daemon token to whatever host
 * somebody can talk a user into typing. The desktop has refused since 1.0 —
 * `DesktopSettings.ALLOWED_HOSTS`, four literal hosts — and **the phone never
 * checked at all**. Moving the rule into `:core` is what closes that, and is the
 * single strongest argument for generalizing routes in the first place.
 *
 * A HOST LIST CANNOT SURVIVE user-added routes, so this is a SHAPE rule instead:
 *
 * - `https://` anywhere. TLS is the thing that makes a public address safe to
 *   send a bearer to, so once it is present the host stops mattering.
 * - `http://` ONLY to an address that cannot leave a trusted network: loopback,
 *   RFC1918, the CGNAT block tailscale hands out (100.64.0.0/10), ULA
 *   (fc00::/7), or a `*.ts.net` MagicDNS name.
 * - Nothing else. `http://` to a public literal or an arbitrary DNS name is
 *   refused, in the daemon's own sentence.
 *
 * Two smaller rules, both inherited from the desktop's version and both
 * load-bearing:
 *
 * - **The path must be empty.** A base URL is an authority, not a prefix; a path
 *   here is either a mistake or an attempt to point the client's own route
 *   builder somewhere it does not expect.
 * - **No userinfo.** `http://user:pass@host` puts a credential in a field the
 *   diagnostics bundle prints, and there is no case for one against this daemon.
 *
 * ⚠ AND THE SCHEME IS MADE EXPLICIT BEFORE STORING. [HuginnClient] prepends
 * `http://` to a bare address (which is convenient and stays), so a guard that
 * ran before that upgrade could be walked past by typing `example.com`.
 * [normalize] stamps the scheme on, so what is stored is what was judged.
 */
object RouteGuard {

    /**
     * The one refusal sentence, verbatim from the desktop store it replaces.
     * Shown to the reader rather than logged: a setting that silently does not
     * take is worse than one that says no.
     */
    const val REFUSED: String =
        "refusing that server address — huginn only talks to its own daemon"

    /** The address as it is stored: trimmed, no trailing slash, scheme explicit. */
    fun normalize(raw: String): String {
        val parts = parse(raw) ?: return AppdRoutes.normalize(raw)
        return parts.canonical
    }

    fun isAllowed(raw: String): Boolean {
        val parts = parse(raw) ?: return false
        if (parts.scheme == "https") return true
        return parts.kind != RouteKind.CUSTOM
    }

    /**
     * The normalized address, or an [IllegalArgumentException] carrying
     * [REFUSED]. `require`-shaped so both stores can keep failing the way the
     * desktop always has, with the message the UI already prints.
     */
    fun require(raw: String): String {
        val parts = parse(raw)
        kotlin.require(parts != null && (parts.scheme == "https" || parts.kind != RouteKind.CUSTOM)) { REFUSED }
        return parts!!.canonical
    }

    /**
     * What kind of path this address describes — the badge beside a pinned
     * route's name. Auto-detected rather than asked, because the guard above
     * already has to compute exactly this to decide whether plain HTTP is safe.
     */
    fun kindOf(raw: String): RouteKind = parse(raw)?.kind ?: RouteKind.CUSTOM

    /**
     * `100.97.198.90:8787` — the authority and never more. Used as the default
     * NAME of a route somebody typed without naming, and as the address line
     * under a route's name in diagnostics, so it must never carry a userinfo
     * segment: [parse] refuses one outright.
     */
    fun authorityOf(raw: String): String = parse(raw)?.authority ?: ""

    // ------------------------------------------------------------- parsing

    private data class Parts(
        val scheme: String,
        val host: String,
        val port: Int?,
        val kind: RouteKind,
    ) {
        val authority: String =
            (if (':' in host) "[$host]" else host) + (port?.let { ":$it" } ?: "")
        val canonical: String = "$scheme://$authority"
    }

    /**
     * A deliberately small URL parser, because `java.net.URI` does not exist in
     * common code and the rules here are narrower than a general one anyway:
     * scheme, host, optional port, nothing else at all.
     */
    private fun parse(raw: String): Parts? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null

        val lower = trimmed.lowercase()
        val scheme: String
        val rest: String
        when {
            lower.startsWith("https://") -> { scheme = "https"; rest = trimmed.substring(8) }
            lower.startsWith("http://") -> { scheme = "http"; rest = trimmed.substring(7) }
            // No scheme means http, the same assumption HuginnClient makes — and
            // judged as http, which is the whole point of normalizing first.
            "://" in trimmed -> return null
            else -> { scheme = "http"; rest = trimmed }
        }

        val authority = rest.substringBefore('/')
        // Everything after the authority must be nothing. The trailing slash was
        // already trimmed, so a surviving remainder is a real path.
        if (rest.length > authority.length) return null
        if (authority.isEmpty()) return null
        if (authority.any { it == '@' || it == '?' || it == '#' || it.isWhitespace() }) return null

        val host: String
        val portText: String?
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            host = authority.substring(1, close)
            val tail = authority.substring(close + 1)
            portText = when {
                tail.isEmpty() -> null
                tail.startsWith(":") -> tail.substring(1)
                else -> return null
            }
        } else if (authority.count { it == ':' } > 1) {
            // A bare IPv6 literal. Ambiguous with host:port by construction, so
            // it is refused rather than guessed at — brackets are not optional.
            return null
        } else if (':' in authority) {
            host = authority.substringBefore(':')
            portText = authority.substringAfter(':')
        } else {
            host = authority
            portText = null
        }

        if (host.isEmpty()) return null
        val port = portText?.let { text ->
            val n = text.toIntOrNull() ?: return null
            if (n !in 1..65535) return null
            n
        }
        return Parts(scheme, host.lowercase(), port, classify(host.lowercase()))
    }

    private fun classify(host: String): RouteKind {
        if (host == "localhost" || host.endsWith(".localhost")) return RouteKind.LOCAL
        if (host.endsWith(".ts.net")) return RouteKind.TAILNET

        ipv4(host)?.let { octets ->
            val a = octets[0]
            val b = octets[1]
            return when {
                a == 127 -> RouteKind.LOCAL
                a == 10 -> RouteKind.LAN
                a == 192 && b == 168 -> RouteKind.LAN
                a == 172 && b in 16..31 -> RouteKind.LAN
                // 100.64.0.0/10 — the CGNAT block tailscale allocates from.
                a == 100 && b in 64..127 -> RouteKind.TAILNET
                else -> RouteKind.CUSTOM
            }
        }

        if (':' in host) return classifyIpv6(host)

        return RouteKind.CUSTOM
    }

    private fun classifyIpv6(host: String): RouteKind {
        val groups = host.split(":")
        val values = groups.map { g ->
            if (g.isEmpty()) null else (g.toIntOrNull(16)?.takeIf { it in 0..0xffff } ?: return RouteKind.CUSTOM)
        }
        // `::1` and its written-out form. Not `endsWith("1")`: `1::` has the same
        // single significant group and is a different address entirely.
        val significant = values.filterNotNull().filter { it != 0 }
        if (significant == listOf(1) && values.lastOrNull() == 1) return RouteKind.LOCAL
        val first = values.firstOrNull { it != null } ?: return RouteKind.CUSTOM
        // fc00::/7 — the unique-local block the overlay meshes live in.
        return if ((first shr 8) == 0xfc || (first shr 8) == 0xfd) RouteKind.MESH else RouteKind.CUSTOM
    }

    private fun ipv4(host: String): List<Int>? {
        val parts = host.split(".")
        if (parts.size != 4) return null
        return parts.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
    }
}
