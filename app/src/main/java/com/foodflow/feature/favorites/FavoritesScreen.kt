package com.foodflow.feature.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodflow.core.designsystem.components.FoodFlowEmptyState
import com.foodflow.core.designsystem.components.FoodFlowPullToRefreshBox
import com.foodflow.core.designsystem.components.FoodFlowTopBar
import com.foodflow.core.designsystem.components.OfflineBanner
import com.foodflow.core.designsystem.components.RestaurantCard
import com.foodflow.core.designsystem.components.foodFlowScreenListPadding
import com.foodflow.core.designsystem.theme.FoodFlowSpacing
import kotlinx.coroutines.flow.collectLatest

/** Displays restaurants the user has saved as favorites. */
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onOpenRestaurant: (String) -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is FavoritesEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            FoodFlowTopBar(
                title = "Favourite Kitchens",
                onBack = onBack
            )
        }
    ) { padding ->
        FoodFlowPullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.onIntent(FavoritesIntent.Refresh) },
            testTag = "favorites_pull_to_refresh",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("favorites_list"),
                contentPadding = foodFlowScreenListPadding(
                    horizontal = FoodFlowSpacing.md,
                    top = FoodFlowSpacing.md,
                    bottomExtra = 24.dp,
                    includeNavBarInset = true
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.isOffline) {
                    item(key = "offline_banner") {
                        OfflineBanner(isOffline = state.isOffline)
                    }
                }

                if (state.favorites.isEmpty()) {
                    item(key = "empty_favorites") {
                        FoodFlowEmptyState(
                            icon = Icons.Filled.FavoriteBorder,
                            title = "No favourite kitchens yet",
                            subtitle = "Tap the heart icon on any restaurant to save your go-to places for quick ordering."
                        )
                    }
                } else {
                    items(state.favorites, key = { it.id }) { restaurant ->
                        RestaurantCard(
                            restaurant = restaurant,
                            onClick = { onOpenRestaurant(restaurant.id) },
                            onToggleFavorite = { viewModel.onIntent(FavoritesIntent.RemoveFavorite(restaurant.id)) }
                        )
                    }
                }
            }
        }
    }
}
