package com.foodflow.domain.usecase.preferences

import com.foodflow.core.datastore.ThemeMode
import com.foodflow.domain.repository.UserPreferencesRepository
import javax.inject.Inject

class SetThemeModeUseCase @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository
) {
    /** Saves the user's preferred light, dark, or system theme mode. */
    suspend operator fun invoke(mode: ThemeMode) = userPreferencesRepository.setThemeMode(mode)
}
