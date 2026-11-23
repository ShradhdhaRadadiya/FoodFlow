package com.foodflow.domain.usecase.auth

import com.foodflow.core.common.AppResult
import com.foodflow.domain.repository.AuthRepository
import javax.inject.Inject

class RequestOtpUseCase @Inject constructor(
    private val authRepository: AuthRepository
) {
    /** Requests a sign-in code for the supplied phone number. */
    suspend operator fun invoke(phoneNumber: String): AppResult<Int> =
        authRepository.requestOtp(phoneNumber)
}
