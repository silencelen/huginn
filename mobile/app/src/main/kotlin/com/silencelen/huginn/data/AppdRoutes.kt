package com.silencelen.huginn.data

/**
 * A named path to huginn-appd.
 *
 * There is more than one because only ONE VpnService can hold the tunnel slot
 * at a time: while Tailscale is connected the tailnet address is the reachable
 * one, and while Yggdrasil (the Nebula mesh) is connected the LAN address is,
 * reached through the mesh gateway on heimdall. Neither is reachable from the
 * other's tunnel, so the app keeps both and picks whichever answers.
 */
data class AppdRoute(val label: String, val url: String, val hint: String)

object AppdRoutes {
    val TAILSCALE = AppdRoute(
        label = "Tailscale",
        url = "http://100.97.198.90:8787",
        hint = "huginn's tailnet address",
    )

    /**
     * huginn's VLAN-2 address. Off-LAN devices reach it through the yggdrasil
     * LAN gateway; the hop is encrypted by nebula, so plain HTTP here is no
     * weaker than the tailnet route.
     */
    val YGGDRASIL = AppdRoute(
        label = "Yggdrasil",
        url = "http://192.168.2.117:8787",
        hint = "over the mesh gateway",
    )

    val ALL = listOf(TAILSCALE, YGGDRASIL)

    fun normalize(url: String): String = url.trim().trimEnd('/')

    /** The predefined route matching [url], or null when it's a custom address. */
    fun match(url: String): AppdRoute? {
        val n = normalize(url)
        return ALL.firstOrNull { normalize(it.url) == n }
    }

    fun labelFor(url: String): String = match(url)?.label ?: "Custom"

    /**
     * Candidates to try, current route first so a working setup is never
     * disturbed and we don't flap between equally-reachable paths.
     */
    fun candidates(current: String): List<String> {
        val n = normalize(current)
        return (listOf(n) + ALL.map { normalize(it.url) }).distinct()
    }
}

/**
 * Picks the first candidate that answers. Pure but for the injected [probe],
 * so the ordering and short-circuit behaviour are unit-testable without a
 * network.
 */
object RouteResolver {

    /**
     * @param probe returns true when the URL is reachable — any HTTP reply
     *   counts, including 401, since that proves the daemon is answering.
     * @return the first reachable candidate, or null when none answered (in
     *   which case the caller should leave the current setting alone rather
     *   than blank it).
     */
    suspend fun resolve(candidates: List<String>, probe: suspend (String) -> Boolean): String? {
        for (url in candidates) {
            if (probe(url)) return url
        }
        return null
    }
}
