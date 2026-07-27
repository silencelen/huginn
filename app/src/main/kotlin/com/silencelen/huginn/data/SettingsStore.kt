package com.silencelen.huginn.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "huginn_settings")

/**
 * Server URL + bearer token. Both are user-supplied: the token is minted on the
 * host by the daemon's deploy script, so there is nothing to hardcode here.
 * Default URL is huginn's tailnet address, which is where the daemon binds.
 */
class SettingsStore(private val context: Context) {
    companion object {
        const val DEFAULT_BASE_URL = "http://100.97.198.90:8787"
        private val BASE_URL = stringPreferencesKey("base_url")
        private val TOKEN = stringPreferencesKey("token")
    }

    val baseUrl: Flow<String> = context.dataStore.data.map { it[BASE_URL] ?: DEFAULT_BASE_URL }
    val token: Flow<String> = context.dataStore.data.map { it[TOKEN] ?: "" }

    suspend fun setBaseUrl(value: String) {
        context.dataStore.edit { it[BASE_URL] = value.trim() }
    }

    suspend fun setToken(value: String) {
        context.dataStore.edit { it[TOKEN] = value.trim() }
    }
}
