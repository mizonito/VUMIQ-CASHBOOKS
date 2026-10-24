package com.vumiq.cashbook.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vumiq.cashbook.domain.LocaleCode
import com.vumiq.cashbook.domain.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.UUID

data class UserPreferencesSnapshot(
    val activeBusinessId: String = "",
    val localOwnerUid: String = "",
    val displayName: String = "Business Owner",
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val locale: LocaleCode = LocaleCode.EN,
    val selectedCurrencyCode: String = ""
)

class UserPreferencesStore(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.applicationContext.vumiqPreferencesDataStore)

    private object Keys {
        val ACTIVE_BUSINESS_ID = stringPreferencesKey("active_business_id")
        val LOCAL_OWNER_UID = stringPreferencesKey("local_owner_uid")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val THEME = stringPreferencesKey("theme_pref")
        val LOCALE = stringPreferencesKey("locale_code")
        val SELECTED_CURRENCY = stringPreferencesKey("selected_currency")
    }

    private val safeDataFlow: Flow<Preferences> = dataStore.data.catch { exception ->
        if (exception is IOException) {
            emit(emptyPreferences())
        } else {
            throw exception
        }
    }

    val preferencesFlow: Flow<UserPreferencesSnapshot> = safeDataFlow.map { prefs ->
        UserPreferencesSnapshot(
            activeBusinessId = prefs[Keys.ACTIVE_BUSINESS_ID].orEmpty(),
            localOwnerUid = prefs[Keys.LOCAL_OWNER_UID].orEmpty(),
            displayName = prefs[Keys.DISPLAY_NAME] ?: "Business Owner",
            theme = ThemePreference.fromWire(prefs[Keys.THEME]),
            locale = LocaleCode.fromWire(prefs[Keys.LOCALE]),
            selectedCurrencyCode = prefs[Keys.SELECTED_CURRENCY].orEmpty()
        )
    }

    suspend fun getOrCreateLocalOwnerUid(): String {
        val current = preferencesFlow.first().localOwnerUid
        if (current.isNotBlank()) return current
        val generated = "local_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        dataStore.edit { it[Keys.LOCAL_OWNER_UID] = generated }
        return generated
    }

    suspend fun setActiveBusinessId(businessId: String) {
        dataStore.edit { it[Keys.ACTIVE_BUSINESS_ID] = businessId }
    }

    suspend fun setDisplayName(name: String) {
        val clean = name.trim().take(100).ifEmpty { "Business Owner" }
        dataStore.edit { it[Keys.DISPLAY_NAME] = clean }
    }

    suspend fun setTheme(theme: ThemePreference) {
        dataStore.edit { it[Keys.THEME] = theme.wireValue }
    }

    suspend fun setLocale(locale: LocaleCode) {
        dataStore.edit { it[Keys.LOCALE] = locale.wireValue }
    }

    suspend fun setSelectedCurrencyCode(code: String) {
        dataStore.edit { it[Keys.SELECTED_CURRENCY] = code }
    }
}
