package com.foodflow.domain.repository

import com.foodflow.core.datastore.ThemeMode
import com.foodflow.core.datastore.UserPreferences
import com.foodflow.domain.model.PaymentMethod
import kotlinx.coroutines.flow.Flow

interface UserPreferencesRepository {
    val preferences: Flow<UserPreferences>
    /** Saves the user's preferred theme mode. */
    suspend fun setThemeMode(mode: ThemeMode)
    /** Saves the payment method to preselect during checkout. */
    suspend fun setPreferredPayment(method: PaymentMethod)
}
