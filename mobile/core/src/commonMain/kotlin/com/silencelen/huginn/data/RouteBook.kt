package com.silencelen.huginn.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * What kind of path an address describes. A BADGE, not a setting: computed from
 * the URL by [RouteGuard.kindOf], because the guard has to work this out anyway
 * to decide whether plain HTTP is safe to send a bearer over.
 *
 * `Yggdrasil` reads as [LAN] rather than [MESH], and that is correct: the pin's
 * NAME says which path you take, the badge says what the address *is*, and
 * huginn's yggdrasil route is its VLAN-2 address reached through the mesh
 * gateway. [MESH] is for an overlay address proper (ULA, `fc00::/7`).
 */
@Serializable
enum class RouteKind {
    TAILNET,
    MESH,
    LAN,
    LOCAL,
    CUSTOM;

    /** The word on the badge. */
    val label: String
        get() = when (this) {
            TAILNET -> "Tailnet"
            MESH -> "Mesh"
            LAN -> "LAN"
            LOCAL -> "Local"
            CUSTOM -> "Custom"
        }
}

/**
 * One address the owner has pinned, in their own words.
 *
 * This is the record [AppdRoute] was not: it has an id (so a rename does not
 * lose which route is active), an order (so "first healthy in my order" means
 * something), and a timestamp (so a list can say which one is new).
 *
 * @param id stable for the life of the pin, and what [RouteBook.activeId] names.
 *   Never derived from the URL or the name — both are editable.
 * @param name the owner's word for it. "Tailscale", "the mesh", "work laptop".
 * @param url normalized and guard-checked by [RouteGuard] before it gets here.
 * @param kind the badge, recomputed whenever [url] changes.
 * @param order the position, mirrored from the list index on every mutation so
 *   a store that loses list order still restores the owner's preference.
 * @param byHand whether a PERSON put this address here. ⚠ NOT COSMETIC: a plain
 *   http route carries the bearer in cleartext, and the two addresses the
 *   migration seeds are hard-coded literals in a public repo — whoever holds one
 *   of them on the network the phone is on today would be handed the token by an
 *   automatic switch. [RouteResolver] will not adopt a plain-http route with
 *   this false without a person saying so. Defaults TRUE so every route that
 *   arrives through [RouteBook.add] — which is the only path a person has — is
 *   trusted the way it always was, and only [AppdRoutes.seed] says otherwise.
 */
@Serializable
data class PinnedRoute(
    val id: String,
    val name: String,
    val url: String,
    val kind: RouteKind = RouteKind.CUSTOM,
    val order: Int = 0,
    val addedAt: Long = 0,
    val byHand: Boolean = true,
)

/**
 * The pinned routes, which one is active, and whether huginn may move between
 * them — one value, because all three change together and a client that held
 * them as three settings would be able to point `activeId` at a route that is
 * no longer in the list.
 *
 * ⚠ PURE. Every operation returns a new book and nothing here touches a store,
 * a clock or a socket; the shells persist what comes back. That is what makes
 * the cap, the migration table and the ordering assertable in `:core` rather
 * than twice over in two shells.
 *
 * ⚠ `base_url` IS DERIVED FROM THIS. [activeUrl] is the address ten background
 * call sites still read through `HuginnSettings.baseUrl` — the watch service,
 * the heartbeat, both notification receivers, the widget worker. They were not
 * changed and must not need to be: whatever this book says is active IS the
 * base URL, and both stores write it through on every save.
 */
