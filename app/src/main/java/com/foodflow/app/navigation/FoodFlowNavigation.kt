package com.foodflow.app.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.foodflow.core.designsystem.components.FloatingCartPreviewBar
import com.foodflow.domain.model.VoiceConnectionState
import com.foodflow.domain.model.VoiceNavigationTarget
import com.foodflow.feature.auth.LoginScreen
import com.foodflow.feature.cart.CartScreen
import com.foodflow.feature.checkout.CheckoutScreen
import com.foodflow.feature.favorites.FavoritesScreen
import com.foodflow.feature.home.HomeScreen
import com.foodflow.feature.location.LocationScreen
import com.foodflow.feature.orderconfirmation.OrderConfirmationScreen
import com.foodflow.feature.orders.OrdersScreen
import com.foodflow.feature.payment.PaymentScreen
import com.foodflow.feature.profile.ProfileScreen
import com.foodflow.feature.restaurant.RestaurantDetailScreen
import com.foodflow.feature.restaurant.RestaurantListScreen
import com.foodflow.feature.scheduled.ScheduleConfirmationScreen
import com.foodflow.feature.scheduled.ScheduledOrdersScreen
import com.foodflow.feature.search.SearchScreen
import com.foodflow.feature.splash.SplashScreen
import com.foodflow.feature.support.SupportScreen
import com.foodflow.feature.tracking.OrderTrackingScreen
import com.foodflow.feature.voice.VoiceAssistantIntent
import com.foodflow.feature.voice.VoiceAssistantScreen
import com.foodflow.feature.voice.VoiceAssistantUiEffect
import com.foodflow.feature.voice.VoiceAssistantViewModel
import kotlinx.coroutines.launch

private const val TAB_HOME = 0
private const val TAB_SEARCH = 1
private const val TAB_CART = 2
private const val TAB_ORDERS = 3
private const val TAB_PROFILE = 4

