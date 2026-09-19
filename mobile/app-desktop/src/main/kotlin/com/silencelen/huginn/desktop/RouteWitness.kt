package com.silencelen.huginn.desktop

import com.silencelen.huginn.data.RouteHealth

/**
 * WHETHER A RESPONSE COUNTS AS THE ACTIVE ROUTE HAVING JUST WORKED.
 *
 * ⚠ THE ROW SAID "LAST REACHED 4m AGO" ABOUT THE ROUTE SERVING EVERY REQUEST.
 * `AppStore.noteRouteReached()` was called from exactly two places, both the
 * success branch of `/v1/status` — and `/v1/status` is only polled while the
 * Status pane is open. Measured over one window: 42 successful `/v1/chats`, 42
 * `/v1/sessions`, 39 each of `/v1/rounds`, `/v1/devices`, `/v1/scratchpads` and
 * `/v1/projects`, plus an attached watch stream, and ZERO `/v1/status`. The one
 * line in Settings whose whole job is to say which path is working was the one
 * line ordinary traffic did not touch. Its own doc comment already claimed the
 * opposite.
 *
 * So the witness is stamped from the HTTP layer — once, where every response
 * arrives — rather than from each caller. A per-caller rule is the shape that
 * produced this bug: it is correct until somebody adds the twelfth call site.
 *
 * Three things have to be true before a response is a witness, and each one is a
 * way the naive version goes wrong:
 *
 *  * **It succeeded.** A 401 proves the address answers but not that the route
 *    works for us, and `RouteFailures` already makes that distinction.
 *  * **It came from the ACTIVE route's own address.** The client probes candidate
 *    addresses through the same engine; a probe that happens to 200 must not
 *    stamp whatever row is active at the time.
 *  * **It is worth writing.** The health map is persisted to the settings FILE on
 *    every change, and the poll makes eight or more requests every five seconds.
 *    Granularity is what stops a witness that reads "just now" from costing a
 *    hundred disk writes a minute to say so.
 */
object RouteWitness {

    /**
     * How stale the witness may get before a success rewrites it.
     *
     * The rows speak in minutes ("reached just now", "3m ago"), so ten seconds is
     * invisible in the answer and turns ~100 writes a minute into six.
     */
    const val GRANULARITY_MS: Long = 10_000

    /**
     * The `host:port` a URL addresses, lowercased, with the scheme's default port
     * made explicit so `http://host` and `http://host:80` are one place.
     *
     * Deliberately NOT a full URL comparison: the route book holds a base
     * (`192.168.2.117:8787`, `https://huginn.example`) and what arrives here is a
     * request for `/v1/chats` on it. Matching the authority is the question being
     * asked — "did this response come from where we are pinned" — and it is the
     * only part of the two strings that describes the same thing.
     *
     * Returns "" for anything unparseable, which never matches (see [sameEndpoint]).
     */
    fun authority(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        val schemeAt = trimmed.indexOf("://")
        val scheme = if (schemeAt < 0) "http" else trimmed.substring(0, schemeAt).lowercase()
        val rest = if (schemeAt < 0) trimmed else trimmed.substring(schemeAt + 3)
        // Whichever comes first ends the authority: a path, a query, or a fragment.
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = (if (end < 0) rest else rest.substring(0, end))
            // Credentials are not part of the place. Nothing writes them today,
            // and a route book entry that carried one would otherwise never match.
            .substringAfterLast('@')
            .lowercase()
        if (authority.isEmpty()) return ""
        if (authority.contains(':') && !authority.startsWith("[")) return authority
        val port = if (scheme == "https") "443" else "80"
        return "$authority:$port"
    }

    /** Whether a response's URL and a pinned route's URL name the same place. */
    fun sameEndpoint(responseUrl: String, routeUrl: String): Boolean {
        val a = authority(responseUrl)
        return a.isNotEmpty() && a == authority(routeUrl)
    }

    /**
     * Whether this success is worth recording, given what is already on file.
     *
     * @param status the HTTP status of the response.
     * @param responseUrl the absolute URL the response came from.
     * @param routeUrl the ACTIVE route's address, as the book holds it.
     * @param health the witness already recorded for that route, if any.
     * @param now epoch millis.
     */
    fun stamps(
        status: Int,
        responseUrl: String,
        routeUrl: String,
        health: RouteHealth?,
        now: Long,
    ): Boolean {
        if (status !in 200..299) return false
        if (routeUrl.isBlank() || !sameEndpoint(responseUrl, routeUrl)) return false
        val was = health?.lastSeenAt ?: 0
        // A witness that has never been written is always worth writing;
        // `RouteResolver.touch` refuses to move the clock backwards on its own,
        // so this only has to decide how OFTEN a working route is re-stamped.
        return was <= 0 || now - was >= GRANULARITY_MS
    }
}
