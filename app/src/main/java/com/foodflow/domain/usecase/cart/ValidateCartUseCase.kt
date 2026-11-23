package com.foodflow.domain.usecase.cart

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.CartReconciliationNotice
import com.foodflow.domain.repository.CartRepository
import javax.inject.Inject

class ValidateCartUseCase @Inject constructor(
    private val cartRepository: CartRepository
) {
    /** Checks cart items against current server data and returns any changes to review. */
    suspend operator fun invoke(): AppResult<List<CartReconciliationNotice>> =
        cartRepository.validateCartWithServer()
}
