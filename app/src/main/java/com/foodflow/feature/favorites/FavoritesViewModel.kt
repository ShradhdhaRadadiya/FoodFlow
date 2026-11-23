package com.foodflow.feature.favorites

import androidx.lifecycle.viewModelScope
import com.foodflow.core.common.AppResult
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.core.mvi.MviViewModel
import com.foodflow.domain.usecase.restaurant.ObserveFavoriteRestaurantsUseCase
import com.foodflow.domain.usecase.restaurant.RefreshHomeCatalogUseCase
import com.foodflow.domain.usecase.restaurant.ToggleFavoriteRestaurantUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val observeFavoriteRestaurantsUseCase: ObserveFavoriteRestaurantsUseCase,
    private val refreshHomeCatalog: RefreshHomeCatalogUseCase,
    private val toggleFavoriteRestaurantUseCase: ToggleFavoriteRestaurantUseCase,
    private val connectivityObserver: ConnectivityObserver
) : MviViewModel<FavoritesUiState, FavoritesIntent, FavoritesEffect>(FavoritesUiState()) {

    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                observeFavoriteRestaurantsUseCase(),
                connectivityObserver.isOnline
            ) { favorites, online ->
                favorites to !online
            }.collect { (favorites, offline) ->
                updateState {
                    copy(
                        favorites = favorites,
                        isOffline = offline
                    )
                }
            }
        }
    }

    /** Handles favorite-screen refresh and remove-favorite actions. */
    override fun onIntent(intent: FavoritesIntent) {
        when (intent) {
            FavoritesIntent.Refresh -> handleRefresh()
            is FavoritesIntent.RemoveFavorite -> {
                viewModelScope.launch {
                    toggleFavoriteRestaurantUseCase(intent.restaurantId)
                }
            }
        }
    }

    /** Refreshes the catalog when online while keeping the saved favorites visible. */
    private fun handleRefresh() {
        if (state.value.isRefreshing || refreshJob?.isActive == true) return

        if (!connectivityObserver.isCurrentlyOnline()) {
            emitEffect(FavoritesEffect.ShowMessage("You're offline. Showing saved favourites."))
            return
        }

        refreshJob = viewModelScope.launch {
            updateState { copy(isRefreshing = true) }
            try {
                when (val result = refreshHomeCatalog()) {
                    is AppResult.Success -> Unit
                    is AppResult.Error -> emitEffect(FavoritesEffect.ShowMessage(result.error.userMessage))
                }
            } finally {
                updateState { copy(isRefreshing = false) }
            }
        }
    }
}
