package com.foodflow.domain.usecase.cart

import com.foodflow.core.common.AppResult
import com.foodflow.domain.repository.CartRepository
import javax.inject.Inject

class ClearCartUseCase @Inject constructor(
    private val cartRepository: CartRepository
) {
    /** Removes all items from the current cart. */
    suspend operator fun invoke(): AppResult<Unit> = cartRepository.clearCart()
}
