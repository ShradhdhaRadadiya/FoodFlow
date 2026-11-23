package com.foodflow.domain.repository

import com.foodflow.core.common.AppResult
import com.foodflow.core.datastore.UserPreferences
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    val preferences: Flow<UserPreferences>
    /** Requests a sign-in code for the supplied phone number. */
    suspend fun requestOtp(phoneNumber: String): AppResult<Int>
    /** Verifies a sign-in code and returns the resulting user session. */
    suspend fun verifyOtp(phoneNumber: String, otp: String): AppResult<UserPreferences>
    /** Ends the current user session. */
    suspend fun logout(): AppResult<Unit>
    /** Ensures the initial local catalog data is available. */
    suspend fun ensureSeeded(): AppResult<Unit>
}
