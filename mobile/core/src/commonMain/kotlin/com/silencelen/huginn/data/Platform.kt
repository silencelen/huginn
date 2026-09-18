package com.silencelen.huginn.data

import com.silencelen.huginn.ui.TimeFormat
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

/**
 * The local wall-clock rules: the UTC offset in force at [atMs], and whether
 * this locale writes a 24-hour clock.
 *
 * The third expect/actual in this file, and the last one it should ever need.
 * `commonMain` has no zone database and no locale, and [TimeWords] is
 * deliberately pure — it takes the offset rather than reading one, so a DST edge
 * is a test case instead of a Tuesday-in-March bug report. This is the single
 * seam where the machine gets to answer.
 *
 * ⚠ TAKE THE OFFSET AT THE INSTANT BEING RENDERED, not at "now". A message sent
 * in July drawn in December is drawn with July's offset, which is what its clock
 * time actually was; passing `System.currentTimeMillis()` for a historic stamp
 * moves it by an hour twice a year.
 *
 * There is no settings row behind this and there should not be one (decision 43):
 * the operating system already knows both answers and the reader has already
 * given them to it once.
 */
expect fun localTimeFormat(atMs: Long): TimeFormat
