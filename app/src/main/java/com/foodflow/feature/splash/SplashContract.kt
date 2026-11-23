package com.foodflow.feature.splash

import com.foodflow.core.mvi.MviEffect
import com.foodflow.core.mvi.MviIntent
import com.foodflow.core.mvi.MviState

data class SplashUiState(
    val isReady: Boolean = false
) : MviState

sealed interface SplashIntent : MviIntent {
    data object Initialize : SplashIntent
    data object UseReducedMotionDuration : SplashIntent
}

sealed interface SplashEffect : MviEffect {
    data object NavigateToHome : SplashEffect
    data object NavigateToLogin : SplashEffect
}
