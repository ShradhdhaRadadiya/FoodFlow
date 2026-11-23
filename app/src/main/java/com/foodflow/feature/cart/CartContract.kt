package com.foodflow.feature.cart

import com.foodflow.core.mvi.MviEffect
import com.foodflow.core.mvi.MviIntent
import com.foodflow.core.mvi.MviState
import com.foodflow.domain.model.CartItem
import com.foodflow.domain.model.CartPriceBreakdown
import com.foodflow.domain.model.CartReconciliationNotice
import com.foodflow.domain.model.Coupon
import com.foodflow.domain.model.FoodItem
import com.foodflow.domain.model.Restaurant

data class CartUiState(
    val items: List<CartItem> = emptyList(),
    val cookingInstructionDrafts: Map<String, String> = emptyMap(),
    val restaurant: Restaurant? = null,
    val suggestedAddons: List<FoodItem> = emptyList(),
    val coupons: List<Coupon> = emptyList(),
    val couponInput: String = "",
    val appliedCoupon: Coupon? = null,
    val couponError: String? = null,
    val breakdown: CartPriceBreakdown = CartPriceBreakdown(),
    val isOffline: Boolean = false,
    val isValidating: Boolean = false,
    val reconciliationNotices: List<CartReconciliationNotice> = emptyList(),
    val errorMessage: String? = null
) : MviState {
    val isEmpty: Boolean get() = items.isEmpty()

    /** Returns the unsaved note draft for an item, or its current saved instructions. */
    fun cookingInstructionsFor(item: CartItem): String =
        cookingInstructionDrafts[item.itemId] ?: item.cookingInstructions
}

sealed interface CartIntent : MviIntent {
    data class IncrementItem(val itemId: String, val currentQty: Int) : CartIntent
    data class DecrementItem(val itemId: String, val currentQty: Int) : CartIntent
    data class RemoveItem(val itemId: String) : CartIntent
    data class AddSuggestedItem(val foodItem: FoodItem) : CartIntent
    data class UpdateInstructions(val itemId: String, val instructions: String) : CartIntent
    data class CouponInputChanged(val value: String) : CartIntent
    data class ApplyCouponCode(val code: String) : CartIntent
    data object RemoveCoupon : CartIntent
    data object ClearCart : CartIntent
    data object DismissNotices : CartIntent
    data object ProceedToCheckout : CartIntent
}

sealed interface CartEffect : MviEffect {
    data class NavigateToCheckout(val couponCode: String?) : CartEffect
    data class ShowMessage(val message: String) : CartEffect
}
