package com.foodflow.domain.usecase.cart

import com.foodflow.core.common.AppResult
import com.foodflow.domain.repository.CartRepository
import javax.inject.Inject

class UpdateCookingInstructionsUseCase @Inject constructor(
    private val cartRepository: CartRepository
) {
    /** Saves the cooking instructions for the specified cart item. */
    suspend operator fun invoke(itemId: String, instructions: String): AppResult<Unit> =
        cartRepository.updateCookingInstructions(itemId, instructions)
}
