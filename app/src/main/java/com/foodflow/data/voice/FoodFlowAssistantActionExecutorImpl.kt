package com.foodflow.data.voice

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.FoodItem
import com.foodflow.domain.model.Restaurant
import com.foodflow.domain.model.VoiceActionRequest
import com.foodflow.domain.model.VoiceActionResult
import com.foodflow.domain.model.VoiceCartSummaryItem
import com.foodflow.domain.model.VoiceCartSummarySnapshot
import com.foodflow.domain.model.VoiceConversationContext
import com.foodflow.domain.model.VoiceNavigationTarget
import com.foodflow.domain.model.VoicePendingConfirmation
import com.foodflow.domain.model.VoiceRecommendationItem
import com.foodflow.domain.model.VoiceResponseType
import com.foodflow.domain.model.VoiceStructuredResponse
import com.foodflow.domain.usecase.CalculateCartTotalsUseCase
import com.foodflow.domain.usecase.cart.AddToCartUseCase
import com.foodflow.domain.usecase.cart.ObserveCartItemsUseCase
import com.foodflow.domain.usecase.cart.RemoveCartItemUseCase
import com.foodflow.domain.usecase.cart.UpdateCartQuantityUseCase
import com.foodflow.domain.usecase.order.ObserveOrdersUseCase
import com.foodflow.domain.usecase.restaurant.ObserveMenuUseCase
import com.foodflow.domain.usecase.restaurant.ObserveRestaurantsUseCase
import com.foodflow.domain.usecase.restaurant.SearchFoodItemsUseCase
import com.foodflow.domain.usecase.restaurant.SearchRestaurantsUseCase
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Controlled, validated FoodFlow action executor.
 * Gemini never mutates application state directly: every tool call is validated against live
 * FoodFlow Room data and delegated to the existing domain use cases.
 */
