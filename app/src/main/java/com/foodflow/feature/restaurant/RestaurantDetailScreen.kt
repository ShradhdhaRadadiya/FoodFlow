package com.foodflow.feature.restaurant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodflow.core.common.toInr
import com.foodflow.core.designsystem.components.FoodFlowPullToRefreshBox
import com.foodflow.core.designsystem.components.FoodFlowTopBar
import com.foodflow.core.designsystem.components.FoodImage
import com.foodflow.core.designsystem.components.FoodItemCard
import com.foodflow.core.designsystem.components.OfflineBanner
import com.foodflow.core.designsystem.components.PaginationFooterSlot
import com.foodflow.core.designsystem.components.QuantityStepper
import com.foodflow.core.designsystem.components.RatingBadge
import com.foodflow.core.designsystem.components.VegNonVegIndicator
import com.foodflow.core.designsystem.components.foodFlowBottomBarInsets
import com.foodflow.core.designsystem.components.foodFlowScreenListPadding
import com.foodflow.core.designsystem.theme.FoodFlowSpacing
import com.foodflow.core.designsystem.theme.JetBrainsMonoFontFamily
import com.foodflow.domain.model.FoodAddon
import com.foodflow.domain.model.FoodItem
import com.foodflow.domain.model.PaginationLoadState
import com.foodflow.domain.model.Restaurant
import com.foodflow.domain.model.RestaurantRatingCalculator
import com.foodflow.domain.model.RestaurantReview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shows a restaurant's details, menu, reviews, and food customization flow. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RestaurantDetailScreen(
    restaurantId: String,
    onBack: () -> Unit,
    onOpenCart: () -> Unit,
    viewModel: RestaurantDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(restaurantId) {
        viewModel.onIntent(RestaurantDetailIntent.Load(restaurantId))
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is RestaurantDetailEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            FoodFlowTopBar(
                title = state.restaurant?.name ?: "Restaurant",
                subtitle = state.restaurant?.let { "${it.deliveryTimeMin} mins • ${it.distanceKm} km" },
                onBack = onBack,
                actions = {
                    state.restaurant?.let { rest ->
                        IconButton(
                            onClick = { viewModel.onIntent(RestaurantDetailIntent.ToggleFavorite) },
                            modifier = Modifier.testTag("detail_favorite_button")
                        ) {
                            Icon(
                                imageVector = if (rest.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = "Toggle favourite",
                                tint = if (rest.isFavorite) Color(0xFFFF4D6D) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            com.foodflow.core.designsystem.components.FloatingCartPreviewBar(
                itemCount = state.cartTotalItems,
                totalAmount = state.cartTotalAmount,
                restaurantName = state.restaurant?.name.orEmpty(),
                onViewCart = onOpenCart,
                containerTestTag = "view_cart_sticky_bar",
                ctaTestTag = "detail_view_cart_cta",
                modifier = Modifier
                    .fillMaxWidth()
                    .foodFlowBottomBarInsets()
                    .padding(horizontal = FoodFlowSpacing.md, vertical = 10.dp)
            )
        }
    ) { padding ->
        val restaurant = state.restaurant
        if (restaurant == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Loading menu...")
            }
        } else {
            val grouped = remember(state.filteredMenu) {
                state.filteredMenu.groupBy { it.category }
            }
            val categories = remember(grouped) { grouped.keys.toList() }
            val popularDishes = remember(state.menuItems, state.vegOnly, state.searchQuery) {
                if (state.searchQuery.isNotBlank()) emptyList()
                else state.menuItems
                    .filter { (!state.vegOnly || it.isVeg) && it.isAvailable && (it.isBestseller || it.rating >= 4.5) }
                    .sortedByDescending { it.rating }
                    .take(6)
            }

            val categoryHeaderIndices = remember(
                grouped,
                state.isOffline,
                popularDishes.isNotEmpty()
            ) {
                val map = LinkedHashMap<String, Int>()
                var cursor = 0
                if (state.isOffline) cursor += 1 // offline_banner
                cursor += 1 // hero_image
                cursor += 1 // restaurant_meta_card
                if (popularDishes.isNotEmpty()) cursor += 1 // popular_dishes_strip
                cursor += 1 // menu_filters
                if (categories.size > 1) cursor += 1 // sticky_category_bar
                grouped.forEach { (cat, list) ->
                    map[cat] = cursor
                    cursor += 1 + list.size
                }
                map
            }

            val activeCategory by remember(listState, categoryHeaderIndices) {
                derivedStateOf {
                    val firstVisible = listState.firstVisibleItemIndex
                    var current = categories.firstOrNull().orEmpty()
                    categoryHeaderIndices.forEach { (cat, idx) ->
                        if (firstVisible + 1 >= idx) {
                            current = cat
                        }
                    }
                    current
                }
            }

            val shouldLoadMoreMenu by remember(
                listState,
                state.menuHasNextPage,
                state.menuLoadState,
                state.filteredMenu.size
            ) {
                derivedStateOf {
                    if (!state.menuHasNextPage || state.menuLoadState != PaginationLoadState.Idle) {
                        return@derivedStateOf false
                    }
                    val total = listState.layoutInfo.totalItemsCount
                    val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                        ?: return@derivedStateOf false
                    total > 0 && lastVisible >= total - 3
                }
            }

            LaunchedEffect(shouldLoadMoreMenu) {
                if (shouldLoadMoreMenu) {
                    viewModel.onIntent(RestaurantDetailIntent.LoadNextMenuPage)
                }
            }

            FoodFlowPullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { viewModel.onIntent(RestaurantDetailIntent.Refresh) },
                testTag = "restaurant_detail_pull_to_refresh",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("restaurant_detail_list"),
                contentPadding = foodFlowScreenListPadding(
                    horizontal = FoodFlowSpacing.md,
                    top = FoodFlowSpacing.sm,
                    bottomExtra = if (state.cartTotalItems > 0) 20.dp else 28.dp,
                    includeNavBarInset = state.cartTotalItems == 0
                ),
                verticalArrangement = Arrangement.spacedBy(FoodFlowSpacing.sm)
            ) {
                if (state.isOffline) {
                    item(key = "offline_banner", contentType = "offline_banner") {
                        OfflineBanner(isOffline = true)
                    }
                }

                item(key = "hero_image", contentType = "hero_image") {
                    FoodImage(
                        imageKey = restaurant.imageKey,
                        contentDescription = restaurant.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(184.dp)
                            .clip(MaterialTheme.shapes.large)
                    )
                }

                item(key = "restaurant_meta_card", contentType = "restaurant_meta") {
                    RestaurantOverviewCard(restaurant = restaurant)
                }

                if (popularDishes.isNotEmpty()) {
                    item(key = "popular_dishes_strip", contentType = "popular_dishes_strip") {
                        PopularDishesStrip(
                            dishes = popularDishes,
                            cartQuantities = state.cartQuantities,
                            onAddClick = { dish ->
                                viewModel.onIntent(RestaurantDetailIntent.RequestAddItem(dish))
                            },
                            onIncrement = { dish, qty ->
                                viewModel.onIntent(RestaurantDetailIntent.IncrementItem(dish.id, qty))
                            },
                            onDecrement = { dish, qty ->
                                viewModel.onIntent(RestaurantDetailIntent.DecrementItem(dish.id, qty))
                            }
                        )
                    }
                }

                item(key = "menu_filters", contentType = "menu_filters") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = { viewModel.onIntent(RestaurantDetailIntent.SearchChanged(it)) },
                            placeholder = { Text("Search in menu...") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = state.vegOnly,
                            onClick = { viewModel.onIntent(RestaurantDetailIntent.ToggleVegOnly) },
                            label = { Text("Veg") },
                            leadingIcon = { Icon(Icons.Filled.Eco, null, Modifier.size(16.dp)) }
                        )
                    }
                }

                if (categories.size > 1) {
                    stickyHeader(key = "sticky_category_bar") {
                        Surface(
                            color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
                            shadowElevation = 2.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("restaurant_sticky_category_bar")
                        ) {
                            LazyRow(
                                contentPadding = PaddingValues(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(categories, key = { "cat_pill_$it" }) { category ->
                                    val selected = category == activeCategory
                                    val count = grouped[category]?.size ?: 0
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            val target = categoryHeaderIndices[category] ?: return@FilterChip
                                            coroutineScope.launch {
                                                listState.animateScrollToItem(target)
                                            }
                                        },
                                        label = {
                                            Text(
                                                text = "$category ($count)",
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        ),
                                        modifier = Modifier.testTag("category_pill_${category.lowercase().replace(" ", "_")}")
                                    )
                                }
                            }
                        }
                    }
                }

                grouped.forEach { (categoryName, itemsInCategory) ->
                    item(key = "header_$categoryName", contentType = "category_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = categoryName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${itemsInCategory.size} items",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(
                        items = itemsInCategory,
                        key = { it.id },
                        contentType = { "menu_food_item" }
                    ) { foodItem ->
                        val qty = state.cartQuantities[foodItem.id] ?: 0
                        FoodItemCard(
                            item = foodItem,
                            quantityInCart = qty,
                            onAddClick = { viewModel.onIntent(RestaurantDetailIntent.RequestAddItem(foodItem)) },
                            onIncrement = { viewModel.onIntent(RestaurantDetailIntent.IncrementItem(foodItem.id, qty)) },
                            onDecrement = { viewModel.onIntent(RestaurantDetailIntent.DecrementItem(foodItem.id, qty)) }
                        )
                    }
                }

                item(key = "menu_pagination_footer", contentType = "pagination_footer") {
                    PaginationFooterSlot(
                        loadState = state.menuLoadState,
                        itemLabel = "dishes",
                        loadedCount = state.filteredMenu.size,
                        totalCount = state.menuTotalCount.coerceAtLeast(state.filteredMenu.size),
                        onRetry = { viewModel.onIntent(RestaurantDetailIntent.RetryMenuPagination) }
                    )
                }

                if (state.reviews.isNotEmpty()) {
                    item(key = "recent_reviews_section", contentType = "recent_reviews") {
                        RecentReviewsSection(reviews = state.reviews)
                    }
                }
            }
            }
        }
    }

    state.customizingItem?.let { item ->
        CustomizeFoodDialog(
            item = item,
            onDismiss = { viewModel.onIntent(RestaurantDetailIntent.DismissCustomization) },
            onConfirm = { addons, notes ->
                viewModel.onIntent(RestaurantDetailIntent.ConfirmAddCustomized(item, addons, notes))
            }
        )
    }

    state.mismatchMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.onIntent(RestaurantDetailIntent.DismissMismatchDialog) },
            title = { Text("Replace cart items?") },
            text = { Text(message) },
            confirmButton = {
                Button(
                    onClick = { viewModel.onIntent(RestaurantDetailIntent.ConfirmClearCartAndAdd) },
                    modifier = Modifier.testTag("confirm_replace_cart_button")
                ) {
                    Text("Clear & Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onIntent(RestaurantDetailIntent.DismissMismatchDialog) }) {
                    Text("Keep Current")
                }
            }
        )
    }
}

