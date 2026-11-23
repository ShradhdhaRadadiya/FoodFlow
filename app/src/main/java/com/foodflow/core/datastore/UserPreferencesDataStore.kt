package com.foodflow.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.foodFlowDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "foodflow_preferences"
)

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

data class UserPreferences(
    val userId: String? = null,
    val userName: String = "Aarav Sharma",
    val userPhone: String = "",
    val userEmail: String = "aarav.sharma@foodflow.in",
    val authToken: String? = null,
    val selectedAddressId: String = "addr_home",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val preferredPaymentMethod: String = "UPI",
    val lastCatalogSyncEpochMs: Long = 0L,
    val seedInitialized: Boolean = false
) {
    /** Requires both a saved user identifier and non-empty auth token for a valid local session. */
    val isLoggedIn: Boolean get() = !authToken.isNullOrBlank() && !userId.isNullOrBlank()
}

/**
 * Lightweight key-value persistence backed by Jetpack Preferences DataStore.
 * Strictly separated from structured relational domain entities stored in Room.
 */
@Singleton
class UserPreferencesDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val USER_ID = stringPreferencesKey("user_id")
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_PHONE = stringPreferencesKey("user_phone")
        val USER_EMAIL = stringPreferencesKey("user_email")
        val AUTH_TOKEN = stringPreferencesKey("auth_token")
        val SELECTED_ADDRESS_ID = stringPreferencesKey("selected_address_id")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val PREFERRED_PAYMENT = stringPreferencesKey("preferred_payment")
        val LAST_CATALOG_SYNC = longPreferencesKey("last_catalog_sync")
        val SEED_INITIALIZED = booleanPreferencesKey("seed_initialized")
    }

    /** Maps persisted keys to defaults and falls back to system theme for unknown saved enum values. */
    val preferences: Flow<UserPreferences> = context.foodFlowDataStore.data.map { prefs ->
        UserPreferences(
            userId = prefs[Keys.USER_ID],
            userName = prefs[Keys.USER_NAME] ?: "Aarav Sharma",
            userPhone = prefs[Keys.USER_PHONE] ?: "",
            userEmail = prefs[Keys.USER_EMAIL] ?: "aarav.sharma@foodflow.in",
            authToken = prefs[Keys.AUTH_TOKEN],
            selectedAddressId = prefs[Keys.SELECTED_ADDRESS_ID] ?: "addr_home",
            themeMode = prefs[Keys.THEME_MODE]?.let { name ->
                runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.SYSTEM)
            } ?: ThemeMode.SYSTEM,
            preferredPaymentMethod = prefs[Keys.PREFERRED_PAYMENT] ?: "UPI",
            lastCatalogSyncEpochMs = prefs[Keys.LAST_CATALOG_SYNC] ?: 0L,
            seedInitialized = prefs[Keys.SEED_INITIALIZED] ?: false
        )
    }

    /** Reads the first current preference snapshot for one-time decisions. */
    suspend fun getSnapshot(): UserPreferences = preferences.first()

    /** Persists the authenticated user's profile and token in one DataStore edit. */
    suspend fun saveSession(userId: String, name: String, phone: String, email: String, token: String) {
        context.foodFlowDataStore.edit { prefs ->
            prefs[Keys.USER_ID] = userId
            prefs[Keys.USER_NAME] = name
            prefs[Keys.USER_PHONE] = phone
            prefs[Keys.USER_EMAIL] = email
            prefs[Keys.AUTH_TOKEN] = token
        }
    }

    /** Removes session identity and token fields while preserving unrelated preferences. */
    suspend fun clearSession() {
        context.foodFlowDataStore.edit { prefs ->
            prefs.remove(Keys.USER_ID)
            prefs.remove(Keys.AUTH_TOKEN)
            prefs.remove(Keys.USER_PHONE)
        }
    }

    /** Persists the user's selected delivery address identifier. */
    suspend fun updateSelectedAddress(addressId: String) {
        context.foodFlowDataStore.edit { prefs ->
            prefs[Keys.SELECTED_ADDRESS_ID] = addressId
        }
    }

    /** Persists the selected theme enum by name for safe decoding on the next read. */
    suspend fun updateThemeMode(mode: ThemeMode) {
        context.foodFlowDataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = mode.name
        }
    }

    /** Persists the payment method preselected during checkout. */
    suspend fun updatePreferredPayment(method: String) {
        context.foodFlowDataStore.edit { prefs ->
            prefs[Keys.PREFERRED_PAYMENT] = method
        }
    }

    /** Saves the timestamp of the most recent successful catalog refresh. */
    suspend fun updateLastCatalogSync(timestampMs: Long) {
        context.foodFlowDataStore.edit { prefs ->
            prefs[Keys.LAST_CATALOG_SYNC] = timestampMs
        }
    }

    /** Records whether the initial local demo dataset has been loaded. */
    suspend fun setSeedInitialized(initialized: Boolean) {
        context.foodFlowDataStore.edit { prefs ->
            prefs[Keys.SEED_INITIALIZED] = initialized
        }
    }
}
