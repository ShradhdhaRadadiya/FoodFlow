package com.foodflow.domain.usecase.auth

import com.foodflow.core.common.AppResult
import com.foodflow.domain.repository.AuthRepository
import javax.inject.Inject

class LogoutUseCase @Inject constructor(
    private val authRepository: AuthRepository
) {
    /** Ends the current user session and returns whether logout succeeded. */
    suspend operator fun invoke(): AppResult<Unit> = authRepository.logout()
}
