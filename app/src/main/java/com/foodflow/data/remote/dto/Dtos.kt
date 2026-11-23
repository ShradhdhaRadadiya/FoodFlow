package com.foodflow.data.remote.dto

import com.foodflow.domain.model.FoodAddon
import com.foodflow.domain.model.OrderItem
import kotlinx.serialization.Serializable

@Serializable
data class SeedDatasetDto(
    val categories: List<CategoryDto>,
    val restaurants: List<RestaurantDto>,
    val foodItems: List<FoodItemDto>,
    val coupons: List<CouponDto>,
    val addresses: List<AddressDto>,
    val deliveryPartners: List<DeliveryPartnerDto>,
    val sampleOrders: List<OrderDto>
)

@Serializable
data class CategoryDto(
    val id: String,
    val name: String,
    val subtitle: String,
    val imageKey: String,
    val accentHex: Long
)

@Serializable
data class RestaurantDto(
    val id: String,
    val name: String,
    val cuisines: List<String>,
    val rating: Double,
    val ratingCount: Int,
    val deliveryTimeMin: Int,
    val deliveryFee: Double,
    val minOrderAmount: Double,
    val offerText: String? = null,
    val isPureVeg: Boolean,
    val isOpen: Boolean = true,
    val isPopular: Boolean = false,
    val isRecommended: Boolean = false,
    val imageKey: String,
    val address: String,
    val distanceKm: Double,
    val latitude: Double,
    val longitude: Double
)

@Serializable
data class FoodItemDto(
    val id: String,
    val restaurantId: String,
    val name: String,
    val description: String,
    val categoryName: String,
    val price: Double,
    val originalPrice: Double? = null,
    val isVeg: Boolean,
    val isAvailable: Boolean = true,
    val isRecommended: Boolean = false,
    val isBestseller: Boolean = false,
    val rating: Double = 4.4,
    val imageKey: String,
    val addons: List<FoodAddon> = emptyList(),
    val spiceLevel: String = "Medium"
)

@Serializable
data class CouponDto(
    val code: String,
    val title: String,
    val description: String,
    val discountType: String,
    val discountValue: Double,
    val minOrderAmount: Double,
    val maxDiscount: Double,
    val expiresInDays: Int,
    val isActive: Boolean = true
)

@Serializable
data class AddressDto(
    val id: String,
    val label: String,
    val recipientName: String,
    val line1: String,
    val landmark: String,
    val city: String,
    val pincode: String,
    val deliveryInstructions: String,
    val latitude: Double,
    val longitude: Double,
    val isDefault: Boolean = false,
    val isServiceable: Boolean = true
)

@Serializable
data class DeliveryPartnerDto(
    val id: String,
    val name: String,
    val phone: String,
    val rating: Double,
    val vehicleNumber: String,
    val totalDeliveries: Int
)

@Serializable
data class OrderDto(
    val id: String,
    val userId: String,
    val idempotencyKey: String,
    val restaurantId: String,
    val restaurantName: String,
    val restaurantAddress: String,
    val restaurantLat: Double,
    val restaurantLng: Double,
    val deliveryAddress: String,
    val deliveryLat: Double,
    val deliveryLng: Double,
    val deliveryInstructions: String,
    val items: List<OrderItem>,
    val subtotal: Double,
    val deliveryFee: Double,
    val packagingCharge: Double,
    val taxes: Double,
    val discount: Double,
    val totalPayable: Double,
    val couponCode: String? = null,
    val orderStatus: String,
    val paymentStatus: String,
    val paymentMethod: String,
    val estimatedDeliveryMinutes: Int,
    val deliveryPartnerId: String? = null,
    val createdHoursAgo: Int = 24
)

@Serializable
data class CartOperationRequestDto(
    val operationId: String,
    val userId: String,
    val operationType: String,
    val itemId: String,
    val restaurantId: String,
    val quantity: Int,
    val expectedUnitPrice: Double,
    val instructions: String = ""
)

@Serializable
data class CartSyncAckDto(
    val operationId: String,
    val accepted: Boolean,
    val duplicateIgnored: Boolean = false,
    val authoritativePrice: Double? = null,
    val itemAvailable: Boolean = true,
    val restaurantOpen: Boolean = true,
    val serverMessage: String? = null
)

@Serializable
data class CreateOrderRequestDto(
    val idempotencyKey: String,
    val userId: String,
    val restaurantId: String,
    val restaurantName: String,
    val restaurantAddress: String,
    val restaurantLat: Double,
    val restaurantLng: Double,
    val deliveryAddress: String,
    val deliveryLat: Double,
    val deliveryLng: Double,
    val deliveryInstructions: String,
    val items: List<OrderItem>,
    val subtotal: Double,
    val deliveryFee: Double,
    val packagingCharge: Double,
    val taxes: Double,
    val discount: Double,
    val totalPayable: Double,
    val couponCode: String? = null,
    val paymentMethod: String
)

@Serializable
data class PaymentRequestDto(
    val orderId: String,
    val idempotencyKey: String,
    val callbackId: String,
    val method: String,
    val amount: Double
)

@Serializable
data class PaymentResponseDto(
    val orderId: String,
    val transactionId: String,
    val paymentStatus: String,
    val duplicateCallbackIgnored: Boolean = false,
    val message: String
)
