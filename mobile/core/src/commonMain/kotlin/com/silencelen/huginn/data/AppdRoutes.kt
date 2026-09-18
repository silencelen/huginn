package com.silencelen.huginn.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * One of the two addresses this app SHIPPED with, for seeding and migration.
 *
 * There were two because only ONE VpnService can hold the tunnel slot at a time:
 * while Tailscale is connected the tailnet address is the reachable one, and
 * while Yggdrasil (the Nebula mesh) is connected the LAN address is, reached
 * through the mesh gateway on heimdall. Neither is reachable from the other's
 * tunnel — which is the whole reason this app has ever had more than one address,
 * and now the reason a user can pin as many as they need.
 *
 * ⚠ NOT A UI TYPE. It carried a `hint` ("huginn's tailnet address") that a row on
 * the phone printed beside the route name; nothing prints it now, because a route
 * has the owner's own name and the app has no business annotating it.
 */
data class AppdRoute(val label: String, val url: String)

/**
 * The two addresses this app used to be built around — now SEED AND MIGRATION
 * ONLY.
 *
 * Routes are the owner's list ([RouteBook]), not a compile-time constant, and
 * nothing in the UI names Tailscale or Yggdrasil any more. What survives here is
 * the pair of addresses an upgrading install has to be handed so that upgrading
 * changes nothing about where the app talks — see [migrate], whose table is the
 * whole point of this file's continued existence.
 */
object AppdRoutes {
    /** huginn's tailnet address, which is where the daemon binds. */
    val TAILSCALE = AppdRoute(label = "Tailscale", url = "http://100.97.198.90:8787")

    /**
     * huginn's VLAN-2 address. Off-LAN devices reach it through the yggdrasil
     * LAN gateway; the hop is encrypted by nebula, so plain HTTP here is no
     * weaker than the tailnet route.
     */
    val YGGDRASIL = AppdRoute(label = "Yggdrasil", url = "http://192.168.2.117:8787")

    val ALL = listOf(TAILSCALE, YGGDRASIL)

    /**
     * The id a migrated hand-typed address gets. Fixed rather than minted so the
     * migration table is a table — the same input gives the same book, which is
     * what [MigrationTest] can hold to account.
     */
    const val MIGRATED_ID: String = "custom"

    /**
     * The `addedAt` a migrated pin gets: ZERO, meaning "it was already here".
     *
     * Not the clock, and deliberately. The phone's store has no open-and-upgrade
     * moment — DataStore is a flow of preferences — so [migrate] runs on every
     * READ until the first save writes the book down. A wall-clock timestamp
     * would make two reads of the same unchanged bytes produce two different
     * books, which breaks flow equality and would have every collector rebuild
     * on every emission. Zero is also the honest answer: nobody added these, an
     * upgrade did.
     */
    const val MIGRATED_AT: Long = 0

    fun normalize(url: String): String = url.trim().trimEnd('/')

    /** The predefined route matching [url], or null when it's a custom address. */
    fun match(url: String): AppdRoute? {
        val n = normalize(url)
        return ALL.firstOrNull { normalize(it.url) == n }
    }

    /**
     * The seed pin for a built-in, with the label as its name and a stable id.
     *
     * ⚠ `byHand = false`. Nobody typed these: they are literals compiled into a
     * PUBLIC app, over plain http, and an install that upgrades gets both of them
     * whether or not the machine they name is the one it talks to. That is
     * exactly the shape [RouteResolver] refuses to adopt on its own.
     */
    fun seed(route: AppdRoute, now: Long, order: Int = 0): PinnedRoute {
        val url = normalize(route.url)
        return PinnedRoute(
            id = route.label.lowercase(),
            name = route.label,
            url = url,
            kind = RouteGuard.kindOf(url),
            order = order,
            addedAt = now,
            byHand = false,
        )
    }

