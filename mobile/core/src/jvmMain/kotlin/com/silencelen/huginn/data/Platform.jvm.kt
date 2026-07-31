package com.silencelen.huginn.data

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual fun huginnHttpEngine(): HttpClientEngine = OkHttp.create()

actual val huginnIoDispatcher: CoroutineDispatcher get() = Dispatchers.IO
