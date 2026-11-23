package com.foodflow.domain.usecase.cart

import com.foodflow.domain.model.CartItem
import com.foodflow.domain.repository.CartRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveCartItemsUseCase @Inject constructor(
    private val cartRepository: CartRepository
) {
    /** Provides live updates to the items currently in the cart. */
    operator fun invoke(): Flow<List<CartItem>> = cartRepository.observeCartItems()
}