/**
 * Hosts top-level destinations, shared navigation chrome, cart previews, and the persistent Voice Assistant.
 * Collects state lifecycle-safely and synchronizes tab swipes, navigation selection, and screen context.
 */
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun FoodFlowRoot(
    chromeViewModel: AppChromeViewModel = hiltViewModel(),
    voiceViewModel: VoiceAssistantViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination
    val cartCount by chromeViewModel.cartCount.collectAsStateWithLifecycle()
    val cartPreview by chromeViewModel.cartPreview.collectAsStateWithLifecycle()
    val voiceUiState by voiceViewModel.uiState.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    var micPermissionRequestSignal by remember { mutableIntStateOf(0) }

    val dashboardPagerState = rememberPagerState(
        initialPage = TAB_HOME,
        pageCount = { FoodFlowTopLevelDestinations.size }
    )

    val rootDensity = LocalDensity.current
    val navScrollHysteresisPx = remember(rootDensity) { with(rootDensity) { 20.dp.toPx() } }
    val navScrollState = remember(navScrollHysteresisPx) {
        BottomNavScrollState(scrollHysteresisPx = navScrollHysteresisPx)
    }
    val showChrome = currentDest?.hasRoute<HomeRoute>() == true
    val activeTabIndex = dashboardPagerState.currentPage

    LaunchedEffect(activeTabIndex) {
        navScrollState.setActiveTab(activeTabIndex)
    }

    val navNestedScrollConnection = remember(navScrollState) {
        navScrollState.createNestedScrollConnection { dashboardPagerState.currentPage }
    }

    // Sync already-granted RECORD_AUDIO permission once at root mount without prompting proactively
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            voiceViewModel.onIntent(
                VoiceAssistantIntent.PermissionResult(granted = true, permanentlyDenied = false)
            )
        }
    }

    /** Clamps a requested tab, returns to the dashboard if needed, then updates pager and nav state. */
    fun selectDashboardTab(tabIndex: Int, animate: Boolean = true) {
        val clamped = tabIndex.coerceIn(0, FoodFlowTopLevelDestinations.lastIndex)
        if (!showChrome) {
            val popped = navController.popBackStack(HomeRoute, inclusive = false)
            if (!popped) {
                navController.navigate(HomeRoute) {
                    popUpTo<HomeRoute> { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
        navScrollState.expand(clamped)
        coroutineScope.launch {
            if (animate) {
                dashboardPagerState.animateScrollToPage(clamped)
            } else {
                dashboardPagerState.scrollToPage(clamped)
            }
        }
    }

    BackHandler(enabled = showChrome && activeTabIndex != TAB_HOME) {
        selectDashboardTab(TAB_HOME)
    }

    // Keep global voice assistant context synchronized with the active FoodFlow screen
    LaunchedEffect(backStackEntry, activeTabIndex) {
        val screenName = when {
            currentDest == null -> "Home"
            currentDest.hasRoute<HomeRoute>() -> when (activeTabIndex) {
                TAB_HOME -> "Home"
                TAB_SEARCH -> "Search"
                TAB_CART -> "Cart"
                TAB_ORDERS -> "Orders"
                TAB_PROFILE -> "Profile"
                else -> "Home"
            }
            currentDest.hasRoute<RestaurantDetailRoute>() -> "Restaurant Detail"
            currentDest.hasRoute<RestaurantListRoute>() -> "Restaurants"
            currentDest.hasRoute<CheckoutRoute>() -> "Checkout"
            currentDest.hasRoute<OrderTrackingRoute>() -> "Order Tracking"
            currentDest.hasRoute<ScheduledOrdersRoute>() -> "Scheduled Orders"
            currentDest.hasRoute<LocationRoute>() -> "Delivery Address"
            currentDest.hasRoute<FavoritesRoute>() -> "Favorites"
            currentDest.hasRoute<SupportRoute>() -> "Support"
            else -> "FoodFlow"
        }
        val activeRestaurantId = if (currentDest?.hasRoute<RestaurantDetailRoute>() == true) {
            runCatching { backStackEntry?.toRoute<RestaurantDetailRoute>()?.restaurantId }.getOrNull()
        } else null
        voiceViewModel.onIntent(
            VoiceAssistantIntent.ScreenContextChanged(
                screenName = screenName,
                restaurantId = activeRestaurantId
            )
        )
    }

    // Close any open voice session when returning to Login (e.g., after signing out)
    val isLoginDestination = currentDest?.hasRoute<LoginRoute>() == true
    LaunchedEffect(isLoginDestination) {
        if (isLoginDestination &&
            (voiceUiState.isSheetOpen ||
                voiceUiState.isListening ||
                voiceUiState.isSpeaking ||
                voiceUiState.connectionState == VoiceConnectionState.CONNECTED ||
                voiceUiState.connectionState == VoiceConnectionState.CONNECTING ||
                voiceUiState.connectionState == VoiceConnectionState.RECONNECTING)
        ) {
            voiceViewModel.onIntent(VoiceAssistantIntent.CloseAssistant)
        }
    }

    // Collect one-shot voice assistant UI effects (mic permission requests & real navigation)
    LaunchedEffect(voiceViewModel) {
        voiceViewModel.effects.collect { effect ->
            when (effect) {
                VoiceAssistantUiEffect.RequestMicrophonePermission -> {
                    val alreadyGranted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (alreadyGranted) {
                        voiceViewModel.onIntent(
                            VoiceAssistantIntent.PermissionResult(granted = true, permanentlyDenied = false)
                        )
                    } else {
                        micPermissionRequestSignal++
                    }
                }
                is VoiceAssistantUiEffect.Navigate -> {
                    when (val target = effect.target) {
                        VoiceNavigationTarget.Home -> selectDashboardTab(TAB_HOME)
                        VoiceNavigationTarget.Search -> selectDashboardTab(TAB_SEARCH)
                        VoiceNavigationTarget.Cart -> selectDashboardTab(TAB_CART)
                        VoiceNavigationTarget.Orders -> selectDashboardTab(TAB_ORDERS)
                        VoiceNavigationTarget.Profile -> selectDashboardTab(TAB_PROFILE)
                        VoiceNavigationTarget.Checkout -> navController.navigate(CheckoutRoute(null))
                        VoiceNavigationTarget.ScheduledOrders -> navController.navigate(ScheduledOrdersRoute)
                        is VoiceNavigationTarget.RestaurantDetail -> {
                            navController.navigate(RestaurantDetailRoute(target.restaurantId))
                        }
                        is VoiceNavigationTarget.OrderTracking -> {
                            navController.navigate(OrderTrackingRoute(target.orderId))
                        }
                    }
                }
                is VoiceAssistantUiEffect.ShowMessage -> {
                    // Tool action results are already appended to the live conversation feed.
                }
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val useNavRail = maxWidth >= 680.dp
        val isKeyboardOpen = WindowInsets.ime.getBottom(density) > 0
        LaunchedEffect(isKeyboardOpen) {
            navScrollState.onKeyboardVisibilityChanged(isKeyboardOpen)
        }
        var measuredBottomChromePx by remember { mutableIntStateOf(0) }
        var measuredCapsuleNavPx by remember { mutableIntStateOf(0) }

        val showFloatingCartOnTab = showChrome &&
            !isKeyboardOpen &&
            cartPreview.isVisible &&
            (activeTabIndex == TAB_HOME || activeTabIndex == TAB_SEARCH)

        val measuredChromeDp = with(density) { measuredBottomChromePx.toDp() }
        val measuredCapsuleNavDp = with(density) { measuredCapsuleNavPx.toDp() }

        val capsuleClearanceDp = when {
            isKeyboardOpen -> 16.dp
            showChrome && !useNavRail -> {
                val fallback = if (showFloatingCartOnTab) 184.dp else 112.dp
                if (measuredChromeDp > 0.dp) measuredChromeDp + 12.dp else fallback
            }
            showFloatingCartOnTab -> {
                if (measuredChromeDp > 0.dp) measuredChromeDp + 12.dp else 104.dp
            }
            else -> 32.dp
        }

        val cartBottomNavClearanceDp = when {
            useNavRail || isKeyboardOpen -> 0.dp
            measuredCapsuleNavDp > 0.dp -> measuredCapsuleNavDp + 8.dp
            else -> 108.dp
        }

        val standardTabBottomClearanceDp = when {
            useNavRail -> 32.dp
            isKeyboardOpen -> 16.dp
            measuredCapsuleNavDp > 0.dp -> measuredCapsuleNavDp + 14.dp
            else -> 114.dp
        }

        Row(modifier = Modifier.fillMaxSize()) {
            if (showChrome && useNavRail) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    FoodFlowTopLevelDestinations.forEachIndexed { index, dest ->
                        val selected = activeTabIndex == index
                        NavigationRailItem(
                            selected = selected,
                            onClick = { selectDashboardTab(index) },
                            label = {
                                Text(
                                    text = dest.label,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            icon = {
                                AnimatedNavIconWithBadge(
                                    selected = selected,
                                    selectedIcon = dest.selectedIcon,
                                    unselectedIcon = dest.unselectedIcon,
                                    label = dest.label,
                                    badgeCount = if (dest.route == CartRoute) cartCount else 0
                                )
                            },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.testTag(dest.testTag)
                        )
                    }
                }
            }

            // Outer Scaffold uses zero top insets so child screens own status-bar padding ONCE via FoodFlowTopBar.
            Scaffold(
                modifier = Modifier.weight(1f),
                contentWindowInsets = WindowInsets(0, 0, 0, 0)
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = SplashRoute,
                        enterTransition = {
                            fadeIn(tween(220)) + slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Start,
                                tween(220)
                            )
                        },
                        exitTransition = { fadeOut(tween(180)) },
                        popEnterTransition = { fadeIn(tween(220)) },
                        popExitTransition = {
                            fadeOut(tween(180)) + slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.End,
                                tween(220)
                            )
                        }
                    ) {
                        composable<SplashRoute> {
                            SplashScreen(
                                onNavigateToHome = {
                                    navController.navigate(HomeRoute) {
                                        popUpTo<SplashRoute> { inclusive = true }
                                    }
                                },
                                onNavigateToLogin = {
                                    navController.navigate(LoginRoute) {
                                        popUpTo<SplashRoute> { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable<LoginRoute> {
                            LoginScreen(
                                onLoginSuccess = {
                                    navController.navigate(HomeRoute) {
                                        popUpTo<LoginRoute> { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable<HomeRoute> {
                            HorizontalPager(
                                state = dashboardPagerState,
                                beyondViewportPageCount = 1,
                                key = { page -> FoodFlowTopLevelDestinations[page].testTag },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .nestedScroll(navNestedScrollConnection)
                                    .testTag("dashboard_horizontal_pager")
                            ) { page ->
                                when (page) {
                                    TAB_HOME -> HomeScreen(
                                        onOpenLocation = { navController.navigate(LocationRoute) },
                                        onOpenSearch = { selectDashboardTab(TAB_SEARCH) },
                                        onOpenRestaurantList = { category ->
                                            navController.navigate(RestaurantListRoute(category))
                                        },
                                        onOpenRestaurantDetail = { id ->
                                            navController.navigate(RestaurantDetailRoute(id))
                                        },
                                        onOpenTracking = { orderId ->
                                            navController.navigate(OrderTrackingRoute(orderId))
                                        },
                                        onOpenFavorites = { navController.navigate(FavoritesRoute) },
                                        onScrollAtTopChanged = { atTop ->
                                            navScrollState.onTabAtTopChanged(TAB_HOME, atTop)
                                        },
                                        bottomContentPadding = capsuleClearanceDp
                                    )

                                    TAB_SEARCH -> SearchScreen(
                                        onOpenRestaurant = { id ->
                                            navController.navigate(RestaurantDetailRoute(id))
                                        },
                                        onScrollAtTopChanged = { atTop ->
                                            navScrollState.onTabAtTopChanged(TAB_SEARCH, atTop)
                                        },
                                        bottomContentPadding = capsuleClearanceDp
                                    )

                                    TAB_CART -> CartScreen(
                                        onBrowseRestaurants = { selectDashboardTab(TAB_HOME) },
                                        onNavigateToCheckout = { couponCode ->
                                            navController.navigate(CheckoutRoute(couponCode))
                                        },
                                        onScrollAtTopChanged = { atTop ->
                                            navScrollState.onTabAtTopChanged(TAB_CART, atTop)
                                        },
                                        bottomBarBottomPadding = cartBottomNavClearanceDp
                                    )

                                    TAB_ORDERS -> OrdersScreen(
                                        onTrackOrder = { orderId ->
                                            navController.navigate(OrderTrackingRoute(orderId))
                                        },
                                        onOpenCart = { selectDashboardTab(TAB_CART) },
                                        onReportIssue = { orderId ->
                                            navController.navigate(SupportRoute(orderId))
                                        },
                                        onOpenScheduledOrders = {
                                            navController.navigate(ScheduledOrdersRoute)
                                        },
                                        onOpenRestaurant = { restaurantId ->
                                            navController.navigate(RestaurantDetailRoute(restaurantId))
                                        },
                                        onScrollAtTopChanged = { atTop ->
                                            navScrollState.onTabAtTopChanged(TAB_ORDERS, atTop)
                                        },
                                        bottomContentPadding = standardTabBottomClearanceDp
                                    )

                                    TAB_PROFILE -> ProfileScreen(
                                        onOpenAddresses = { navController.navigate(LocationRoute) },
                                        onOpenOrders = { selectDashboardTab(TAB_ORDERS) },
                                        onOpenScheduledOrders = { navController.navigate(ScheduledOrdersRoute) },
                                        onOpenFavorites = { navController.navigate(FavoritesRoute) },
                                        onOpenSupport = { navController.navigate(SupportRoute(null)) },
                                        onLoggedOut = {
                                            navController.navigate(LoginRoute) {
                                                popUpTo<HomeRoute> { inclusive = true }
                                                launchSingleTop = true
                                            }
                                        },
                                        onScrollAtTopChanged = { atTop ->
                                            navScrollState.onTabAtTopChanged(TAB_PROFILE, atTop)
                                        },
                                        bottomContentPadding = standardTabBottomClearanceDp
                                    )
                                }
                            }
                        }

                        composable<LocationRoute> {
                            LocationScreen(onBack = { navController.popBackStack() })
                        }

                        composable<RestaurantListRoute> { entry ->
                            val route = entry.toRoute<RestaurantListRoute>()
                            RestaurantListScreen(
                                initialCategory = route.category,
                                onBack = { navController.popBackStack() },
                                onOpenRestaurant = { id -> navController.navigate(RestaurantDetailRoute(id)) }
                            )
                        }

                        composable<RestaurantDetailRoute> { entry ->
                            val route = entry.toRoute<RestaurantDetailRoute>()
                            RestaurantDetailScreen(
                                restaurantId = route.restaurantId,
                                onBack = { navController.popBackStack() },
                                onOpenCart = { selectDashboardTab(TAB_CART, animate = false) }
                            )
                        }

                        composable<CheckoutRoute> { entry ->
                            val route = entry.toRoute<CheckoutRoute>()
                            CheckoutScreen(
                                couponCode = route.couponCode,
                                onBack = { navController.popBackStack() },
                                onChangeAddress = { navController.navigate(LocationRoute) },
                                onNavigateToPayment = { orderId, key ->
                                    navController.navigate(PaymentRoute(orderId, key))
                                },
                                onNavigateToScheduleConfirmation = { scheduleId ->
                                    navController.navigate(ScheduleConfirmationRoute(scheduleId)) {
                                        popUpTo<HomeRoute> { inclusive = false }
                                    }
                                }
                            )
                        }

                        composable<ScheduleConfirmationRoute> { entry ->
                            val route = entry.toRoute<ScheduleConfirmationRoute>()
                            ScheduleConfirmationScreen(
                                scheduleId = route.scheduleId,
                                onManageScheduledOrders = {
                                    navController.navigate(ScheduledOrdersRoute) {
                                        popUpTo<HomeRoute> { inclusive = false }
                                    }
                                },
                                onBackHome = { selectDashboardTab(TAB_HOME, animate = false) }
                            )
                        }

                        composable<ScheduledOrdersRoute> {
                            ScheduledOrdersScreen(
                                onBack = { navController.popBackStack() },
                                onTrackOrder = { orderId ->
                                    navController.navigate(OrderTrackingRoute(orderId))
                                },
                                onOpenRestaurant = { restaurantId ->
                                    navController.navigate(RestaurantDetailRoute(restaurantId))
                                }
                            )
                        }

                        composable<PaymentRoute> { entry ->
                            val route = entry.toRoute<PaymentRoute>()
                            PaymentScreen(
                                orderId = route.orderId,
                                idempotencyKey = route.idempotencyKey,
                                onBack = { navController.popBackStack() },
                                onPaymentConfirmed = { orderId ->
                                    navController.navigate(OrderConfirmationRoute(orderId)) {
                                        popUpTo<HomeRoute> { inclusive = false }
                                    }
                                }
                            )
                        }

                        composable<OrderConfirmationRoute> { entry ->
                            val route = entry.toRoute<OrderConfirmationRoute>()
                            OrderConfirmationScreen(
                                orderId = route.orderId,
                                onTrackOrder = { orderId ->
                                    navController.navigate(OrderTrackingRoute(orderId)) {
                                        popUpTo<HomeRoute> { inclusive = false }
                                    }
                                },
                                onBackHome = { selectDashboardTab(TAB_HOME, animate = false) }
                            )
                        }

                        composable<OrderTrackingRoute> { entry ->
                            val route = entry.toRoute<OrderTrackingRoute>()
                            OrderTrackingScreen(
                                orderId = route.orderId,
                                onBack = { navController.popBackStack() },
                                onOpenRestaurant = { restaurantId ->
                                    navController.navigate(RestaurantDetailRoute(restaurantId))
                                },
                                onBackHome = { selectDashboardTab(TAB_HOME, animate = false) }
                            )
                        }

                        composable<FavoritesRoute> {
                            FavoritesScreen(
                                onBack = { navController.popBackStack() },
                                onOpenRestaurant = { id -> navController.navigate(RestaurantDetailRoute(id)) }
                            )
                        }

                        composable<SupportRoute> { entry ->
                            val route = entry.toRoute<SupportRoute>()
                            SupportScreen(
                                orderId = route.orderId,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }

                    // Bottom Floating Chrome Stack: Cart Preview Bar cleanly above Rounded Floating Capsule Nav.
                    // Hidden automatically while the IME keyboard is open so input fields & Cart CTA never collide.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showChrome && !isKeyboardOpen,
                        enter = fadeIn(tween(160)) + slideInVertically(tween(180)) { it / 2 },
                        exit = fadeOut(tween(120)) + slideOutVertically(tween(140)) { it / 2 },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onSizeChanged { measuredBottomChromePx = it.height },
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (activeTabIndex == TAB_HOME || activeTabIndex == TAB_SEARCH) {
                                FloatingCartPreviewBar(
                                    itemCount = cartPreview.itemCount,
                                    totalAmount = cartPreview.totalPayable,
                                    restaurantName = cartPreview.restaurantName,
                                    onViewCart = { selectDashboardTab(TAB_CART) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(
                                            if (useNavRail) Modifier.navigationBarsPadding() else Modifier
                                        )
                                        .padding(
                                            start = 16.dp,
                                            end = 16.dp,
                                            bottom = if (useNavRail) 12.dp else 0.dp
                                        )
                                )
                            }

                            if (!useNavRail) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onSizeChanged { measuredCapsuleNavPx = it.height }
                                ) {
                                    FloatingCapsuleBottomNavBar(
                                        destinations = FoodFlowTopLevelDestinations,
                                        activeTabIndex = activeTabIndex,
                                        cartCount = cartCount,
                                        showLabels = navScrollState.shouldShowLabels(activeTabIndex),
                                        onSelectTab = { index -> selectDashboardTab(index) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val showVoiceWidget = currentDest != null &&
            !currentDest.hasRoute<SplashRoute>() &&
            !currentDest.hasRoute<LoginRoute>() &&
            !currentDest.hasRoute<PaymentRoute>() &&
            !isKeyboardOpen

        val voiceBottomOffset = when {
            showChrome && !useNavRail && showFloatingCartOnTab -> {
                if (measuredChromeDp > 0.dp) measuredChromeDp + 8.dp else 166.dp
            }
            showChrome && !useNavRail -> {
                if (measuredCapsuleNavDp > 0.dp) measuredCapsuleNavDp + 8.dp else 104.dp
            }
            currentDest?.hasRoute<CheckoutRoute>() == true -> 96.dp
            currentDest?.hasRoute<RestaurantDetailRoute>() == true -> 88.dp
            else -> 20.dp
        }

        // Single Global Voice Assistant Overlay (Draggable Floating Trigger + Unified Bottom Sheet)
        VoiceAssistantScreen(
            uiState = voiceUiState,
            showFloatingTrigger = showVoiceWidget,
            floatingBottomOffset = voiceBottomOffset,
            requestPermissionSignal = micPermissionRequestSignal,
            onIntent = voiceViewModel::onIntent
        )
    }
}

/** Displays the selected/unselected destination icon with its optional cart badge. */
@Composable
private fun AnimatedNavIconWithBadge(
    selected: Boolean,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    label: String,
    badgeCount: Int
) {
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.12f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "nav_icon_scale_$label"
    )
    val icon = if (selected) selectedIcon else unselectedIcon
    val iconModifier = Modifier.scale(iconScale)

    if (badgeCount > 0) {
        BadgedBox(
            badge = {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("cart_badge_count")
                ) {
                    Text(formatBadgeCount(badgeCount))
                }
            }
        ) {
            Icon(icon, contentDescription = label, modifier = iconModifier)
        }
    } else {
        Icon(icon, contentDescription = label, modifier = iconModifier)
    }
}