    /**
     * Turns what an older install stored into a route book, with NO user action
     * and no change in behaviour.
     *
     * The rules, and why each one is the way it is:
     *
     * | stored | becomes |
     * |---|---|
     * | `base_url` matching a built-in | that built-in as pin #1, named "Tailscale" / "Yggdrasil" |
     * | `base_url` matching nothing | pin #1 named after its own host — the owner's chosen address stays chosen |
     * | the built-ins not already emitted | appended, in [ALL] order |
     * | `base_url` absent (fresh install) | an EMPTY book; the first address saved becomes pin #1 |
     * | `appd_route_pinned` = true | `autoSwitch = false` — the same refusal to move, under its new name |
     *
     * The resulting order reproduces the old `candidates()` EXACTLY (active
     * first, then the built-ins in [ALL] order), so the first route the resolver
     * picks after an upgrade is the one it would have picked before it. That is
     * not a coincidence to be preserved by hand; it is why the order is spelled
     * this way.
     */
    fun migrate(storedBaseUrl: String?, routePinned: Boolean, now: Long = MIGRATED_AT): RouteBook {
        val stored = storedBaseUrl?.let { normalize(it) }?.takeIf { it.isNotBlank() }
            ?: return RouteBook(autoSwitch = !routePinned)

        val first = match(stored)?.let { seed(it, now) }
            ?: PinnedRoute(
                id = MIGRATED_ID,
                name = RouteBook.defaultName(stored),
                url = stored,
                kind = RouteGuard.kindOf(stored),
                addedAt = now,
            )
        val rest = ALL.filter { normalize(it.url) != stored }.map { seed(it, now) }
        return RouteBook(
            routes = listOf(first) + rest,
            activeId = first.id,
            autoSwitch = !routePinned,
        ).normalized()
    }
}

/**
 * Which pinned route this client should be talking to.
 *
 * ORDERED PREFERENCE, NOT FASTEST. The owner's list IS the preference, and the
 * first healthy route in it wins. Ranking by round-trip time is what makes a
 * client flap between two paths that both work, which is the failure the old
 * "current route first" comment existed to prevent and which gets worse, not
 * better, as the list grows.
 *
 * Pure but for the injected [probe], so the ordering, the hysteresis and the
 * short-circuit are unit-testable without a network.
 */
object RouteResolver {

    /**
     * How long a route that answered keeps the connection even when a route
     * above it in the list also answers.
     *
     * A switch is a reconnect: the watch stream drops, the poll restarts, and
     * every open pane refetches. Paying that to move from a working path to
     * a marginally-preferred one is a bad trade, so the active route only loses
     * its place by FAILING.
     */
    const val HYSTERESIS_MS: Long = 60_000

    /**
     * Consecutive failed calls on the active route before the client looks for
     * another one. Three rather than one because a single timeout is ordinary —
     * a phone changing cells, a laptop waking — and re-resolving on each of them
     * would turn a blip into a reconnect.
     */
    const val FAILURES_BEFORE_REPROBE: Int = 3

    /** What a resolution decided. Each case is a different thing to tell the reader. */
    sealed interface Choice {
        /** Nothing is pinned yet. The connect flow, not an error. */
        data object Empty : Choice

        /** The owner pinned this by hand; auto-switching was not consulted. */
        data class Pinned(val route: PinnedRoute) : Choice

        /**
         * The client is not moving. [route] is where it stays — which is what
         * every caller has always printed — and the subclass says why.
         *
         * ⚠ SEALED RATHER THAN A SECOND TOP-LEVEL CASE, on purpose. [Candidate]
         * is new, and a brand-new `Choice` branch would break the exhaustive
         * `when` in both shells; nested under [Stay] the shells keep compiling
         * and keep doing the SAFE thing (nothing) until they grow a branch for
         * it. A client that never learns about candidates is a client that never
         * auto-adopts one, which is the whole point.
         */
        sealed class Stay(val route: PinnedRoute) : Choice {

            /** The active route is still the right one. */
            class Here(route: PinnedRoute) : Stay(route)

            /**
             * Another route answered, and this client will not take it without a
             * person saying so: it is a plain-http address nobody added by hand
             * (see [PinnedRoute.byHand]), and moving there means handing that
             * host the daemon's bearer in cleartext.
             *
             * @param route where the connection stays in the meantime.
             * @param candidate the route that answered, to be offered.
             */
            class Candidate(route: PinnedRoute, val candidate: PinnedRoute) : Stay(route)
        }

