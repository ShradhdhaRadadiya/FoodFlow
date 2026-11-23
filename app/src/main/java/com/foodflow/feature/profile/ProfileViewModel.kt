package com.foodflow.feature.profile

import androidx.lifecycle.viewModelScope
import com.foodflow.core.mvi.MviViewModel
import com.foodflow.domain.usecase.auth.LogoutUseCase
import com.foodflow.domain.usecase.preferences.ObserveUserPreferencesUseCase
import com.foodflow.domain.usecase.preferences.SetPreferredPaymentMethodUseCase
import com.foodflow.domain.usecase.preferences.SetThemeModeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    observeUserPreferences: ObserveUserPreferencesUseCase,
    private val setThemeMode: SetThemeModeUseCase,
    private val setPreferredPaymentMethod: SetPreferredPaymentMethodUseCase,
    private val logoutUseCase: LogoutUseCase
) : MviViewModel<ProfileUiState, ProfileIntent, ProfileEffect>(ProfileUiState()) {

    init {
        observeUserPreferences()
            .onEach { prefs -> updateState { copy(preferences = prefs) } }
            .launchIn(viewModelScope)
    }

    /** Applies profile settings changes and logout requests. */
    override fun onIntent(intent: ProfileIntent) {
        when (intent) {
            is ProfileIntent.SetTheme -> viewModelScope.launch {
                setThemeMode(intent.mode)
            }
            is ProfileIntent.SetPreferredPayment -> viewModelScope.launch {
                setPreferredPaymentMethod(intent.method)
                emitEffect(ProfileEffect.ShowMessage("Preferred payment updated to ${intent.method.displayName}"))
            }
            ProfileIntent.Logout -> {
                viewModelScope.launch {
                    updateState { copy(isLoggingOut = true) }
                    logoutUseCase()
                    updateState { copy(isLoggingOut = false) }
                    emitEffect(ProfileEffect.NavigateToLogin)
                }
            }
        }
    }
}
