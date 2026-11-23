package com.foodflow.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodflow.core.designsystem.components.FoodFlowEmptyState
import com.foodflow.core.designsystem.components.FoodFlowLogoMark
import com.foodflow.core.designsystem.components.FoodFlowPullToRefreshBox
import com.foodflow.core.designsystem.components.FoodFlowTopBar
import com.foodflow.core.designsystem.components.FoodImage
import com.foodflow.core.designsystem.components.OfflineBanner
import com.foodflow.core.designsystem.components.RatingBadge
import com.foodflow.core.designsystem.components.RestaurantCard
import com.foodflow.core.designsystem.components.ShimmerRestaurantSkeleton
import com.foodflow.core.designsystem.theme.FoodFlowSpacing
import com.foodflow.core.designsystem.theme.FoodFlowThemeTokens
import com.foodflow.domain.model.FoodCategory
import com.foodflow.domain.model.Order
import com.foodflow.domain.model.OrderStatus
import com.foodflow.domain.model.PromoBanner
import com.foodflow.domain.model.Restaurant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.absoluteValue

/** Hosts the dashboard state, effects, and user actions. */
@Composable
fun HomeScreen(
    onOpenLocation: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenRestaurantList: (String?) -> Unit,
    onOpenRestaurantDetail: (String) -> Unit,
    onOpenTracking: (String) -> Unit,
    onOpenFavorites: () -> Unit,
    onScrollAtTopChanged: (Boolean) -> Unit = {},
    bottomContentPadding: Dp = FoodFlowSpacing.xl,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is HomeEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    HomeScreenContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onIntent = viewModel::onIntent,
        onOpenLocation = onOpenLocation,
        onOpenSearch = onOpenSearch,
        onOpenRestaurantList = onOpenRestaurantList,
        onOpenRestaurantDetail = onOpenRestaurantDetail,
        onOpenTracking = onOpenTracking,
        onOpenFavorites = onOpenFavorites,
        onScrollAtTopChanged = onScrollAtTopChanged,
        bottomContentPadding = bottomContentPadding
    )
}

