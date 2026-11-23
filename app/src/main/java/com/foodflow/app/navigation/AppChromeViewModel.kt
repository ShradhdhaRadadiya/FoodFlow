package com.foodflow.app.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foodflow.domain.usecase.cart.ObserveCartItemsUseCase
import com.foodflow.domain.usecase.restaurant.ObserveRestaurantsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Combines local cart and catalog flows into the lightweight state used by global app chrome. */
@HiltViewModel
class AppChromeViewModel @Inject constructor(
    observeCartItems: ObserveCartItemsUseCase,
    observeRestaurants: ObserveRestaurantsUseCase
) : ViewModel() {

    /** Recomputes item count, estimated subtotal, and restaurant label whenever cart or catalog data changes. */
    val cartPreview: StateFlow<AppCartPreviewState> = combine(
        observeCartItems(),
        observeRestaurants()
    ) { items, restaurants ->
        if (items.isEmpty()) {
            AppCartPreviewState()
        } else {
            val restaurantId = items.first().restaurantId
            val restaurant = restaurants.firstOrNull { it.id == restaurantId }
            AppCartPreviewState(
                itemCount = items.sumOf { it.quantity },
                totalPayable = items.sumOf { it.unitPrice * it.quantity },
                restaurantName = restaurant?.name.orEmpty()
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppCartPreviewState())

    /** Exposes only the item count for badges that do not need the full cart preview. */
    val cartCount: StateFlow<Int> = cartPreview
        .map { it.itemCount }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}
