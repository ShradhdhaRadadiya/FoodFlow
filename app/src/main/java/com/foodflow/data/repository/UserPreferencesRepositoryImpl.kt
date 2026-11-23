package com.foodflow.data.repository

import com.foodflow.core.datastore.ThemeMode
import com.foodflow.core.datastore.UserPreferences
import com.foodflow.core.datastore.UserPreferencesDataStore
import com.foodflow.domain.model.PaymentMethod
import com.foodflow.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserPreferencesRepositoryImpl @Inject constructor(
    private val preferencesDataStore: UserPreferencesDataStore
) : UserPreferencesRepository {

    override val preferences: Flow<UserPreferences> = preferencesDataStore.preferences

    /** Saves the user's selected theme mode in DataStore. */
    override suspend fun setThemeMode(mode: ThemeMode) {
        preferencesDataStore.updateThemeMode(mode)
    }

    /** Saves the payment method to preselect during checkout. */
    override suspend fun setPreferredPayment(method: PaymentMethod) {
        preferencesDataStore.updatePreferredPayment(method.name)
    }
}
