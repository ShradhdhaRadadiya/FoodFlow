package com.foodflow.domain.usecase.cart

import com.foodflow.core.common.AppResult
import com.foodflow.domain.repository.CartRepository
import javax.inject.Inject

class RemoveCartItemUseCase @Inject constructor(
    private val cartRepository: CartRepository
) {
    /** Removes the specified item from the current cart. */
    suspend operator fun invoke(itemId: String): AppResult<Unit> =
        cartRepository.removeItem(itemId)
}
