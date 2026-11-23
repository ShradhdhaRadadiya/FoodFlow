package com.foodflow.domain.usecase.cart

import com.foodflow.core.common.AppResult
import com.foodflow.domain.repository.CartRepository
import javax.inject.Inject

class UpdateCartQuantityUseCase @Inject constructor(
    private val cartRepository: CartRepository
) {
    /** Changes the quantity of the specified cart item. */
    suspend operator fun invoke(itemId: String, quantity: Int): AppResult<Unit> =
        cartRepository.updateQuantity(itemId, quantity)
}
