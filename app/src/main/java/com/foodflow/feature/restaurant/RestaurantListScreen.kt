package com.foodflow.feature.restaurant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodflow.core.designsystem.components.FoodFlowEmptyState
import com.foodflow.core.designsystem.components.FoodFlowPullToRefreshBox
import com.foodflow.core.designsystem.components.FoodFlowTopBar
import com.foodflow.core.designsystem.components.OfflineBanner
import com.foodflow.core.designsystem.components.PaginationFooterSlot
import com.foodflow.core.designsystem.components.RestaurantCard
import com.foodflow.core.designsystem.components.foodFlowScreenListPadding
import com.foodflow.core.designsystem.theme.FoodFlowSpacing
import com.foodflow.domain.model.PaginationLoadState
import kotlinx.coroutines.flow.collectLatest

/** Displays a filterable and paginated list of restaurants. */
@Composable
fun RestaurantListScreen(
    initialCategory: String?,
    onBack: () -> Unit,
    onOpenRestaurant: (String) -> Unit,
    viewModel: RestaurantListViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(initialCategory) {
        viewModel.onIntent(RestaurantListIntent.SetCategory(initialCategory))
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is RestaurantListEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    val shouldLoadMore by remember(listState, state.hasNextPage, state.loadState, state.visibleRestaurants.size) {
        derivedStateOf {
            if (!state.hasNextPage || state.loadState != PaginationLoadState.Idle) return@derivedStateOf false
            val totalItems = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            totalItems > 0 && lastVisible >= totalItems - 2
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            viewModel.onIntent(RestaurantListIntent.LoadNextPage)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            val subtitleText = if (state.totalCount > 0) {
                "Showing ${state.visibleRestaurants.size} of ${state.totalCount} kitchens"
            } else {
                "${state.visibleRestaurants.size} kitchens available"
            }
            FoodFlowTopBar(
                title = state.selectedCategory?.let { "$it Restaurants" } ?: "All Restaurants",
                subtitle = subtitleText,
                onBack = onBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = FoodFlowSpacing.md, vertical = FoodFlowSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = RestaurantSort.entries,
                    key = { "sort_${it.name}" },
                    contentType = { "sort_chip" }
                ) { sort ->
                    FilterChip(
                        selected = state.sortBy == sort,
                        onClick = { viewModel.onIntent(RestaurantListIntent.SetSort(sort)) },
                        label = { Text(sort.label) }
                    )
                }
                item(key = "filter_pure_veg", contentType = "filter_chip") {
                    FilterChip(
                        selected = state.pureVegOnly,
                        onClick = { viewModel.onIntent(RestaurantListIntent.TogglePureVeg) },
                        label = { Text("Pure Veg") }
                    )
                }
                item(key = "filter_open_now", contentType = "filter_chip") {
                    FilterChip(
                        selected = state.openOnly,
                        onClick = { viewModel.onIntent(RestaurantListIntent.ToggleOpenOnly) },
                        label = { Text("Open Now") }
                    )
                }
            }

            FoodFlowPullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { viewModel.onIntent(RestaurantListIntent.Refresh) },
                testTag = "restaurant_list_pull_to_refresh",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("restaurant_paginated_list"),
                contentPadding = foodFlowScreenListPadding(
                    horizontal = FoodFlowSpacing.md,
                    top = FoodFlowSpacing.sm,
                    bottomExtra = 24.dp,
                    includeNavBarInset = true
                ),
                verticalArrangement = Arrangement.spacedBy(FoodFlowSpacing.md)
            ) {
                if (state.isOffline) {
                    item(key = "offline_banner", contentType = "offline_banner") {
                        OfflineBanner(isOffline = true)
                    }
                }

                when {
                    state.loadState is PaginationLoadState.LoadingInitial && state.visibleRestaurants.isEmpty() -> {
                        item(key = "initial_loading", contentType = "loading_indicator") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                    state.loadState is PaginationLoadState.Error && state.visibleRestaurants.isEmpty() -> {
                        item(key = "initial_error", contentType = "error_footer") {
                            PaginationFooterSlot(
                                loadState = state.loadState,
                                itemLabel = "restaurants",
                                loadedCount = 0,
                                totalCount = 0,
                                onRetry = { viewModel.onIntent(RestaurantListIntent.RetryPagination) }
                            )
                        }
                    }
                    state.visibleRestaurants.isEmpty() -> {
                        item(key = "empty", contentType = "empty_state") {
                            FoodFlowEmptyState(
                                icon = Icons.Filled.Search,
                                title = "No restaurants found",
                                subtitle = "Adjust your filters or explore all kitchens.",
                                actionLabel = "Show All",
                                onAction = { viewModel.onIntent(RestaurantListIntent.ResetFilters) }
                            )
                        }
                    }
                    else -> {
                        items(
                            items = state.visibleRestaurants,
                            key = { it.id },
                            contentType = { "restaurant_card" }
                        ) { restaurant ->
                            RestaurantCard(
                                restaurant = restaurant,
                                onClick = { onOpenRestaurant(restaurant.id) },
                                onToggleFavorite = { viewModel.onIntent(RestaurantListIntent.ToggleFavorite(restaurant.id)) }
                            )
                        }

                        item(key = "pagination_footer", contentType = "pagination_footer") {
                            PaginationFooterSlot(
                                loadState = state.loadState,
                                itemLabel = "restaurants",
                                loadedCount = state.visibleRestaurants.size,
                                totalCount = state.totalCount,
                                onRetry = { viewModel.onIntent(RestaurantListIntent.RetryPagination) }
                            )
                        }
                    }
                }
            }
            }
        }
    }
}
