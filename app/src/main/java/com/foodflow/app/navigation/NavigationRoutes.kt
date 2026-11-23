package com.foodflow.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

@Serializable data object SplashRoute
@Serializable data object LoginRoute
@Serializable data object HomeRoute
@Serializable data object SearchRoute
@Serializable data object CartRoute
@Serializable data object OrdersRoute
@Serializable data object ProfileRoute
@Serializable data object LocationRoute
@Serializable data class RestaurantListRoute(val category: String? = null)
@Serializable data class RestaurantDetailRoute(val restaurantId: String)
@Serializable data class CheckoutRoute(val couponCode: String? = null)
@Serializable data class PaymentRoute(val orderId: String, val idempotencyKey: String)
@Serializable data class OrderConfirmationRoute(val orderId: String)
@Serializable data class OrderTrackingRoute(val orderId: String)
@Serializable data class ScheduleConfirmationRoute(val scheduleId: String)
@Serializable data object ScheduledOrdersRoute
@Serializable data object FavoritesRoute
@Serializable data class SupportRoute(val orderId: String? = null)

data class TopLevelDestination(
    val route: Any,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

val FoodFlowTopLevelDestinations: List<TopLevelDestination> = listOf(
    TopLevelDestination(HomeRoute, "Home", Icons.Filled.Home, Icons.Outlined.Home, "nav_home"),
    TopLevelDestination(SearchRoute, "Search", Icons.Filled.Search, Icons.Outlined.Search, "nav_search"),
    TopLevelDestination(CartRoute, "Cart", Icons.Filled.ShoppingCart, Icons.Outlined.ShoppingCart, "nav_cart"),
    TopLevelDestination(OrdersRoute, "Orders", Icons.AutoMirrored.Filled.ReceiptLong, Icons.AutoMirrored.Outlined.ReceiptLong, "nav_orders"),
    TopLevelDestination(ProfileRoute, "Profile", Icons.Filled.Person, Icons.Outlined.Person, "nav_profile")
)
