package com.foodflow.domain.usecase.preferences

import com.foodflow.core.datastore.UserPreferences
import com.foodflow.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveUserPreferencesUseCase @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository
) {
    /** Provides live updates to the user's saved app preferences. */
    operator fun invoke(): Flow<UserPreferences> = userPreferencesRepository.preferences
}
