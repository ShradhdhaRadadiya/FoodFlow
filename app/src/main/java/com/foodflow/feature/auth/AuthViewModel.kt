package com.foodflow.feature.auth

import androidx.lifecycle.viewModelScope
import com.foodflow.core.common.AppResult
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.core.mvi.MviViewModel
import com.foodflow.domain.usecase.auth.RequestOtpUseCase
import com.foodflow.domain.usecase.auth.VerifyOtpUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val requestOtp: RequestOtpUseCase,
    private val verifyOtp: VerifyOtpUseCase,
    connectivityObserver: ConnectivityObserver
) : MviViewModel<AuthUiState, AuthIntent, AuthEffect>(AuthUiState()) {

    private var timerJob: Job? = null

    init {
        connectivityObserver.isOnline
            .onEach { online -> updateState { copy(isOffline = !online) } }
            .launchIn(viewModelScope)
    }

    /** Applies sign-in screen actions such as editing a phone number or submitting a code. */
    override fun onIntent(intent: AuthIntent) {
        when (intent) {
            is AuthIntent.PhoneChanged -> updateState {
                copy(phoneNumber = intent.value.filter { it.isDigit() }.take(10), errorMessage = null)
            }
            is AuthIntent.OtpChanged -> updateState {
                copy(otpCode = intent.value.filter { it.isDigit() }.take(6), errorMessage = null)
            }
            AuthIntent.SendOtp -> sendOtp()
            AuthIntent.VerifyOtp -> submitOtp()
            AuthIntent.QuickContinue -> quickSignIn()
            AuthIntent.EditPhone -> {
                timerJob?.cancel()
                updateState { copy(isOtpSent = false, otpCode = "", errorMessage = null) }
            }
        }
    }

    /** Validates the phone number and requests a one-time sign-in code. */
    private fun sendOtp() {
        val phone = state.value.phoneNumber
        if (phone.length < 10) {
            updateState { copy(errorMessage = "Please enter a valid 10-digit mobile number.") }
            return
        }
        viewModelScope.launch {
            updateState { copy(isLoading = true, errorMessage = null) }
            when (val res = requestOtp(phone)) {
                is AppResult.Success -> {
                    updateState {
                        copy(
                            isLoading = false,
                            isOtpSent = true,
                            resendSecondsRemaining = res.data
                        )
                    }
                    startCountdown(res.data)
                    emitEffect(AuthEffect.ShowMessage("Verification code sent to +91 $phone"))
                }
                is AppResult.Error -> updateState {
                    copy(isLoading = false, errorMessage = res.error.userMessage)
                }
            }
        }
    }

    /** Verifies the entered code and navigates to Home when sign-in succeeds. */
    private fun submitOtp() {
        val current = state.value
        if (current.otpCode.length < 4) {
            updateState { copy(errorMessage = "Enter the 6-digit verification code.") }
            return
        }
        viewModelScope.launch {
            updateState { copy(isLoading = true, errorMessage = null) }
            when (val res = verifyOtp(current.phoneNumber, current.otpCode)) {
                is AppResult.Success -> {
                    timerJob?.cancel()
                    updateState { copy(isLoading = false) }
                    emitEffect(AuthEffect.NavigateToHome)
                }
                is AppResult.Error -> updateState {
                    copy(isLoading = false, errorMessage = res.error.userMessage)
                }
            }
        }
    }

    /** Signs in with the app's built-in demo credentials. */
    private fun quickSignIn() {
        viewModelScope.launch {
            updateState { copy(isLoading = true, errorMessage = null) }
            when (val res = verifyOtp("9876543210", "123456")) {
                is AppResult.Success -> {
                    updateState { copy(isLoading = false) }
                    emitEffect(AuthEffect.NavigateToHome)
                }
                is AppResult.Error -> updateState {
                    copy(isLoading = false, errorMessage = res.error.userMessage)
                }
            }
        }
    }

    /** Updates the remaining wait time before another code can be requested. */
    private fun startCountdown(seconds: Int) {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            var remaining = seconds
            while (remaining > 0) {
                delay(1_000L)
                remaining -= 1
                updateState { copy(resendSecondsRemaining = remaining) }
            }
        }
    }
}
