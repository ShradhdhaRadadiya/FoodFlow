package com.foodflow.feature.favorites

import com.foodflow.core.mvi.MviEffect
import com.foodflow.core.mvi.MviIntent
import com.foodflow.core.mvi.MviState
import com.foodflow.domain.model.Restaurant

data class FavoritesUiState(
    val favorites: List<Restaurant> = emptyList(),
    val isRefreshing: Boolean = false,
    val isOffline: Boolean = false
) : MviState

sealed interface FavoritesIntent : MviIntent {
    data object Refresh : FavoritesIntent
    data class RemoveFavorite(val restaurantId: String) : FavoritesIntent
}

sealed interface FavoritesEffect : MviEffect {
    data class ShowMessage(val message: String) : FavoritesEffect
}
