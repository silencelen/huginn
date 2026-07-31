package com.silencelen.huginn.data

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineDispatcher

/**
 * The HTTP engine [HuginnClient] builds on when a caller does not supply one.
 *
 * The only reason this is expect/actual: Ktor's engines are published per
 * platform, so `commonMain` has no symbol for any of them. Both actuals name
 * OkHttp — the same stack the phone shipped before the client moved here, and
 * the same one the desktop client will use, so there is ONE set of connect /
 * read / call timeout semantics to reason about rather than two.
 *
 * A fresh engine per call site, as OkHttpClient was before: Ktor's OkHttp engine
 * shares one OkHttpClient prototype across engines, so the connection pool is
 * shared even though each [HuginnClient] holds its own engine.
 */
expect fun huginnHttpEngine(): HttpClientEngine

/**
 * Where the SSE readers parse.
 *
 * Ktor never blocks a thread on a socket, so this is not about avoiding a
 * blocked read — it is about where the JSON decoding of a 4000-frame replay
 * happens. On the phone the collector is the Compose main thread, and that work
 * must not land on it. `Dispatchers.IO` is JVM-only in coroutines 1.9 (it
 * reaches `commonMain` in 1.10), which is the only reason this needs an
 * expect/actual at all.
 */
expect val huginnIoDispatcher: CoroutineDispatcher