data class RouteBook(
    val routes: List<PinnedRoute> = emptyList(),
    val activeId: String? = null,
    val autoSwitch: Boolean = true,
    /**
     * An address that was in this book (or in the settings it was migrated from)
     * and is NOT in it now, because [RouteGuard] refuses it.
     *
     * ⚠ A NOTICE, NOT A STATE. Dropping used to be silent on the theory that only
     * a hand-edited store could produce a refused URL — and the phone's pre-3.x
     * "Base URL" was a free-text field with no validation at all, so an upgrade
     * quietly deleted whatever hostname the owner had been using for a year and
     * pointed the app at a hard-coded built-in instead. Carried here so both
     * settings screens can say which address went, and so [normalized] can leave
     * the book with no active route rather than adopting an address nobody chose.
     */
    val droppedUrl: String? = null,
) {

    val active: PinnedRoute? get() = routes.firstOrNull { it.id == activeId }

    /**
     * The derived base URL. Empty when nothing is pinned — which is the honest
     * answer on a fresh install and is why the connect flow asks for an address
     * rather than pre-filling one that may not reach anything.
     */
    val activeUrl: String get() = active?.url ?: ""

    /** The name to show for the connection. Empty when nothing is pinned. */
    val activeName: String get() = active?.name ?: ""

    val isFull: Boolean get() = routes.size >= MAX_PINS

    /**
     * Repairs what a hand-edited settings file (or a half-written one) can
     * break, and is called on every read AND every write by both stores.
     *
     * - `order` mirrors the list index.
     * - `activeId` names a route that exists. An id pointing at nothing falls
     *   back to the first pin rather than to null, because a book with pins and
     *   no active route cannot address the daemon at all.
     * - ⚠ **AN ADDRESS THE GUARD REFUSES IS DROPPED — AND NAMED.** [add] and
     *   [setUrl] throw, so a refused URL reaches a book only from a store: a
     *   hand-edited file, or the pre-3.x free-text "Base URL" the migration
     *   reads. Dropping it is right (this client would refuse to dial it) and
     *   dropping it SILENTLY was not: the address goes to [droppedUrl] so a
     *   screen can say what happened.
     * - ⚠ **AND A BOOK THAT LOST ITS PIN IS NOT RE-POINTED AT A BUILT-IN.** When
     *   [activeId] is null and something was dropped, this leaves it null. A
     *   client with no address says so; a client silently talking to an address
     *   nobody chose is the failure this whole field exists for.
     */
    fun normalized(): RouteBook {
        val kept = routes.filter { RouteGuard.isAllowed(it.url) }
        val dropped = routes.firstOrNull { !RouteGuard.isAllowed(it.url) }?.url ?: droppedUrl
        val ordered = kept.mapIndexed { i, r -> if (r.order == i) r else r.copy(order = i) }
        val unaddressedOnPurpose = activeId == null && dropped != null
        val id = activeId?.takeIf { id -> ordered.any { it.id == id } }
            ?: if (unaddressedOnPurpose) null else ordered.firstOrNull()?.id
        return if (ordered == routes && id == activeId && dropped == droppedUrl) this
        else copy(routes = ordered, activeId = id, droppedUrl = dropped)
    }

    /**
     * Adds a pin at the end of the list.
     *
     * The FIRST pin becomes active, which is the owner's rule stated plainly:
     * *"the first route they set when setting up the app becomes the first
     * pinned route"*. Every one after it joins the order and changes nothing
     * about where the app is currently talking.
     *
     * @throws IllegalArgumentException when the book is full, when the address
     *   does not pass [RouteGuard], or when that address is already pinned —
     *   two pins on one daemon would probe twice and read as two answers.
     */
    fun add(name: String, url: String, now: Long, id: String = newId(now)): RouteBook {
        require(!isFull) { FULL }
        val clean = RouteGuard.require(url)
        require(routes.none { it.url == clean }) { DUPLICATE }
        val route = PinnedRoute(
            id = id,
            name = name.trim().ifBlank { defaultName(clean) },
            url = clean,
            kind = RouteGuard.kindOf(clean),
            order = routes.size,
            addedAt = now,
        )
        return copy(routes = routes + route, activeId = activeId ?: route.id).normalized()
    }

    /** A blank name falls back to the address, never to an empty row. */
    fun rename(id: String, name: String): RouteBook =
        mapRoute(id) { it.copy(name = name.trim().ifBlank { defaultName(it.url) }) }

    /**
     * Editing a pin's URL is HOW an address is typed now — there is no "Base
     * URL" field any more. Guard-checked like an add, and the badge follows.
     */
    fun setUrl(id: String, url: String): RouteBook {
        val clean = RouteGuard.require(url)
        require(routes.none { it.id != id && it.url == clean }) { DUPLICATE }
        return mapRoute(id) { it.copy(url = clean, kind = RouteGuard.kindOf(clean)) }
    }

    /** Removing the active pin moves the connection to whatever is now first. */
    fun remove(id: String): RouteBook =
        copy(routes = routes.filterNot { it.id == id }).normalized()

    /** Up or down by [delta] places, clamped. Unknown id: unchanged. */
    fun move(id: String, delta: Int): RouteBook {
        val from = routes.indexOfFirst { it.id == id }
        if (from < 0) return this
        val to = (from + delta).coerceIn(0, routes.lastIndex)
        if (to == from) return this
        val next = routes.toMutableList()
        next.add(to, next.removeAt(from))
        return copy(routes = next).normalized()
    }

    /**
     * Chooses a route by hand. Unknown id: unchanged — a stale row must not be
     * able to blank the connection.
     */
    fun activate(id: String): RouteBook =
        if (routes.none { it.id == id }) this else copy(activeId = id)

    fun withAutoSwitch(on: Boolean): RouteBook = copy(autoSwitch = on)

    /**
     * Forgets the dropped-address notice. For a screen that has shown it — the
     * notice is a one-time apology for an upgrade, not a setting.
     */
    fun clearDropped(): RouteBook = if (droppedUrl == null) this else copy(droppedUrl = null)

    private fun mapRoute(id: String, f: (PinnedRoute) -> PinnedRoute): RouteBook {
        if (routes.none { it.id == id }) return this
        return copy(routes = routes.map { if (it.id == id) f(it) else it })
    }

    companion object {
        /**
         * Bounds the probe fan-out and the row height at once. Eight is the
         * owner's number; every pin is a socket opened inside one three-second
         * budget whenever the active route goes quiet.
         */
        const val MAX_PINS: Int = 8

        const val FULL: String = "eight pinned routes is the limit — remove one first"
        const val DUPLICATE: String = "that address is already pinned"

        /** The address itself, when somebody adds a route without naming it. */
        fun defaultName(url: String): String =
            RouteGuard.authorityOf(url).ifBlank { AppdRoutes.normalize(url) }

        /**
         * A pin id. Not a UUID (there is no multiplatform one at this Kotlin
         * level) and it does not need to be: it only has to be unique inside one
         * list of at most eight, and stable once minted.
         */
        fun newId(now: Long, random: Random = Random.Default): String =
            "r-" + now.toString(36) + "-" + random.nextInt(0, 1 shl 20).toString(36)
    }
}