/** Renders the dashboard content from its current state and callbacks. */
@Composable
fun HomeScreenContent(
    state: HomeUiState,
    snackbarHostState: SnackbarHostState,
    onIntent: (HomeIntent) -> Unit,
    onOpenLocation: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenRestaurantList: (String?) -> Unit,
    onOpenRestaurantDetail: (String) -> Unit,
    onOpenTracking: (String) -> Unit,
    onOpenFavorites: () -> Unit,
    onScrollAtTopChanged: (Boolean) -> Unit = {},
    bottomContentPadding: Dp = FoodFlowSpacing.xl
) {
    val listState = rememberLazyListState()
    val isAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 12
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { isAtTop }.collectLatest { atTop ->
            onScrollAtTopChanged(atTop)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            FoodFlowTopBar(
                title = "",
                titleContent = {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onOpenLocation)
                            .padding(vertical = 4.dp, horizontal = 4.dp)
                            .testTag("home_location_selector"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FoodFlowLogoMark(size = 34.dp)
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.LocationOn,
                                    contentDescription = "Selected delivery location",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = state.selectedAddress?.label?.displayName ?: "Select Location",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "Change delivery address",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = state.selectedAddress?.line1 ?: "Tap to choose delivery address",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    HomeFavoriteActionButton(
                        favoriteCount = state.favoriteCount,
                        onClick = onOpenFavorites
                    )
                }
            )
        }
    ) { padding ->
        FoodFlowPullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { onIntent(HomeIntent.Refresh) },
            testTag = "home_pull_to_refresh",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("home_feed_list"),
            contentPadding = PaddingValues(
                start = FoodFlowSpacing.md,
                end = FoodFlowSpacing.md,
                top = 10.dp,
                bottom = bottomContentPadding
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (state.isOffline) {
                item(key = "offline_banner") {
                    OfflineBanner(isOffline = true)
                }
            }

            val bannerOrder = state.activeOrder ?: state.deliveredOrderBanner
            if (bannerOrder != null) {
                item(key = "active_order_banner") {
                    ActiveOrderBannerCard(
                        order = bannerOrder,
                        onTrack = { onOpenTracking(bannerOrder.id) },
                        onDismissDelivered = { onIntent(HomeIntent.DismissDeliveredBanner) }
                    )
                }
            }

            item(key = "search_bar") {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClick = onOpenSearch)
                        .testTag("home_search_bar"),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Search biryani, pizza, dosa, kitchens...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            item(key = "promo_carousel") {
                AutoScrollingPromoCarousel(
                    banners = state.promoBanners,
                    onBannerClick = { banner -> onOpenRestaurantList(banner.categoryFilter) }
                )
            }

            item(key = "categories_header") {
                SectionHeader(
                    title = "What's on your mind?",
                    actionLabel = "See all",
                    onAction = { onOpenRestaurantList(null) }
                )
            }

            item(key = "categories_strip") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.testTag("home_categories_row")
                ) {
                    itemsIndexed(state.categories, key = { _, cat -> cat.id }) { index, category ->
                        AnimatedCategoryItem(
                            category = category,
                            index = index,
                            selected = state.selectedCategory == category.name,
                            onClick = { onIntent(HomeIntent.SelectCategory(category.name)) }
                        )
                    }
                }
            }

            item(key = "quick_filters") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = state.pureVegOnly,
                            onClick = { onIntent(HomeIntent.TogglePureVeg) },
                            label = { Text("Pure Veg") },
                            leadingIcon = { Icon(Icons.Filled.Eco, null, Modifier.size(16.dp)) },
                            modifier = Modifier.testTag("filter_pure_veg")
                        )
                    }
                    item {
                        FilterChip(
                            selected = state.fastDeliveryOnly,
                            onClick = { onIntent(HomeIntent.ToggleFastDelivery) },
                            label = { Text("Under 30 mins") },
                            leadingIcon = { Icon(Icons.Filled.Bolt, null, Modifier.size(16.dp)) },
                            modifier = Modifier.testTag("filter_fast_delivery")
                        )
                    }
                    item {
                        FilterChip(
                            selected = state.topRatedOnly,
                            onClick = { onIntent(HomeIntent.ToggleTopRated) },
                            label = { Text("4.4+ Rated") },
                            leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.size(16.dp)) },
                            modifier = Modifier.testTag("filter_top_rated")
                        )
                    }
                    item {
                        FilterChip(
                            selected = false,
                            onClick = onOpenFavorites,
                            label = { Text("Favourites") },
                            leadingIcon = { Icon(Icons.Filled.Tune, null, Modifier.size(16.dp)) },
                            modifier = Modifier.testTag("home_favorites_chip")
                        )
                    }
                }
            }

            if (state.popularRestaurants.isNotEmpty() && state.selectedCategory == null) {
                item(key = "popular_header") {
                    SectionHeader(
                        title = "Top Rated Near You",
                        actionLabel = "Explore",
                        onAction = { onOpenRestaurantList(null) }
                    )
                }
                item(key = "popular_carousel") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(state.popularRestaurants, key = { _, it -> "pop_${it.id}" }) { _, restaurant ->
                            PopularRestaurantCompactCard(
                                restaurant = restaurant,
                                onClick = { onOpenRestaurantDetail(restaurant.id) }
                            )
                        }
                    }
                }
            }

            item(key = "all_restaurants_header") {
                val heading = if (state.selectedCategory != null) {
                    "${state.selectedCategory} Kitchens (${state.filteredRestaurants.size})"
                } else {
                    "All Restaurants (${state.filteredRestaurants.size})"
                }
                SectionHeader(
                    title = heading,
                    actionLabel = if (state.selectedCategory != null) "Clear" else "View All",
                    onAction = {
                        if (state.selectedCategory != null) onIntent(HomeIntent.SelectCategory(null))
                        else onOpenRestaurantList(null)
                    }
                )
            }

            if (state.isLoading) {
                items(
                    count = 3,
                    key = { index -> "shimmer_restaurant_$index" },
                    contentType = { "shimmer_skeleton" }
                ) {
                    ShimmerRestaurantSkeleton()
                }
            } else if (state.filteredRestaurants.isEmpty()) {
                item(key = "empty_restaurants", contentType = "empty_state") {
                    FoodFlowEmptyState(
                        icon = Icons.Filled.Search,
                        title = "No matching kitchens",
                        subtitle = "Try clearing a filter or exploring another cuisine.",
                        actionLabel = "Reset Filters",
                        onAction = {
                            onIntent(HomeIntent.SelectCategory(null))
                            if (state.pureVegOnly) onIntent(HomeIntent.TogglePureVeg)
                            if (state.fastDeliveryOnly) onIntent(HomeIntent.ToggleFastDelivery)
                            if (state.topRatedOnly) onIntent(HomeIntent.ToggleTopRated)
                        }
                    )
                }
            } else {
                itemsIndexed(
                    items = state.filteredRestaurants,
                    key = { _, it -> it.id },
                    contentType = { _, _ -> "restaurant_card" }
                ) { _, restaurant ->
                    RestaurantCard(
                        restaurant = restaurant,
                        onClick = { onOpenRestaurantDetail(restaurant.id) },
                        onToggleFavorite = { onIntent(HomeIntent.ToggleFavorite(restaurant.id)) }
                    )
                }
            }
        }
        }
    }
}

