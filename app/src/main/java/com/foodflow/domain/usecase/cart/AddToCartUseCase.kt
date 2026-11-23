package com.foodflow.domain.usecase.cart

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.FoodAddon
import com.foodflow.domain.model.FoodItem
import com.foodflow.domain.model.Restaurant
import com.foodflow.domain.repository.CartRepository
import javax.inject.Inject

class AddToCartUseCase @Inject constructor(
    private val cartRepository: CartRepository
) {
    /** Adds a food item with its options and notes to the user's cart. */
    suspend operator fun invoke(
        restaurant: Restaurant,
        foodItem: FoodItem,
        addons: List<FoodAddon> = emptyList(),
        cookingInstructions: String = "",
        clearExistingDifferentRestaurant: Boolean = false
    ): AppResult<Unit> = cartRepository.addItem(
        restaurant = restaurant,
        foodItem = foodItem,
        addons = addons,
        cookingInstructions = cookingInstructions,
        clearExistingDifferentRestaurant = clearExistingDifferentRestaurant
    )
}
