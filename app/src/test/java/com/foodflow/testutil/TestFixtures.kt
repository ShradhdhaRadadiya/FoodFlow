package com.foodflow.testutil

import com.foodflow.core.common.DispatcherProvider
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.core.connectivity.ConnectivityStatus
import com.foodflow.domain.model.CartItem
import com.foodflow.domain.model.Coupon
import com.foodflow.domain.model.DiscountType
import com.foodflow.domain.model.FoodAddon
import com.foodflow.domain.model.FoodItem
import com.foodflow.domain.model.Restaurant
import com.foodflow.domain.model.SyncStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher

class TestDispatcherProvider(
    val dispatcher: TestDispatcher = StandardTestDispatcher()
) : DispatcherProvider {
    override val main: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
}

class FakeConnectivityObserver(initialOnline: Boolean = true) : ConnectivityObserver {
    private val _isOnline = MutableStateFlow(initialOnline)
    private val _isSimulatedOffline = MutableStateFlow(!initialOnline)

    override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()
    override val isSimulatedOffline: StateFlow<Boolean> = _isSimulatedOffline.asStateFlow()
    override val status: Flow<ConnectivityStatus> = _isOnline.map { online ->
        if (online) ConnectivityStatus.ONLINE else ConnectivityStatus.OFFLINE
    }

    override fun isCurrentlyOnline(): Boolean = _isOnline.value

    override fun setSimulatedOffline(offline: Boolean) {
        _isSimulatedOffline.value = offline
        _isOnline.value = !offline
    }
}

object TestFixtures {
    fun restaurant(
        id: String = "rest_1",
        name: String = "Spice Route Kitchen",
        isOpen: Boolean = true,
        deliveryFee: Double = 35.0,
        isPureVeg: Boolean = false
    ) = Restaurant(
        id = id,
        name = name,
        cuisines = listOf("North Indian", "Biryani"),
        rating = 4.6,
        ratingCount = 1200,
        deliveryTimeMin = 25,
        deliveryFee = deliveryFee,
        minOrderAmount = 149.0,
        offerText = "50% OFF up to ₹100",
        isPureVeg = isPureVeg,
        isOpen = isOpen,
        isPopular = true,
        isRecommended = true,
        imageKey = "indian",
        address = "SG Highway, Ahmedabad",
        distanceKm = 2.4,
        latitude = 23.0225,
        longitude = 72.5714
    )

    fun foodItem(
        id: String = "food_1",
        restaurantId: String = "rest_1",
        name: String = "Hyderabadi Dum Biryani",
        price: Double = 280.0,
        isVeg: Boolean = false,
        isAvailable: Boolean = true
    ) = FoodItem(
        id = id,
        restaurantId = restaurantId,
        name = name,
        description = "Aromatic basmati rice slow-cooked with saffron and spices.",
        categoryName = "Biryani",
        price = price,
        isVeg = isVeg,
        isAvailable = isAvailable,
        isRecommended = true,
        isBestseller = true,
        imageKey = "indian",
        addons = listOf(FoodAddon("addon_raita", "Burhani Raita", 40.0))
    )

    fun cartItem(
        itemId: String = "food_1",
        restaurantId: String = "rest_1",
        restaurantName: String = "Spice Route Kitchen",
        foodName: String = "Hyderabadi Dum Biryani",
        unitPrice: Double = 280.0,
        quantity: Int = 1,
        addons: List<FoodAddon> = emptyList(),
        syncStatus: SyncStatus = SyncStatus.SYNCED
    ) = CartItem(
        itemId = itemId,
        restaurantId = restaurantId,
        restaurantName = restaurantName,
        foodName = foodName,
        unitPrice = unitPrice,
        quantity = quantity,
        isVeg = true,
        imageKey = "indian",
        selectedAddons = addons,
        syncStatus = syncStatus
    )

    fun coupon(
        code: String = "FLOW50",
        type: DiscountType = DiscountType.PERCENTAGE,
        value: Double = 50.0,
        minOrder: Double = 199.0,
        maxDiscount: Double = 100.0,
        expiresAtMs: Long = System.currentTimeMillis() + 86_400_000L,
        isActive: Boolean = true
    ) = Coupon(
        code = code,
        title = "$code Offer",
        description = "Test discount coupon",
        discountType = type,
        discountValue = value,
        minOrderAmount = minOrder,
        maxDiscount = maxDiscount,
        expiresAtMs = expiresAtMs,
        isActive = isActive
    )
}