/** Summarizes the restaurant's address, rating, and delivery details. */
@Composable
private fun RestaurantOverviewCard(restaurant: Restaurant) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = restaurant.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = restaurant.cuisines.joinToString(" • "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                RatingBadge(rating = restaurant.rating, reviewCount = restaurant.reviewCount)
            }

            Text(
                text = restaurant.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                RestaurantMetaPill(
                    icon = Icons.Filled.AccessTime,
                    label = "${restaurant.deliveryTimeMin} mins",
                    caption = "Delivery"
                )
                RestaurantMetaPill(
                    icon = Icons.Filled.DeliveryDining,
                    label = if (restaurant.deliveryFee <= 0.0) "Free" else restaurant.deliveryFee.toInr(),
                    caption = "Delivery fee"
                )
                RestaurantMetaPill(
                    icon = Icons.Filled.Restaurant,
                    label = "₹${restaurant.priceForTwo}",
                    caption = "For two"
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Verified,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    val formattedAvg = RestaurantRatingCalculator.formatRating(
                        rating = restaurant.rating,
                        reviewCount = restaurant.reviewCount
                    )
                    val summaryLabel = if (restaurant.reviewCount <= 0) {
                        "New kitchen · Be the first to rate after your meal"
                    } else {
                        "$formattedAvg ★ (${restaurant.reviewCount} verified diner review${if (restaurant.reviewCount == 1) "" else "s"}) • Freshly cooked & tamper-proof packaging"
                    }
                    Text(
                        text = summaryLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("detail_verified_rating_summary")
                    )
                }
            }

            if (!restaurant.isOpen) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "This kitchen is currently closed. You can browse the menu for later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }
    }
}

