package com.foodflow.feature.cart

import androidx.lifecycle.viewModelScope
import com.foodflow.core.common.AppResult
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.core.mvi.MviViewModel
import com.foodflow.domain.model.Coupon
import com.foodflow.domain.model.FoodItem
import com.foodflow.domain.usecase.CalculateCartTotalsUseCase
import com.foodflow.domain.usecase.ValidateCouponUseCase
import com.foodflow.domain.usecase.cart.AddToCartUseCase
import com.foodflow.domain.usecase.cart.ClearCartUseCase
import com.foodflow.domain.usecase.cart.ObserveCartItemsUseCase
import com.foodflow.domain.usecase.cart.RemoveCartItemUseCase
import com.foodflow.domain.usecase.cart.SyncPendingCartOperationsUseCase
import com.foodflow.domain.usecase.cart.UpdateCartQuantityUseCase
import com.foodflow.domain.usecase.cart.UpdateCookingInstructionsUseCase
import com.foodflow.domain.usecase.cart.ValidateCartUseCase
import com.foodflow.domain.usecase.restaurant.GetCouponByCodeUseCase
import com.foodflow.domain.usecase.restaurant.ObserveCouponsUseCase
import com.foodflow.domain.usecase.restaurant.ObserveMenuUseCase
import com.foodflow.domain.usecase.restaurant.ObserveRestaurantsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CartViewModel @Inject constructor(
    observeCartItems: ObserveCartItemsUseCase,
    observeRestaurants: ObserveRestaurantsUseCase,
    observeCoupons: ObserveCouponsUseCase,
    observeMenu: ObserveMenuUseCase,
    private val addToCart: AddToCartUseCase,
    private val updateCartQuantity: UpdateCartQuantityUseCase,
    private val removeCartItem: RemoveCartItemUseCase,
    private val updateCookingInstructions: UpdateCookingInstructionsUseCase,
    private val clearCart: ClearCartUseCase,
    private val syncPendingCartOperations: SyncPendingCartOperationsUseCase,
    private val validateCart: ValidateCartUseCase,
    private val getCouponByCode: GetCouponByCodeUseCase,
    private val calculateCartTotals: CalculateCartTotalsUseCase,
    private val validateCoupon: ValidateCouponUseCase,
    private val connectivityObserver: ConnectivityObserver
) : MviViewModel<CartUiState, CartIntent, CartEffect>(CartUiState()) {

    private val appliedCouponFlow = MutableStateFlow<Coupon?>(null)
    private val instructionSaveJobs = mutableMapOf<String, Job>()

    init {
        val cartFlow = observeCartItems()
        val menuFlow = cartFlow
            .map { items -> items.firstOrNull()?.restaurantId }
            .distinctUntilChanged()
            .flatMapLatest { restaurantId ->
                if (restaurantId.isNullOrBlank()) flowOf(emptyList())
                else observeMenu(restaurantId)
            }

        combine(
            cartFlow,
            observeRestaurants(),
            observeCoupons(),
            menuFlow,
            appliedCouponFlow
        ) { items, restaurants, coupons, menu, appliedCoupon ->
            val restaurant = items.firstOrNull()?.let { first ->
                restaurants.firstOrNull { it.id == first.restaurantId }
            }
            val cartFoodIds = items.map { it.itemId }.toSet()
            val suggested = selectSuggestedAddons(menu = menu, existingFoodIds = cartFoodIds)
            val deliveryFee = restaurant?.deliveryFee ?: CalculateCartTotalsUseCase.DEFAULT_DELIVERY_FEE
            val breakdown = calculateCartTotals(
                items = items,
                baseDeliveryFee = deliveryFee,
                coupon = appliedCoupon
            )
            val effectiveCoupon = if (breakdown.appliedCouponCode != null) appliedCoupon else null
            updateState {
                val activeIds = items.map { it.itemId }.toSet()
                val prunedDrafts = cookingInstructionDrafts.filterKeys { it in activeIds }
                copy(
                    items = items,
                    cookingInstructionDrafts = prunedDrafts,
                    restaurant = restaurant,
                    suggestedAddons = suggested,
                    coupons = coupons,
                    appliedCoupon = effectiveCoupon,
                    breakdown = breakdown
                )
            }
        }.launchIn(viewModelScope)

        connectivityObserver.isOnline.map { !it }.distinctUntilChanged().let { offlineFlow ->
            viewModelScope.launch {
                offlineFlow.collect { offline ->
                    updateState { copy(isOffline = offline) }
                }
            }
        }
    }

    /** Chooses available menu items that are useful additions to the current cart. */
    private fun selectSuggestedAddons(
        menu: List<FoodItem>,
        existingFoodIds: Set<String>
    ): List<FoodItem> {
        if (menu.isEmpty()) return emptyList()
        return menu
            .asSequence()
            .filter { it.isAvailable && it.id !in existingFoodIds }
            .sortedWith(
                compareByDescending<FoodItem> { item ->
                    val cat = item.category.lowercase()
                    when {
                        cat.contains("drink") || cat.contains("beverage") || cat.contains("dessert") -> 3
                        cat.contains("bread") || cat.contains("side") || cat.contains("starter") || cat.contains("snack") -> 2
                        item.isBestseller -> 1
                        else -> 0
                    }
                }.thenBy { it.price }
            )
            .take(6)
            .toList()
    }

    /** Handles cart actions and updates saved cart state or one-time UI effects. */
    override fun onIntent(intent: CartIntent) {
        when (intent) {
            is CartIntent.IncrementItem -> viewModelScope.launch {
                updateCartQuantity(intent.itemId, intent.currentQty + 1)
            }
            is CartIntent.DecrementItem -> viewModelScope.launch {
                updateCartQuantity(intent.itemId, intent.currentQty - 1)
            }
            is CartIntent.RemoveItem -> viewModelScope.launch {
                removeCartItem(intent.itemId)
            }
            is CartIntent.AddSuggestedItem -> viewModelScope.launch {
                val restaurant = state.value.restaurant ?: return@launch
                when (val result = addToCart(restaurant = restaurant, foodItem = intent.foodItem)) {
                    is AppResult.Success -> emitEffect(
                        CartEffect.ShowMessage("${intent.foodItem.name} added to your cart")
                    )
                    is AppResult.Error -> emitEffect(
                        CartEffect.ShowMessage(result.error.userMessage)
                    )
                }
            }
            is CartIntent.UpdateInstructions -> {
                updateState {
                    copy(
                        cookingInstructionDrafts = cookingInstructionDrafts + (intent.itemId to intent.instructions)
                    )
                }
                instructionSaveJobs[intent.itemId]?.cancel()
                instructionSaveJobs[intent.itemId] = viewModelScope.launch {
                    delay(COOKING_NOTE_DEBOUNCE_MS)
                    updateCookingInstructions(intent.itemId, intent.instructions)
                }
            }
            is CartIntent.CouponInputChanged -> updateState {
                copy(couponInput = intent.value.uppercase(), couponError = null)
            }
            is CartIntent.ApplyCouponCode -> applyCoupon(intent.code)
            CartIntent.RemoveCoupon -> {
                appliedCouponFlow.value = null
                updateState { copy(couponInput = "", couponError = null) }
            }
            CartIntent.ClearCart -> viewModelScope.launch {
                instructionSaveJobs.values.forEach { it.cancel() }
                instructionSaveJobs.clear()
                clearCart()
                appliedCouponFlow.value = null
                updateState { copy(cookingInstructionDrafts = emptyMap()) }
            }
            CartIntent.DismissNotices -> updateState { copy(reconciliationNotices = emptyList()) }
            CartIntent.ProceedToCheckout -> proceedToCheckout()
        }
    }

    /** Looks up and validates the entered coupon before applying it to the cart. */
    private fun applyCoupon(rawCode: String) {
        viewModelScope.launch {
            val code = rawCode.trim().uppercase()
            val coupon = getCouponByCode(code)
            when (val res = validateCoupon(coupon, state.value.breakdown.subtotal)) {
                is AppResult.Success -> {
                    appliedCouponFlow.value = res.data
                    updateState { copy(couponInput = res.data.code, couponError = null) }
                    emitEffect(CartEffect.ShowMessage("Coupon ${res.data.code} applied!"))
                }
                is AppResult.Error -> {
                    updateState { copy(couponError = res.error.userMessage) }
                }
            }
        }
    }

    /** Syncs and validates the cart before opening checkout. */
    private fun proceedToCheckout() {
        viewModelScope.launch {
            if (state.value.items.isEmpty()) return@launch
            if (!connectivityObserver.isCurrentlyOnline()) {
                emitEffect(
                    CartEffect.ShowMessage(
                        "You're offline. Your cart is saved — reconnect to verify prices and complete checkout."
                    )
                )
                return@launch
            }
            updateState { copy(isValidating = true, errorMessage = null) }
            flushPendingCookingNotes()
            val syncRes = syncPendingCartOperations()
            if (syncRes is AppResult.Error) {
                updateState { copy(isValidating = false) }
                emitEffect(CartEffect.ShowMessage(syncRes.error.userMessage))
                return@launch
            }
            when (val validation = validateCart()) {
                is AppResult.Success -> {
                    updateState { copy(isValidating = false, reconciliationNotices = validation.data) }
                    if (validation.data.isEmpty()) {
                        emitEffect(CartEffect.NavigateToCheckout(state.value.appliedCoupon?.code))
                    } else {
                        emitEffect(CartEffect.ShowMessage("We updated your cart with the latest menu prices and availability."))
                    }
                }
                is AppResult.Error -> {
                    updateState { copy(isValidating = false) }
                    emitEffect(CartEffect.ShowMessage(validation.error.userMessage))
                }
            }
        }
    }

    /** Saves pending cooking-note edits before the cart is validated. */
    private suspend fun flushPendingCookingNotes() {
        val drafts = state.value.cookingInstructionDrafts
        if (drafts.isEmpty()) return
        instructionSaveJobs.values.forEach { it.cancel() }
        instructionSaveJobs.clear()
        drafts.forEach { (itemId, note) ->
            updateCookingInstructions(itemId, note)
        }
    }

    companion object {
        private const val COOKING_NOTE_DEBOUNCE_MS: Long = 350L
    }
}