/** Shows the active order's status and its tracking or dismiss action. */
@Composable
private fun ActiveOrderBannerCard(
    order: Order,
    onTrack: () -> Unit,
    onDismissDelivered: () -> Unit
) {
    val isDelivered = order.orderStatus == OrderStatus.DELIVERED
    val extended = FoodFlowThemeTokens.extendedColors
    val displayStatus = when {
        isDelivered -> "Delivered"
        order.orderStatus == OrderStatus.PICKED_UP && (order.riderLocation?.progressFraction ?: 0f) > 0.25f ->
            "On the Way"
        else -> order.orderStatus.label
    }
    val subtitleText = when {
        isDelivered -> "Order delivered safely · Enjoy your meal!"
        order.orderStatus == OrderStatus.PICKED_UP -> {
            val partnerSuffix = order.deliveryPartner?.name?.let { " · $it" }.orEmpty()
            "Arriving in ~${order.estimatedDeliveryMinutes} mins$partnerSuffix"
        }
        else -> "Arriving in ~${order.estimatedDeliveryMinutes} mins"
    }

    val containerColor by animateColorAsState(
        targetValue = if (isDelivered) extended.vegSurface else MaterialTheme.colorScheme.primaryContainer,
        animationSpec = tween(300),
        label = "banner_container_color"
    )
    val badgeColor by animateColorAsState(
        targetValue = if (isDelivered) extended.vegGreen else MaterialTheme.colorScheme.primary,
        animationSpec = tween(300),
        label = "banner_badge_color"
    )
    val contentColor = if (isDelivered) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTrack)
            .testTag(if (isDelivered) "home_delivered_order_banner" else "home_active_order_banner"),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.40f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = badgeColor,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isDelivered) Icons.Filled.CheckCircle else Icons.Filled.LocalShipping,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = "${order.restaurantName} · $displayStatus",
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                    label = "home_order_status_text"
                ) { title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("home_order_banner_status")
                    )
                }
                AnimatedContent(
                    targetState = subtitleText,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                    label = "home_order_eta_text"
                ) { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.82f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("home_order_banner_eta")
                    )
                }
            }
            if (isDelivered) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Button(
                        onClick = onTrack,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = badgeColor),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("home_view_delivered_order_button")
                    ) {
                        Text("View", style = MaterialTheme.typography.labelLarge)
                    }
                    IconButton(
                        onClick = onDismissDelivered,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("home_dismiss_delivered_banner")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Dismiss delivered order banner",
                            tint = contentColor.copy(alpha = 0.75f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            } else {
                Button(
                    onClick = onTrack,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("home_track_order_button")
                ) {
                    Text("Track Order", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** Rotates through promotional banners and forwards the selected banner. */
@Composable
private fun AutoScrollingPromoCarousel(
    banners: List<PromoBanner>,
    onBannerClick: (PromoBanner) -> Unit
) {
    if (banners.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { banners.size })
    val isDragged by pagerState.interactionSource.collectIsDraggedAsState()

    LaunchedEffect(pagerState, isDragged) {
        if (!isDragged && banners.size > 1) {
            while (true) {
                delay(4_000L)
                val nextPage = (pagerState.currentPage + 1) % banners.size
                pagerState.animateScrollToPage(
                    page = nextPage,
                    animationSpec = tween(durationMillis = 520)
                )
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.testTag("home_promo_carousel")
    ) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 12.dp,
            contentPadding = PaddingValues(end = 16.dp),
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            val banner = banners[page]
            val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue
            val cardScale = lerp(0.95f, 1f, 1f - pageOffset.coerceIn(0f, 1f))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(156.dp)
                    .graphicsLayer {
                        scaleX = cardScale
                        scaleY = cardScale
                    }
                    .clickable { onBannerClick(banner) }
                    .testTag("promo_banner_${banner.id}"),
                shape = MaterialTheme.shapes.large,
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    FoodImage(
                        imageKey = banner.imageKey,
                        contentDescription = banner.title,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color(banner.accentHex).copy(alpha = 0.92f),
                                        Color(banner.accentHex).copy(alpha = 0.68f),
                                        Color.Black.copy(alpha = 0.25f)
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(FoodFlowSpacing.md),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.White.copy(alpha = 0.22f)
                            ) {
                                Text(
                                    text = banner.tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = banner.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = banner.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.92f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color.White
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = banner.ctaLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(banner.accentHex)
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = Color(banner.accentHex),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Animated page indicators
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            banners.forEachIndexed { idx, _ ->
                val active = pagerState.currentPage == idx
                val dotWidth by animateDpAsState(
                    targetValue = if (active) 22.dp else 7.dp,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
                    label = "dot_width_$idx"
                )
                val dotColor by animateColorAsState(
                    targetValue = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    animationSpec = tween(220),
                    label = "dot_color_$idx"
                )
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .height(7.dp)
                        .width(dotWidth)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
        }
    }
}

/** Displays a selectable food category with its selection animation. */
@Composable
private fun AnimatedCategoryItem(
    category: FoodCategory,
    index: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay((index * 35L).coerceAtMost(240L))
        entered = true
    }

    val entryAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(280),
        label = "cat_alpha_${category.id}"
    )
    val selectionScale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "cat_scale_${category.id}"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(200),
        label = "cat_border_${category.id}"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(80.dp)
            .graphicsLayer { alpha = entryAlpha }
            .scale(selectionScale)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
            .testTag("category_${category.name.lowercase().replace(" ", "_")}")
    ) {
        Surface(
            shape = CircleShape,
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            border = BorderStroke(if (selected) 2.5.dp else 1.dp, borderColor),
            shadowElevation = if (selected) 4.dp else 1.dp,
            modifier = Modifier.size(66.dp)
        ) {
            FoodImage(
                imageKey = category.imageKey,
                contentDescription = category.name,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .clip(CircleShape)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = category.name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Shows a section title and its optional action button. */
@Composable
private fun SectionHeader(
    title: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        TextButton(onClick = onAction) {
            Text(actionLabel, color = MaterialTheme.colorScheme.primary)
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** Shows a compact restaurant card for the popular-restaurants row. */
@Composable
private fun PopularRestaurantCompactCard(
    restaurant: Restaurant,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(216.dp)
            .clickable(onClick = onClick)
            .testTag("popular_restaurant_${restaurant.id}"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(106.dp)
            ) {
                FoodImage(
                    imageKey = restaurant.imageKey,
                    contentDescription = restaurant.name,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    RatingBadge(rating = restaurant.rating)
                }
            }
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = restaurant.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${restaurant.deliveryTimeMin} mins • ${restaurant.cuisines.firstOrNull().orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Opens favorites and displays the current saved-restaurant count. */
@Composable
private fun HomeFavoriteActionButton(
    favoriteCount: Int,
    onClick: () -> Unit
) {
    val hasFavorites = favoriteCount > 0
    val heartScale by animateFloatAsState(
        targetValue = if (hasFavorites) 1.08f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "home_favorite_heart_scale"
    )
    val heartColor by animateColorAsState(
        targetValue = if (hasFavorites) {
            Color(0xFFE53955)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(200),
        label = "home_favorite_heart_color"
    )

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag("home_favorite_button"),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (hasFavorites) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = if (hasFavorites) "Favorites, $favoriteCount saved" else "Favorites, none saved",
            tint = heartColor,
            modifier = Modifier
                .size(24.dp)
                .scale(heartScale)
        )
        if (hasFavorites) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.background),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 2.dp)
                    .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                    .testTag("home_favorite_badge")
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = if (favoriteCount > 99) "99+" else favoriteCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

