package com.foodflow.feature.profile

import com.foodflow.core.datastore.ThemeMode
import com.foodflow.core.datastore.UserPreferences
import com.foodflow.core.mvi.MviEffect
import com.foodflow.core.mvi.MviIntent
import com.foodflow.core.mvi.MviState
import com.foodflow.domain.model.PaymentMethod

data class ProfileUiState(
    val preferences: UserPreferences = UserPreferences(),
    val isLoggingOut: Boolean = false
) : MviState

sealed interface ProfileIntent : MviIntent {
    data class SetTheme(val mode: ThemeMode) : ProfileIntent
    data class SetPreferredPayment(val method: PaymentMethod) : ProfileIntent
    data object Logout : ProfileIntent
}

sealed interface ProfileEffect : MviEffect {
    data object NavigateToLogin : ProfileEffect
    data class ShowMessage(val message: String) : ProfileEffect
}
