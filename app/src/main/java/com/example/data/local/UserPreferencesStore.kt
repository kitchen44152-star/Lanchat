package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.lanChatDataStore: DataStore<Preferences> by preferencesDataStore(name = "lanchat_prefs")

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK
}

data class LocalIdentityConfig(
    val nodeId: String = "",
    val displayName: String = "",
    val signingPrivB64: String = "",
    val signingPubB64: String = "",
    val dhPrivB64: String = "",
    val dhPubB64: String = "",
    val isOnboarded: Boolean = false,
    val backgroundRelayEnabled: Boolean = true,
    val maxHops: Int = 6,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val shareLocationOnRadar: Boolean = false
)

class UserPreferencesStore(private val context: Context) {

    private object Keys {
        val NODE_ID = stringPreferencesKey("node_id")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val SIGNING_PRIV = stringPreferencesKey("signing_priv_b64")
        val SIGNING_PUB = stringPreferencesKey("signing_pub_b64")
        val DH_PRIV = stringPreferencesKey("dh_priv_b64")
        val DH_PUB = stringPreferencesKey("dh_pub_b64")
        val IS_ONBOARDED = booleanPreferencesKey("is_onboarded")
        val BACKGROUND_RELAY = booleanPreferencesKey("background_relay_enabled")
        val MAX_HOPS = intPreferencesKey("max_hops")
        val THEME_PREF = stringPreferencesKey("theme_preference")
        val SHARE_LOCATION_ON_RADAR = booleanPreferencesKey("share_location_on_radar")
    }

    val identityFlow: Flow<LocalIdentityConfig> = context.lanChatDataStore.data.map { prefs ->
        LocalIdentityConfig(
            nodeId = prefs[Keys.NODE_ID].orEmpty(),
            displayName = prefs[Keys.DISPLAY_NAME].orEmpty(),
            signingPrivB64 = prefs[Keys.SIGNING_PRIV].orEmpty(),
            signingPubB64 = prefs[Keys.SIGNING_PUB].orEmpty(),
            dhPrivB64 = prefs[Keys.DH_PRIV].orEmpty(),
            dhPubB64 = prefs[Keys.DH_PUB].orEmpty(),
            isOnboarded = prefs[Keys.IS_ONBOARDED] ?: false,
            backgroundRelayEnabled = prefs[Keys.BACKGROUND_RELAY] ?: true,
            maxHops = prefs[Keys.MAX_HOPS] ?: 6,
            themePreference = prefs[Keys.THEME_PREF]
                ?.let { runCatching { ThemePreference.valueOf(it) }.getOrNull() }
                ?: ThemePreference.SYSTEM,
            shareLocationOnRadar = prefs[Keys.SHARE_LOCATION_ON_RADAR] ?: false
        )
    }

    suspend fun getIdentitySnapshot(): LocalIdentityConfig = identityFlow.first()

    suspend fun saveNewIdentity(
        nodeId: String,
        displayName: String,
        signingPrivB64: String,
        signingPubB64: String,
        dhPrivB64: String,
        dhPubB64: String,
        backgroundRelayEnabled: Boolean
    ) {
        context.lanChatDataStore.edit { prefs ->
            prefs[Keys.NODE_ID] = nodeId
            prefs[Keys.DISPLAY_NAME] = displayName
            prefs[Keys.SIGNING_PRIV] = signingPrivB64
            prefs[Keys.SIGNING_PUB] = signingPubB64
            prefs[Keys.DH_PRIV] = dhPrivB64
            prefs[Keys.DH_PUB] = dhPubB64
            prefs[Keys.IS_ONBOARDED] = true
            prefs[Keys.BACKGROUND_RELAY] = backgroundRelayEnabled
        }
    }

    suspend fun updateDisplayName(name: String) {
        context.lanChatDataStore.edit { it[Keys.DISPLAY_NAME] = name.trim() }
    }

    suspend fun setBackgroundRelayEnabled(enabled: Boolean) {
        context.lanChatDataStore.edit { it[Keys.BACKGROUND_RELAY] = enabled }
    }

    suspend fun setMaxHops(hops: Int) {
        context.lanChatDataStore.edit { it[Keys.MAX_HOPS] = hops.coerceIn(2, 10) }
    }

    suspend fun setThemePreference(theme: ThemePreference) {
        context.lanChatDataStore.edit { it[Keys.THEME_PREF] = theme.name }
    }

    suspend fun setShareLocationOnRadar(enabled: Boolean) {
        context.lanChatDataStore.edit { it[Keys.SHARE_LOCATION_ON_RADAR] = enabled }
    }
}