/**
 * The per-route answer to "is this path there", kept so a row can show a state
 * dot and say when it last worked.
 *
 * ⚠ NOT AN INPUT TO SELECTION. Ranking routes by round-trip time is exactly what
 * makes a client flap between two equally-reachable paths; the order is the
 * owner's, and this record is for the reader.
 */
@Serializable
data class RouteHealth(
    val lastOkAt: Long = 0,
    val lastFailAt: Long = 0,
    val lastRttMs: Long = 0,
) {
    /** Null until it has been tried at all — which is a third state, not "bad". */
    val reachable: Boolean?
        get() = when {
            lastOkAt == 0L && lastFailAt == 0L -> null
            else -> lastOkAt >= lastFailAt
        }
}

/**
 * The health map as text, so a shell can put it in its own store.
 *
 * ⚠ THE CACHE BEING IN MEMORY ONLY IS A SECURITY PROPERTY IN REVERSE. With an
 * empty map every cold start skips the hysteresis, probes everything, and takes
 * the first route that answers in the owner's order — which is how a stranger
 * occupying a route pinned above the real daemon wins on every app start even
 * while huginn is up. Persisting what the last resolution learned is what makes
 * "the route that has been working keeps the connection" survive a restart.
 *
 * Kept here rather than in either store because both shells need the same bytes,
 * and a second hand-rolled encoding is a second thing to get wrong. The shells
 * wire it into their stores separately; nothing in `:core` persists anything.
 */
@Serializable
data class RouteHealthSnapshot(
    val health: Map<String, RouteHealth> = emptyMap(),
    /** When it was written. For a reader that wants to age the whole snapshot out. */
    val savedAt: Long = 0,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(health: Map<String, RouteHealth>, now: Long = 0): String =
            json.encodeToString(serializer(), RouteHealthSnapshot(health, now))

        /**
         * Whatever was stored, or an empty map. Never throws: a half-written or
         * hand-edited store must cost the dots, not the launch.
         */
        fun decode(text: String?): Map<String, RouteHealth> {
            if (text.isNullOrBlank()) return emptyMap()
            return runCatching { json.decodeFromString(serializer(), text).health }.getOrDefault(emptyMap())
        }
    }
}

/**
 * Counts consecutive failures on the active route, so the client can re-probe
 * after the network has actually moved rather than on a timer.
 *
 * Deliberately a tiny mutable object rather than a rule scattered across two
 * shells: the threshold, the reset and the "fires once" behaviour are the parts
 * that go wrong. Not thread-safe — each shell holds one and touches it from its
 * own scope.
 */
class RouteFailures(private val threshold: Int = RouteResolver.FAILURES_BEFORE_REPROBE) {

    var count: Int = 0
        private set

    /** A call that worked. Clears the run. */
    fun ok() { count = 0 }

    /** @return true exactly on the [threshold]th failure in a row, then resets. */
    fun fail(): Boolean {
        count++
        if (count < threshold) return false
        count = 0
        return true
    }
}
