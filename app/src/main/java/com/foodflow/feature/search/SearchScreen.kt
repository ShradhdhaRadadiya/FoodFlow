package com.foodflow.feature.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.FilterListOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodflow.core.common.toInr
import com.foodflow.core.designsystem.components.FoodFlowEmptyState
import com.foodflow.core.designsystem.components.FoodFlowErrorState
import com.foodflow.core.designsystem.components.FoodFlowTopBar
import com.foodflow.core.designsystem.components.FoodImage
import com.foodflow.core.designsystem.components.OfflineBanner
import com.foodflow.core.designsystem.components.RestaurantCard
import com.foodflow.core.designsystem.components.VegNonVegIndicator
import com.foodflow.core.designsystem.theme.FoodFlowSpacing
import com.foodflow.core.designsystem.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Provides restaurant and food search with filters and recent queries. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onOpenRestaurant: (String) -> Unit,
    onScrollAtTopChanged: (Boolean) -> Unit = {},
    bottomContentPadding: Dp = FoodFlowSpacing.xl,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val isAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 12
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { isAtTop }.collectLatest { onScrollAtTopChanged(it) }
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is SearchEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            FoodFlowTopBar(
                title = "Search",
                subtitle = "Restaurants, dishes & cuisines",
                actions = {
                    if (state.hasActiveFilters) {
                        TextButton(
                            onClick = { viewModel.onIntent(SearchIntent.ResetFilters) },
                            modifier = Modifier.testTag("search_reset_filters_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FilterListOff,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(" Reset")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = { viewModel.onIntent(SearchIntent.QueryChanged(it)) },
                placeholder = { Text("Search dishes, kitchens, cuisines...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.onIntent(SearchIntent.ClearQuery) },
                            modifier = Modifier.testTag("search_clear_button")
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { viewModel.onIntent(SearchIntent.SubmitSearch(state.query)) }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FoodFlowSpacing.md, vertical = FoodFlowSpacing.xs)
                    .testTag("search_input_field")
            )

            // Primary filter row: Dietary, Fast Delivery, Price Range, and Sort
            LazyRow(
                contentPadding = PaddingValues(horizontal = FoodFlowSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                items(SearchDietaryFilter.entries, key = { "diet_${it.name}" }) { dietary ->
                    FilterChip(
                        selected = state.dietaryFilter == dietary,
                        onClick = { viewModel.onIntent(SearchIntent.SelectDietary(dietary)) },
                        label = { Text(dietary.label) },
                        leadingIcon = if (dietary == SearchDietaryFilter.VEG) {
                            { Icon(Icons.Filled.Eco, null, Modifier.size(16.dp)) }
                        } else null,
                        modifier = Modifier.testTag("search_filter_${dietary.name.lowercase()}")
                    )
                }
                item(key = "fast_delivery_filter") {
                    FilterChip(
                        selected = state.fastDeliveryOnly,
                        onClick = { viewModel.onIntent(SearchIntent.ToggleFastDelivery) },
                        label = { Text("Under 30m") },
                        leadingIcon = { Icon(Icons.Filled.Bolt, null, Modifier.size(16.dp)) },
                        modifier = Modifier.testTag("search_filter_fast")
                    )
                }
                items(SearchPriceFilter.entries.filter { it != SearchPriceFilter.ALL }, key = { "price_${it.name}" }) { price ->
                    val selected = state.priceFilter == price
                    FilterChip(
                        selected = selected,
                        onClick = {
                            viewModel.onIntent(
                                SearchIntent.SelectPriceFilter(if (selected) SearchPriceFilter.ALL else price)
                            )
                        },
                        label = { Text(price.label) },
                        modifier = Modifier.testTag("search_price_${price.name.lowercase()}")
                    )
                }
                items(SearchSortOption.entries, key = { "sort_${it.name}" }) { option ->
                    FilterChip(
                        selected = state.sortBy == option,
                        onClick = { viewModel.onIntent(SearchIntent.SelectSort(option)) },
                        label = { Text(option.label) },
                        modifier = Modifier.testTag("search_sort_${option.name.lowercase()}")
                    )
                }
            }

            // Cuisine filter chips strip
            if (state.availableCuisines.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = FoodFlowSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = FoodFlowSpacing.xs)
                ) {
                    item(key = "cuisine_all") {
                        FilterChip(
                            selected = state.selectedCuisine == null,
                            onClick = { viewModel.onIntent(SearchIntent.SelectCuisine(null)) },
                            label = { Text("All Cuisines") },
                            leadingIcon = { Icon(Icons.Filled.RestaurantMenu, null, Modifier.size(15.dp)) },
                            modifier = Modifier.testTag("search_cuisine_all")
                        )
                    }
                    items(state.availableCuisines, key = { "cuisine_$it" }) { cuisine ->
                        FilterChip(
                            selected = state.selectedCuisine == cuisine,
                            onClick = { viewModel.onIntent(SearchIntent.SelectCuisine(cuisine)) },
                            label = { Text(cuisine) },
                            modifier = Modifier.testTag("search_cuisine_${cuisine.lowercase().replace(" ", "_")}")
                        )
                    }
                }
            }

            if (state.isSearching) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FoodFlowSpacing.md)
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("search_results_list"),
                contentPadding = PaddingValues(
                    start = FoodFlowSpacing.md,
                    end = FoodFlowSpacing.md,
                    top = FoodFlowSpacing.xs,
                    bottom = bottomContentPadding
                ),
                verticalArrangement = Arrangement.spacedBy(FoodFlowSpacing.sm)
            ) {
                if (state.isOffline) {
                    item(key = "offline_banner", contentType = "offline_banner") {
                        OfflineBanner(isOffline = true)
                    }
                }

                if (state.errorMessage != null) {
                    item(key = "search_error", contentType = "error_state") {
                        FoodFlowErrorState(
                            message = state.errorMessage.orEmpty(),
                            onRetry = { viewModel.onIntent(SearchIntent.SubmitSearch(state.query)) }
                        )
                    }
                } else if (state.query.isBlank()) {
                    if (state.recentSearches.isNotEmpty()) {
                        item(key = "recent_header") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Recent Searches", style = MaterialTheme.typography.titleMedium)
                                TextButton(onClick = { viewModel.onIntent(SearchIntent.ClearHistory) }) {
                                    Text("Clear")
                                }
                            }
                        }
                        item(key = "recent_chips") {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                state.recentSearches.forEach { recent ->
                                    AssistChip(
                                        onClick = { viewModel.onIntent(SearchIntent.SubmitSearch(recent)) },
                                        label = { Text(recent) },
                                        leadingIcon = { Icon(Icons.Filled.History, null, Modifier.size(16.dp)) }
                                    )
                                }
                            }
                        }
                    }

                    item(key = "trending_header") {
                        Text(
                            text = "Popular & Trending",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    item(key = "trending_chips") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            state.trendingTags.forEach { tag ->
                                AssistChip(
                                    onClick = { viewModel.onIntent(SearchIntent.SubmitSearch(tag)) },
                                    label = { Text(tag) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.TrendingUp,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    modifier = Modifier.testTag("trending_${tag.lowercase()}")
                                )
                            }
                        }
                    }

                    val restaurants = state.filteredRestaurants
                    item(key = "explore_header") {
                        Text(
                            text = if (state.selectedCuisine != null) {
                                "${state.selectedCuisine} Kitchens (${restaurants.size})"
                            } else {
                                "Popular Kitchens (${restaurants.size})"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    if (restaurants.isEmpty()) {
                        item(key = "explore_empty_filtered") {
                            FoodFlowEmptyState(
                                icon = Icons.Filled.Search,
                                title = "No kitchens match your filters",
                                subtitle = "Try clearing dietary, cuisine, or price filters to explore more restaurants.",
                                actionLabel = "Reset Filters",
                                onAction = { viewModel.onIntent(SearchIntent.ResetFilters) }
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = restaurants,
                            key = { _, it -> "all_${it.id}" },
                            contentType = { _, _ -> "restaurant_card" }
                        ) { index, restaurant ->
                            AnimatedSearchCardWrapper(index = index) {
                                RestaurantCard(
                                    restaurant = restaurant,
                                    onClick = { onOpenRestaurant(restaurant.id) },
                                    onToggleFavorite = { viewModel.onIntent(SearchIntent.ToggleFavorite(restaurant.id)) }
                                )
                            }
                        }
                    }
                } else {
                    val restaurants = state.filteredRestaurants
                    val dishes = state.filteredDishes

                    if (restaurants.isEmpty() && dishes.isEmpty() && !state.isSearching) {
                        item(key = "search_empty", contentType = "empty_state") {
                            FoodFlowEmptyState(
                                icon = Icons.Filled.Search,
                                title = "No matches for \"${state.query}\"",
                                subtitle = if (state.hasActiveFilters) {
                                    "Try clearing your active filters or searching for Biryani, Paneer, Dosa, or Pizza."
                                } else {
                                    "Try searching for Biryani, Paneer, Dosa, or Pizza."
                                },
                                actionLabel = if (state.hasActiveFilters) "Reset Filters" else "Clear Search",
                                onAction = {
                                    if (state.hasActiveFilters) {
                                        viewModel.onIntent(SearchIntent.ResetFilters)
                                    } else {
                                        viewModel.onIntent(SearchIntent.ClearQuery)
                                    }
                                }
                            )
                        }
                    } else {
                        if (restaurants.isNotEmpty()) {
                            item(key = "res_results_header", contentType = "section_header") {
                                Text(
                                    text = "Restaurants (${restaurants.size})",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            itemsIndexed(
                                items = restaurants,
                                key = { _, it -> "res_${it.id}" },
                                contentType = { _, _ -> "restaurant_card" }
                            ) { index, restaurant ->
                                AnimatedSearchCardWrapper(index = index) {
                                    RestaurantCard(
                                        restaurant = restaurant,
                                        onClick = {
                                            viewModel.onIntent(SearchIntent.SubmitSearch(state.query))
                                            onOpenRestaurant(restaurant.id)
                                        },
                                        onToggleFavorite = { viewModel.onIntent(SearchIntent.ToggleFavorite(restaurant.id)) }
                                    )
                                }
                            }
                        }

                        if (dishes.isNotEmpty()) {
                            item(key = "dish_results_header", contentType = "section_header") {
                                Text(
                                    text = "Matching Dishes (${dishes.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                            items(
                                items = dishes,
                                key = { "dish_${it.id}" },
                                contentType = { "dish_card" }
                            ) { dish ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.onIntent(SearchIntent.SubmitSearch(state.query))
                                            onOpenRestaurant(dish.restaurantId)
                                        }
                                        .testTag("search_dish_${dish.id}"),
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        FoodImage(
                                            imageKey = dish.imageKey,
                                            contentDescription = dish.name,
                                            modifier = Modifier
                                                .size(60.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                VegNonVegIndicator(isVeg = dish.isVeg, size = 14.dp)
                                                Text(
                                                    text = dish.name,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Text(
                                                text = dish.category,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = dish.price.toInr(),
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontFamily = JetBrainsMonoFontFamily,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedSearchCardWrapper(
    index: Int,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay((index * 30L).coerceAtMost(180L))
        visible = true
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(240),
        label = "search_card_alpha"
    )
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(240)) + slideInVertically(tween(240)) { it / 12 }
    ) {
        Column(modifier = Modifier.graphicsLayer { this.alpha = alpha }) {
            content()
        }
    }
}
