package com.foodflow.domain.usecase.auth

import com.foodflow.core.common.AppResult
import com.foodflow.domain.repository.AuthRepository
import javax.inject.Inject

class EnsureCatalogSeededUseCase @Inject constructor(
    private val authRepository: AuthRepository
) {
    /** Ensures the local restaurant and food catalog has been initialized. */
    suspend operator fun invoke(): AppResult<Unit> = authRepository.ensureSeeded()
}
