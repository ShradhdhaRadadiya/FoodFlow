package com.foodflow.feature.auth

import com.foodflow.core.mvi.MviEffect
import com.foodflow.core.mvi.MviIntent
import com.foodflow.core.mvi.MviState

data class AuthUiState(
    val phoneNumber: String = "9876543210",
    val otpCode: String = "",
    val isOtpSent: Boolean = false,
    val resendSecondsRemaining: Int = 0,
    val isLoading: Boolean = false,
    val isOffline: Boolean = false,
    val errorMessage: String? = null
) : MviState

sealed interface AuthIntent : MviIntent {
    data class PhoneChanged(val value: String) : AuthIntent
    data class OtpChanged(val value: String) : AuthIntent
    data object SendOtp : AuthIntent
    data object VerifyOtp : AuthIntent
    data object QuickContinue : AuthIntent
    data object EditPhone : AuthIntent
}

sealed interface AuthEffect : MviEffect {
    data object NavigateToHome : AuthEffect
    data class ShowMessage(val message: String) : AuthEffect
}