@Singleton
class FoodFlowAssistantActionExecutorImpl @Inject constructor(
    private val observeRestaurants: ObserveRestaurantsUseCase,
    private val searchRestaurants: SearchRestaurantsUseCase,
    private val searchFoodItems: SearchFoodItemsUseCase,
    private val observeMenu: ObserveMenuUseCase,
    private val observeCartItems: ObserveCartItemsUseCase,
    private val addToCart: AddToCartUseCase,
    private val updateCartQuantity: UpdateCartQuantityUseCase,
    private val removeCartItem: RemoveCartItemUseCase,
    private val observeOrders: ObserveOrdersUseCase,
    private val calculateCartTotals: CalculateCartTotalsUseCase
) : VoiceAssistantActionDataSource {

    /** Validates an assistant request and dispatches it to the matching FoodFlow use case. */
    override suspend fun execute(
        action: VoiceActionRequest,
        context: VoiceConversationContext
    ): VoiceActionResult = when (action) {
        is VoiceActionRequest.SearchRestaurants -> handleSearchRestaurants(action)
        is VoiceActionRequest.SearchFoodItems -> handleSearchFoodItems(action)
        is VoiceActionRequest.GetRestaurantDetails -> handleGetRestaurantDetails(action, context)
        VoiceActionRequest.GetCartSummary -> handleGetCartSummary()
        is VoiceActionRequest.AddItemToCart -> handleAddItemToCart(action, context)
        is VoiceActionRequest.RemoveCartItem -> handleRemoveCartItem(action)
        is VoiceActionRequest.UpdateCartQuantity -> handleUpdateCartQuantity(action)
        is VoiceActionRequest.NavigateToScreen -> handleNavigateToScreen(action, context)
        is VoiceActionRequest.GetOrderStatus -> handleGetOrderStatus(action)
    }

    /** Searches restaurants and maps matching results into assistant-visible cards. */
    private suspend fun handleSearchRestaurants(
        request: VoiceActionRequest.SearchRestaurants
    ): VoiceActionResult {
        val variants = buildSearchVariants(request.query)
        val base = if (variants.isEmpty()) {
            observeRestaurants().first()
        } else {
            var matched: List<Restaurant> = emptyList()
            for (variant in variants) {
                matched = searchRestaurants(variant).first()
                if (matched.isNotEmpty()) break
            }
            if (matched.isNotEmpty()) matched else observeRestaurants().first().filter { rest ->
                variants.any { q ->
                    rest.name.contains(q, ignoreCase = true) ||
                        rest.cuisines.any { it.contains(q, ignoreCase = true) }
                }
            }
        }
        val filtered = base
            .distinctBy { it.id }
            .filter { !request.vegOnly || it.isPureVeg }
            .filter { request.minRating == null || it.rating >= request.minRating }
            .sortedByDescending { it.rating }
            .take(5)

        if (filtered.isEmpty()) {
            return VoiceActionResult(
                success = false,
                summaryForVoice = "I couldn't find any matching restaurants on FoodFlow right now.",
                structuredResponse = VoiceStructuredResponse(
                    type = VoiceResponseType.CONVERSATIONAL_TEXT,
                    bodyText = "No matching restaurants found right now."
                )
            )
        }

        val summary = buildString {
            append("Here are ${filtered.size} restaurants on FoodFlow: ")
            append(
                filtered.mapIndexed { idx, r ->
                    "${idx + 1}. ${r.name} (${r.cuisines.take(2).joinToString(", ")}, rated ${"%.1f".format(r.rating)}, ${r.deliveryTimeMin} mins)"
                }.joinToString("; ")
            )
        }
        val headline = if (request.vegOnly) {
            "Pure veg restaurants (${filtered.size})"
        } else if (request.query.isNotBlank()) {
            "Restaurants for \"${request.query.trim()}\""
        } else {
            "Top rated restaurants on FoodFlow"
        }
        return VoiceActionResult(
            success = true,
            summaryForVoice = summary,
            structuredPayload = mapOf(
                "count" to filtered.size.toString(),
                "restaurants" to filtered.joinToString(" | ") { "${it.id}:${it.name}:${it.rating}" }
            ),
            structuredResponse = VoiceStructuredResponse(
                type = VoiceResponseType.RESTAURANT_RECOMMENDATIONS,
                headline = headline,
                spokenResponse = summary,
                restaurants = filtered
            ),
            selectedRestaurantId = filtered.first().id,
            selectedRestaurantName = filtered.first().name
        )
    }

    /** Searches menu items and returns matching food recommendations with restaurant context. */
    private suspend fun handleSearchFoodItems(
        request: VoiceActionRequest.SearchFoodItems
    ): VoiceActionResult {
        val restaurants = observeRestaurants().first()
        val restaurantMap = restaurants.associateBy { it.id }

        val variants = buildSearchVariants(request.query)
        val allItems: List<FoodItem> = if (variants.isNotEmpty()) {
            var searched: List<FoodItem> = emptyList()
            for (variant in variants) {
                searched = searchFoodItems(variant).first()
                if (searched.isNotEmpty()) break
            }
            if (searched.isNotEmpty()) searched else collectAllMenuItems(restaurants)
        } else {
            collectAllMenuItems(restaurants)
        }

        val meal = request.mealType?.trim()?.lowercase().orEmpty()

        val filtered = allItems
            .asSequence()
            .filter { it.isAvailable }
            .filter { restaurantMap[it.restaurantId]?.isOpen != false }
            .filter { !request.vegOnly || it.isVeg }
            .filter { request.maxPrice == null || it.price <= request.maxPrice }
            .filter {
                !request.spicyOnly ||
                    it.spiceLevel.contains("spicy", ignoreCase = true) ||
                    it.spiceLevel.contains("hot", ignoreCase = true) ||
                    it.name.contains("spicy", ignoreCase = true) ||
                    it.name.contains("peri", ignoreCase = true) ||
                    it.name.contains("masala", ignoreCase = true) ||
                    it.name.contains("tikka", ignoreCase = true) ||
                    it.description.contains("spicy", ignoreCase = true) ||
                    it.description.contains("chilli", ignoreCase = true)
            }
            .filter { item ->
                if (meal.isBlank()) true else {
                    when {
                        meal.contains("breakfast") || meal.contains("healthy") ->
                            item.categoryName.contains("breakfast", ignoreCase = true) ||
                                item.categoryName.contains("healthy", ignoreCase = true) ||
                                item.categoryName.contains("salad", ignoreCase = true) ||
                                item.categoryName.contains("bowl", ignoreCase = true) ||
                                item.name.contains("dosa", ignoreCase = true) ||
                                item.name.contains("idli", ignoreCase = true) ||
                                item.name.contains("poha", ignoreCase = true) ||
                                item.name.contains("sandwich", ignoreCase = true) ||
                                item.name.contains("bowl", ignoreCase = true) ||
                                item.name.contains("salad", ignoreCase = true) ||
                                item.name.contains("oats", ignoreCase = true) ||
                                item.price <= 220.0
                        meal.contains("special") || meal.contains("bestseller") ->
                            item.isBestseller || item.isRecommended || item.rating >= 4.5
                        else -> true
                    }
                }
            }
            .filter { item ->
                if (variants.isEmpty()) true else {
                    val restName = restaurantMap[item.restaurantId]?.name.orEmpty().lowercase()
                    val cuisines = restaurantMap[item.restaurantId]?.cuisines?.joinToString(" ").orEmpty().lowercase()
                    val itemName = item.name.lowercase()
                    val itemDesc = item.description.lowercase()
                    val itemCat = item.categoryName.lowercase()
                    variants.any { q ->
                        itemName.contains(q) ||
                            itemDesc.contains(q) ||
                            itemCat.contains(q) ||
                            restName.contains(q) ||
                            cuisines.contains(q)
                    }
                }
            }
            .sortedWith(
                compareByDescending<FoodItem> { it.isBestseller }
                    .thenByDescending { it.rating }
                    .thenBy { it.price }
            )
            .take(5)
            .toList()

        if (filtered.isEmpty()) {
            val budgetNote = request.maxPrice?.let { " under ₹${it.toInt()}" }.orEmpty()
            val vegNote = if (request.vegOnly) " vegetarian" else ""
            return VoiceActionResult(
                success = false,
                summaryForVoice = "I didn't find any available$vegNote items$budgetNote matching your request in FoodFlow's current menu."
            )
        }

        val dedupedFiltered = filtered.distinctBy { it.id }
        val recommendations = dedupedFiltered.mapIndexed { idx, item ->
            val rest = restaurantMap[item.restaurantId]
            VoiceRecommendationItem(
                index = idx + 1,
                foodId = item.id,
                foodName = item.name,
                restaurantId = item.restaurantId,
                restaurantName = rest?.name ?: "FoodFlow Kitchen",
                price = item.price,
                isVeg = item.isVeg,
                spiceLevel = item.spiceLevel,
                rating = item.rating,
                isAvailable = item.isAvailable
            )
        }

        val comboPrefix = if ((request.forPeopleCount ?: 0) >= 2) {
            "For ${request.forPeopleCount} people, here are great picks you can pair together: "
        } else {
            "I found ${recommendations.size} options on FoodFlow: "
        }
        val summary = comboPrefix + recommendations.joinToString("; ") { item ->
            "${item.index}. ${item.foodName} from ${item.restaurantName} for ₹${item.price.toInt()}"
        } + ". Say 'add the first one' or 'add the second item to my cart' to order."

        val headline = buildString {
            when {
                request.vegOnly && request.maxPrice != null -> append("Veg picks under ₹${request.maxPrice.toInt()}")
                request.maxPrice != null -> append("Dishes under ₹${request.maxPrice.toInt()}")
                request.vegOnly -> append("Vegetarian dishes")
                request.spicyOnly -> append("Spicy picks")
                request.query.isNotBlank() -> append("Dishes matching \"${request.query.trim()}\"")
                (request.forPeopleCount ?: 0) >= 2 -> append("Meal ideas for ${request.forPeopleCount}")
                else -> append("Recommended dishes")
            }
        }

        val foodRestNames = dedupedFiltered.associate { item ->
            item.restaurantId to (restaurantMap[item.restaurantId]?.name ?: "FoodFlow Kitchen")
        }

        return VoiceActionResult(
            success = true,
            summaryForVoice = summary,
            structuredPayload = mapOf(
                "count" to recommendations.size.toString(),
                "items" to recommendations.joinToString(" | ") {
                    "#${it.index}:${it.foodId}:${it.foodName}:₹${it.price.toInt()}:${it.restaurantName}"
                }
            ),
            recommendations = recommendations,
            structuredResponse = VoiceStructuredResponse(
                type = VoiceResponseType.FOOD_RECOMMENDATIONS,
                headline = headline,
                spokenResponse = summary,
                foodItems = dedupedFiltered,
                foodRestaurantNames = foodRestNames
            )
        )
    }

    /** Loads restaurant details and available menu highlights for the assistant response. */
    private suspend fun handleGetRestaurantDetails(
        request: VoiceActionRequest.GetRestaurantDetails,
        context: VoiceConversationContext
    ): VoiceActionResult {
        val restaurants = observeRestaurants().first()
        val restaurant = resolveRestaurant(restaurants, request.restaurantId, request.restaurantName, context)
            ?: return VoiceActionResult(
                success = false,
                summaryForVoice = "I couldn't find that restaurant on FoodFlow."
            )

        val menu = observeMenu(restaurant.id).first().filter { it.isAvailable }
        val topItems = menu.sortedByDescending { it.rating }.take(4)
        val recommendations = topItems.mapIndexed { idx, item ->
            VoiceRecommendationItem(
                index = idx + 1,
                foodId = item.id,
                foodName = item.name,
                restaurantId = restaurant.id,
                restaurantName = restaurant.name,
                price = item.price,
                isVeg = item.isVeg,
                spiceLevel = item.spiceLevel,
                rating = item.rating,
                isAvailable = item.isAvailable
            )
        }

        val summary = buildString {
            append("${restaurant.name} serves ${restaurant.cuisines.joinToString(", ")}, rated ${"%.1f".format(restaurant.rating)} with ${restaurant.ratingCount} reviews, delivering in around ${restaurant.deliveryTimeMin} minutes. ")
            if (recommendations.isNotEmpty()) {
                append("Popular dishes: ")
                append(recommendations.joinToString(", ") { "${it.index}. ${it.foodName} (₹${it.price.toInt()})" })
                append(".")
            }
        }
        return VoiceActionResult(
            success = true,
            summaryForVoice = summary,
            recommendations = recommendations,
            structuredResponse = if (topItems.isNotEmpty()) {
                VoiceStructuredResponse(
                    type = VoiceResponseType.FOOD_RECOMMENDATIONS,
                    headline = "Popular at ${restaurant.name}",
                    spokenResponse = summary,
                    foodItems = topItems,
                    foodRestaurantNames = topItems.associate { it.id to restaurant.name }
                )
            } else {
                VoiceStructuredResponse(
                    type = VoiceResponseType.RESTAURANT_RECOMMENDATIONS,
                    headline = restaurant.name,
                    spokenResponse = summary,
                    restaurants = listOf(restaurant)
                )
            },
            selectedRestaurantId = restaurant.id,
            selectedRestaurantName = restaurant.name,
            structuredPayload = mapOf(
                "restaurantId" to restaurant.id,
                "restaurantName" to restaurant.name,
                "rating" to restaurant.rating.toString(),
                "deliveryTimeMin" to restaurant.deliveryTimeMin.toString()
            )
        )
    }

    /** Reads the local cart and returns a structured summary for the conversation. */
    private suspend fun handleGetCartSummary(): VoiceActionResult {
        val items = observeCartItems().first()
        if (items.isEmpty()) {
            return VoiceActionResult(
                success = true,
                summaryForVoice = "Your FoodFlow cart is currently empty. Ask me for vegetarian dishes, pizza, or something under 200 rupees to add items.",
                structuredPayload = mapOf("itemCount" to "0", "totalPayable" to "0")
            )
        }
        val restaurants = observeRestaurants().first()
        val rest = restaurants.firstOrNull { it.id == items.first().restaurantId }
        val totals = calculateCartTotals(
            items = items,
            baseDeliveryFee = rest?.deliveryFee ?: CalculateCartTotalsUseCase.DEFAULT_DELIVERY_FEE,
            coupon = null
        )
        val itemCount = items.sumOf { it.quantity }
        val itemsList = items.joinToString(", ") { "${it.quantity}x ${it.foodName} (₹${it.lineTotal.toInt()})" }
        val summary = "You have $itemCount item${if (itemCount == 1) "" else "s"} in your cart from ${items.first().restaurantName}: $itemsList. Subtotal is ₹${totals.subtotal.toInt()} and total payable is ₹${totals.totalPayable.toInt()}."
        return VoiceActionResult(
            success = true,
            summaryForVoice = summary,
            structuredResponse = VoiceStructuredResponse(
                type = VoiceResponseType.CART_SUMMARY,
                headline = "Your Cart • ${items.first().restaurantName}",
                spokenResponse = summary,
                cartSummary = VoiceCartSummarySnapshot(
                    restaurantId = items.first().restaurantId,
                    restaurantName = items.first().restaurantName,
                    itemCount = itemCount,
                    subtotal = totals.subtotal,
                    totalPayable = totals.totalPayable,
                    items = items.map { ci ->
                        VoiceCartSummaryItem(
                            itemId = ci.itemId,
                            foodName = ci.foodName,
                            quantity = ci.quantity,
                            lineTotal = ci.lineTotal,
                            isVeg = ci.isVeg
                        )
                    }
                )
            ),
            structuredPayload = mapOf(
                "itemCount" to itemCount.toString(),
                "restaurantName" to items.first().restaurantName,
                "subtotal" to totals.subtotal.toInt().toString(),
                "totalPayable" to totals.totalPayable.toInt().toString()
            )
        )
    }

    /** Resolves and validates a requested item before adding it through the cart use case. */
    private suspend fun handleAddItemToCart(
        request: VoiceActionRequest.AddItemToCart,
        context: VoiceConversationContext
    ): VoiceActionResult {
        val restaurants = observeRestaurants().first()
        val resolvedRec = request.recommendationIndex?.let { ordinal ->
            context.recentRecommendations.firstOrNull { it.index == ordinal }
                ?: context.recentRecommendations.getOrNull(ordinal - 1)
        }

        val targetFoodId = request.foodId ?: resolvedRec?.foodId
        val targetFoodName = request.foodName ?: resolvedRec?.foodName

        val allItems = collectAllMenuItems(restaurants)
        val foodItem = when {
            !targetFoodId.isNullOrBlank() -> allItems.firstOrNull { it.id == targetFoodId }
            !targetFoodName.isNullOrBlank() -> {
                allItems.firstOrNull { it.name.equals(targetFoodName, ignoreCase = true) }
                    ?: allItems.firstOrNull { it.name.contains(targetFoodName, ignoreCase = true) }
            }
            else -> null
        } ?: return VoiceActionResult(
            success = false,
            summaryForVoice = "I couldn't find that exact dish on the menu. Try asking me to search for it first."
        )

        if (!foodItem.isAvailable) {
            return VoiceActionResult(
                success = false,
                summaryForVoice = "Sorry, ${foodItem.name} is currently out of stock."
            )
        }

        val restaurant = restaurants.firstOrNull { it.id == foodItem.restaurantId }
            ?: return VoiceActionResult(
                success = false,
                summaryForVoice = "Sorry, the restaurant for ${foodItem.name} is currently unavailable."
            )

        if (!restaurant.isOpen) {
            return VoiceActionResult(
                success = false,
                summaryForVoice = "Sorry, ${restaurant.name} is currently closed."
            )
        }

        val existingCart = observeCartItems().first()
        if (existingCart.isNotEmpty() && existingCart.first().restaurantId != restaurant.id && !request.replaceExistingCart) {
            val existingRestaurant = existingCart.first().restaurantName
            val confirmation = VoicePendingConfirmation(
                confirmationId = "conf_${UUID.randomUUID().toString().take(8)}",
                title = "Replace current cart?",
                message = "Your cart has items from $existingRestaurant. Replace it to add ${foodItem.name} from ${restaurant.name}?",
                action = request.copy(
                    foodId = foodItem.id,
                    foodName = foodItem.name,
                    replaceExistingCart = true
                )
            )
            return VoiceActionResult(
                success = false,
                summaryForVoice = "Your cart currently has items from $existingRestaurant. Please confirm if you want me to clear it and add ${foodItem.name} from ${restaurant.name}.",
                requiresConfirmation = confirmation
            )
        }

        val qty = request.quantity.coerceIn(1, 10)
        var lastResult: AppResult<Unit> = AppResult.Success(Unit)
        repeat(qty) { idx ->
            lastResult = addToCart(
                restaurant = restaurant,
                foodItem = foodItem,
                clearExistingDifferentRestaurant = request.replaceExistingCart && idx == 0
            )
            if (lastResult is AppResult.Error) return@repeat
        }

        return when (lastResult) {
            is AppResult.Success -> VoiceActionResult(
                success = true,
                summaryForVoice = "Added $qty ${foodItem.name} from ${restaurant.name} (₹${(foodItem.price * qty).toInt()}) to your cart.",
                structuredPayload = mapOf(
                    "foodId" to foodItem.id,
                    "foodName" to foodItem.name,
                    "restaurantId" to restaurant.id,
                    "quantityAdded" to qty.toString()
                ),
                selectedRestaurantId = restaurant.id,
                selectedRestaurantName = restaurant.name
            )
            is AppResult.Error -> VoiceActionResult(
                success = false,
                summaryForVoice = "I couldn't add ${foodItem.name} to your cart: ${(lastResult as AppResult.Error).error.userMessage}"
            )
        }
    }

    /** Removes the requested cart item through the existing cart use case. */
    private suspend fun handleRemoveCartItem(
        request: VoiceActionRequest.RemoveCartItem
    ): VoiceActionResult {
        val cartItems = observeCartItems().first()
        val match = when {
            !request.itemId.isNullOrBlank() -> cartItems.firstOrNull { it.itemId == request.itemId }
            !request.foodName.isNullOrBlank() -> cartItems.firstOrNull {
                it.foodName.contains(request.foodName, ignoreCase = true)
            }
            else -> null
        } ?: return VoiceActionResult(
            success = false,
            summaryForVoice = "I couldn't find that item in your cart."
        )

        return when (val res = removeCartItem(match.itemId)) {
            is AppResult.Success -> VoiceActionResult(
                success = true,
                summaryForVoice = "Removed ${match.foodName} from your cart."
            )
            is AppResult.Error -> VoiceActionResult(
                success = false,
                summaryForVoice = "Unable to remove ${match.foodName}: ${res.error.userMessage}"
            )
        }
    }

    /** Changes a cart item's quantity through the existing cart use case. */
    private suspend fun handleUpdateCartQuantity(
        request: VoiceActionRequest.UpdateCartQuantity
    ): VoiceActionResult {
        val cartItems = observeCartItems().first()
        val match = when {
            !request.itemId.isNullOrBlank() -> cartItems.firstOrNull { it.itemId == request.itemId }
            !request.foodName.isNullOrBlank() -> cartItems.firstOrNull {
                it.foodName.contains(request.foodName, ignoreCase = true)
            }
            else -> null
        } ?: return VoiceActionResult(
            success = false,
            summaryForVoice = "I couldn't find that item in your cart to update its quantity."
        )

        val safeQty = request.quantity.coerceIn(0, 15)
        return when (val res = updateCartQuantity(match.itemId, safeQty)) {
            is AppResult.Success -> VoiceActionResult(
                success = true,
                summaryForVoice = if (safeQty == 0) {
                    "Removed ${match.foodName} from your cart."
                } else {
                    "Updated ${match.foodName} quantity to $safeQty."
                }
            )
            is AppResult.Error -> VoiceActionResult(
                success = false,
                summaryForVoice = "Unable to update ${match.foodName}: ${res.error.userMessage}"
            )
        }
    }

    /** Emits a typed navigation target for the UI to handle outside the data layer. */
    private suspend fun handleNavigateToScreen(
        request: VoiceActionRequest.NavigateToScreen,
        context: VoiceConversationContext
    ): VoiceActionResult {
        val dest = request.destination.trim().lowercase()
        val target: VoiceNavigationTarget? = when {
            dest.contains("cart") -> VoiceNavigationTarget.Cart
            dest.contains("home") || dest.contains("dashboard") -> VoiceNavigationTarget.Home
            dest.contains("search") -> VoiceNavigationTarget.Search
            dest.contains("scheduled") -> VoiceNavigationTarget.ScheduledOrders
            dest.contains("orders") -> VoiceNavigationTarget.Orders
            dest.contains("profile") -> VoiceNavigationTarget.Profile
            dest.contains("checkout") -> VoiceNavigationTarget.Checkout
            dest.contains("track") -> {
                val orderId = request.orderId ?: observeOrders().first().firstOrNull { it.orderStatus.isActive }?.id
                orderId?.let { VoiceNavigationTarget.OrderTracking(it) }
            }
            dest.contains("restaurant") -> {
                val restaurants = observeRestaurants().first()
                val rest = resolveRestaurant(restaurants, request.restaurantId, request.restaurantName, context)
                rest?.let { VoiceNavigationTarget.RestaurantDetail(it.id) }
            }
            else -> null
        }

        if (target == null) {
            return VoiceActionResult(
                success = false,
                summaryForVoice = "I couldn't find that destination screen in FoodFlow."
            )
        }

        val label = when (target) {
            VoiceNavigationTarget.Home -> "Home"
            VoiceNavigationTarget.Search -> "Search"
            VoiceNavigationTarget.Cart -> "your Cart"
            VoiceNavigationTarget.Orders -> "your Orders"
            VoiceNavigationTarget.Profile -> "Profile"
            VoiceNavigationTarget.Checkout -> "Checkout"
            VoiceNavigationTarget.ScheduledOrders -> "Scheduled Orders"
            is VoiceNavigationTarget.RestaurantDetail -> "the restaurant page"
            is VoiceNavigationTarget.OrderTracking -> "live order tracking"
        }
        return VoiceActionResult(
            success = true,
            summaryForVoice = "Opening $label now. I'll stay right here if you need anything else.",
            structuredResponse = VoiceStructuredResponse(
                type = VoiceResponseType.NAVIGATION_CONFIRMATION,
                headline = "Opened $label",
                bodyText = "Navigated to $label. FoodFlow Voice stays ready here.",
                navigationTarget = target
            ),
            navigationTarget = target
        )
    }

    /** Looks up an order and summarizes its current tracking status for the assistant. */
    private suspend fun handleGetOrderStatus(
        request: VoiceActionRequest.GetOrderStatus
    ): VoiceActionResult {
        val orders = observeOrders().first()
        val order = if (!request.orderId.isNullOrBlank()) {
            orders.firstOrNull { it.id == request.orderId }
        } else {
            orders.firstOrNull { it.orderStatus.isActive } ?: orders.firstOrNull()
        } ?: return VoiceActionResult(
            success = true,
            summaryForVoice = "You don't have any orders on FoodFlow yet."
        )

        val etaPart = if (order.orderStatus.isActive) {
            " Estimated arrival is in about ${order.estimatedDeliveryMinutes} minutes."
        } else {
            ""
        }
        return VoiceActionResult(
            success = true,
            summaryForVoice = "Your order from ${order.restaurantName} is currently ${order.orderStatus.label}.$etaPart",
            structuredPayload = mapOf(
                "orderId" to order.id,
                "restaurantName" to order.restaurantName,
                "status" to order.orderStatus.label,
                "etaMinutes" to order.estimatedDeliveryMinutes.toString()
            )
        )
    }

    /** Collects menu entries for candidate restaurants when a cross-restaurant search needs them. */
    private suspend fun collectAllMenuItems(restaurants: List<Restaurant>): List<FoodItem> {
        val result = mutableListOf<FoodItem>()
        for (restaurant in restaurants) {
            result += observeMenu(restaurant.id).first()
        }
        return result
    }

    /** Chooses the restaurant referenced by an action using IDs or normalized names. */
    private fun resolveRestaurant(
        restaurants: List<Restaurant>,
        restaurantId: String?,
        restaurantName: String?,
        context: VoiceConversationContext
    ): Restaurant? {
        if (!restaurantId.isNullOrBlank()) {
            restaurants.firstOrNull { it.id == restaurantId }?.let { return it }
        }
        if (!restaurantName.isNullOrBlank()) {
            restaurants.firstOrNull { it.name.equals(restaurantName, ignoreCase = true) }?.let { return it }
            restaurants.firstOrNull { it.name.contains(restaurantName, ignoreCase = true) }?.let { return it }
        }
        context.selectedRestaurantId?.let { id ->
            restaurants.firstOrNull { it.id == id }?.let { return it }
        }
        context.recentRecommendations.firstOrNull()?.let { rec ->
            restaurants.firstOrNull { it.id == rec.restaurantId }?.let { return it }
        }
        return null
    }

    /** Produces normalized query variants used to match food names and common synonyms. */
    private fun buildSearchVariants(rawQuery: String): List<String> {
        val cleaned = rawQuery.trim().lowercase()
        if (cleaned.isEmpty()) return emptyList()
        val variants = linkedSetOf(cleaned)
        if (cleaned.length > 4 && cleaned.endsWith("es")) {
            variants.add(cleaned.dropLast(2))
        }
        if (cleaned.length > 3 && cleaned.endsWith("s") && !cleaned.endsWith("ss")) {
            variants.add(cleaned.dropLast(1))
        }
        return variants.toList()
    }
}