/** Displays one compact restaurant detail with an icon and caption. */
@Composable
private fun RestaurantMetaPill(
    icon: ImageVector,
    label: String,
    caption: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Shows popular dishes with controls for adding or changing quantities. */
@Composable
private fun PopularDishesStrip(
    dishes: List<FoodItem>,
    cartQuantities: Map<String, Int>,
    onAddClick: (FoodItem) -> Unit,
    onIncrement: (FoodItem, Int) -> Unit,
    onDecrement: (FoodItem, Int) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("popular_dishes_strip")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.LocalFireDepartment,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Popular Dishes",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(dishes, key = { "pop_${it.id}" }) { dish ->
                val qty = cartQuantities[dish.id] ?: 0
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.width(172.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box {
                            FoodImage(
                                imageKey = dish.imageKey,
                                contentDescription = dish.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(92.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Star,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = String.format("%.1f", dish.rating),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            VegNonVegIndicator(isVeg = dish.isVeg, size = 13.dp)
                            Text(
                                text = dish.name,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = dish.price.toInr(),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (qty > 0) {
                                QuantityStepper(
                                    quantity = qty,
                                    onIncrement = { onIncrement(dish, qty) },
                                    onDecrement = { onDecrement(dish, qty) },
                                    itemTagPrefix = "popular_${dish.id}",
                                    itemKey = "popular_${dish.id}"
                                )
                            } else {
                                FilledTonalButton(
                                    onClick = { onAddClick(dish) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text("Add", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Lets the user choose add-ons and instructions for a food item. */
@Composable
private fun CustomizeFoodDialog(
    item: FoodItem,
    onDismiss: () -> Unit,
    onConfirm: (List<FoodAddon>, String) -> Unit
) {
    val selectedAddons = remember { mutableStateListOf<FoodAddon>() }
    var instructions by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Customise ${item.name}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Add-ons (Optional)", style = MaterialTheme.typography.titleSmall)
                item.addons.forEach { addon ->
                    val checked = addon in selectedAddons
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (checked) selectedAddons.remove(addon) else selectedAddons.add(addon)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    if (it) selectedAddons.add(addon) else selectedAddons.remove(addon)
                                }
                            )
                            Text(addon.name, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text("+${addon.price.toInr()}", style = MaterialTheme.typography.labelMedium)
                    }
                }
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("Cooking instructions") },
                    placeholder = { Text("e.g., Less spicy, extra chutney") },
                    singleLine = false,
                    minLines = 1,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            val total = item.price + selectedAddons.sumOf { it.price }
            Button(
                onClick = { onConfirm(selectedAddons.toList(), instructions) },
                modifier = Modifier.testTag("confirm_addon_add_button")
            ) {
                Text("Add · ${total.toInr()}")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Shows recent customer reviews for the restaurant. */
@Composable
private fun RecentReviewsSection(reviews: List<RestaurantReview>) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag("restaurant_recent_reviews_section")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Recent Diner Reviews (${reviews.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            reviews.take(5).forEachIndexed { index, review ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = review.userName,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        RatingBadge(rating = review.rating.toDouble())
                    }
                    if (review.comment.isNotBlank()) {
                        Text(
                            text = review.comment,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val dateText = remember(review.updatedAtMs) {
                        SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(review.updatedAtMs))
                    }
                    Text(
                        text = "Verified order • $dateText",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