        /** A different route answered and the active one did not. */
        data class Switched(val route: PinnedRoute) : Choice

        /**
         * Nothing answered. ⚠ The caller must LEAVE THE SETTING ALONE — blanking
         * it turns "no connectivity" into "this app looks unconfigured".
         */
        data object NoRoute : Choice
    }

    data class Outcome(val choice: Choice, val health: Map<String, RouteHealth>)

    /**
     * @param health the per-route cache from the last resolution, carried in and
     *   handed back updated. Used for the state dots and for the hysteresis
     *   above — never to rank.
     * @param force a manual "find the live route": probes even when the active
     *   route answered moments ago, so the dots refresh. It still cannot move
     *   off a healthy active route, which is what makes the answer *"Still on
     *   X"* rather than a surprise reconnect.
     * @param probe true when HUGINN answered at that URL — not merely something.
     *   See `HuginnClient.provesDaemon`: a probe that counts any HTTP responder
     *   is how a stranger on the LAN gets preferred over a live daemon.
     */
    suspend fun resolve(
        book: RouteBook,
        health: Map<String, RouteHealth> = emptyMap(),
        now: Long = 0,
        force: Boolean = false,
        probe: suspend (PinnedRoute) -> Boolean,
    ): Outcome {
        if (book.routes.isEmpty()) return Outcome(Choice.Empty, health)
        val active = book.active
        if (!book.autoSwitch) {
            return Outcome(active?.let { Choice.Pinned(it) } ?: Choice.Empty, health)
        }

        val activeWasFresh = active != null && isFresh(health[active.id], now)
        // The cheap half of the hysteresis: a route that answered seconds ago is
        // not re-interrogated on every start, only on a manual ask.
        if (!force && active != null && activeWasFresh) return Outcome(Choice.Stay.Here(active), health)

        // ONE budget for the whole list, not one per route. Sequentially probing
        // eight pins at three seconds each is twenty-four seconds of a dead app
        // before it tries the address that works.
        val results = coroutineScope {
            val started = book.routes.map { route ->
                async {
                    val began = kotlin.time.TimeSource.Monotonic.markNow()
                    val ok = probe(route)
                    route.id to (ok to began.elapsedNow().inWholeMilliseconds)
                }
            }
            started.map { it.await() }
        }.toMap()

        val next = health.toMutableMap()
        for ((id, r) in results) {
            val (ok, rtt) = r
            val was = next[id] ?: RouteHealth()
            next[id] = if (ok) was.copy(lastOkAt = now, lastRttMs = rtt) else was.copy(lastFailAt = now)
        }

        val healthy = book.routes.filter { results[it.id]?.first == true }
        if (healthy.isEmpty()) return Outcome(Choice.NoRoute, next)

        // The expensive half of the hysteresis: a route that has been working
        // keeps the connection even when something above it in the list answers.
        // A route that has just come back from the dead does NOT — after a real
        // outage the list settles back to the owner's preference.
        if (active != null && activeWasFresh && healthy.any { it.id == active.id }) {
            return Outcome(Choice.Stay.Here(active), next)
        }

        val pick = healthy.first()
        if (pick.id == active?.id) return Outcome(Choice.Stay.Here(pick), next)
        // ⚠ A MOVE HANDS THE NEXT REQUEST'S BEARER TO THAT HOST. Over https the
        // host stops mattering (RouteGuard's rule); over plain http it matters
        // entirely, and a route nobody typed — the built-ins an upgrade seeds,
        // whose literals are in a public repo — is not evidence of anything. The
        // connection stays where it is and the answer NAMES the route, for a
        // person to adopt once.
        if (!adoptable(pick)) {
            return Outcome(Choice.Stay.Candidate(route = active ?: pick, candidate = pick), next)
        }
        return Outcome(Choice.Switched(pick), next)
    }

    /** Whether this client may move to [route] without being told to. */
    private fun adoptable(route: PinnedRoute): Boolean =
        route.byHand || route.url.trim().startsWith("https://", ignoreCase = true)

    private fun isFresh(h: RouteHealth?, now: Long): Boolean =
        h != null && h.lastOkAt > 0 && now - h.lastOkAt in 0 until HYSTERESIS_MS
}
