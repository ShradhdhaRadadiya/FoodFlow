package com.foodflow.domain.usecase.auth

import com.foodflow.core.datastore.UserPreferences
import com.foodflow.domain.repository.AuthRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveAuthPreferencesUseCase @Inject constructor(
    private val authRepository: AuthRepository
) {
    /** Provides live updates to the user's saved sign-in and profile preferences. */
    operator fun invoke(): Flow<UserPreferences> = authRepository.preferences
}
