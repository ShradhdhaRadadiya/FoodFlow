package com.foodflow.domain.usecase.auth

import com.foodflow.core.common.AppResult
import com.foodflow.core.datastore.UserPreferences
import com.foodflow.domain.repository.AuthRepository
import javax.inject.Inject

class VerifyOtpUseCase @Inject constructor(
    private val authRepository: AuthRepository
) {
    /** Checks the sign-in code and returns the resulting user session preferences. */
    suspend operator fun invoke(phoneNumber: String, otp: String): AppResult<UserPreferences> =
        authRepository.verifyOtp(phoneNumber, otp)
}
