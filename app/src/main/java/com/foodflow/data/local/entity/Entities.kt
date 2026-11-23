package com.foodflow.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
data class FoodCategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val subtitle: String,
    val imageKey: String,
    val accentHex: Long
)

@Entity(tableName = "restaurants")
data class RestaurantEntity(
    @PrimaryKey val id: String,
    val name: String,
    val cuisinesCsv: String,
    val rating: Double,
    val ratingCount: Int,
    val deliveryTimeMin: Int,
    val deliveryFee: Double,
    val minOrderAmount: Double,
    val offerText: String?,
    val isPureVeg: Boolean,
    val isOpen: Boolean,
    val isPopular: Boolean,
    val isRecommended: Boolean,
    val imageKey: String,
    val address: String,
    val distanceKm: Double,
    val latitude: Double,
    val longitude: Double,
    val lastUpdatedMs: Long
)

@Entity(
    tableName = "food_items",
    indices = [Index(value = ["restaurantId"])]
)
data class FoodItemEntity(
    @PrimaryKey val id: String,
    val restaurantId: String,
    val name: String,
    val description: String,
    val categoryName: String,
    val price: Double,
    val originalPrice: Double?,
    val isVeg: Boolean,
    val isAvailable: Boolean,
    val isRecommended: Boolean,
    val isBestseller: Boolean,
    val rating: Double,
    val imageKey: String,
    val addonsJson: String,
    val spiceLevel: String
)

@Entity(tableName = "coupons")
data class CouponEntity(
    @PrimaryKey val code: String,
    val title: String,
    val description: String,
    val discountType: String,
    val discountValue: Double,
    val minOrderAmount: Double,
    val maxDiscount: Double,
    val expiresAtMs: Long,
    val isActive: Boolean
)

/**
 * Local Cart table — Room is the single source of truth so cart mutations succeed
 * immediately offline and survive process death.
 */
@Entity(
    tableName = "cart_items",
    indices = [Index(value = ["userId", "restaurantId"])]
)
data class CartItemEntity(
    @PrimaryKey val itemId: String,
    val userId: String,
    val restaurantId: String,
    val restaurantName: String,
    val foodName: String,
    val unitPrice: Double,
    val quantity: Int,
    val isVeg: Boolean,
    val imageKey: String,
    val selectedAddonsJson: String,
    val cookingInstructions: String,
    val syncStatus: String,
    val updatedAtMs: Long,
    val addedAtMs: Long = updatedAtMs
)

/**
 * Transactional Outbox entity.
 * Inserted atomically inside the same Room transaction as cart or favorite mutations
 * so WorkManager reliably replays deferred syncs even if the app crashes mid-operation.
 */
@Entity(
    tableName = "pending_operations",
    indices = [Index(value = ["status", "createdAtMs"])]
)
data class PendingOperationEntity(
    @PrimaryKey val operationId: String,
    val userId: String,
    val entityType: String, // "CART" or "FAVORITE"
    val operationType: String, // "UPSERT", "REMOVE", "CLEAR", "TOGGLE_FAV"
    val itemId: String,
    val restaurantId: String,
    val quantity: Int,
    val expectedUnitPrice: Double,
    val payloadJson: String,
    val status: String, // PENDING, SYNCING, SYNCED, FAILED
    val retryCount: Int = 0,
    val lastError: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

@Entity(tableName = "addresses")
data class AddressEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val label: String,
    val recipientName: String,
    val line1: String,
    val landmark: String,
    val city: String,
    val pincode: String,
    val deliveryInstructions: String,
    val latitude: Double,
    val longitude: Double,
    val isDefault: Boolean,
    val isServiceable: Boolean
)

@Entity(tableName = "delivery_partners")
data class DeliveryPartnerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String,
    val rating: Double,
    val vehicleNumber: String,
    val totalDeliveries: Int
)

@Entity(
    tableName = "orders",
    indices = [
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["userId", "createdAtMs"])
    ]
)
data class OrderEntity(
    @PrimaryKey val id: String,
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
    val itemsJson: String,
    val subtotal: Double,
    val deliveryFee: Double,
    val packagingCharge: Double,
    val taxes: Double,
    val discount: Double,
    val totalPayable: Double,
    val couponCode: String?,
    val orderStatus: String,
    val paymentStatus: String,
    val paymentMethod: String,
    val estimatedDeliveryMinutes: Int,
    val isDelayed: Boolean = false,
    val delayReason: String? = null,
    val deliveryPartnerId: String? = null,
    val riderLat: Double? = null,
    val riderLng: Double? = null,
    val riderProgress: Float = 0f,
    val riderUpdatedAtMs: Long = 0L,
    val lastEventSequence: Long = 0L,
    val ratingGiven: Int? = null,
    val reviewComment: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

@Entity(
    tableName = "order_timeline_events",
    indices = [Index(value = ["orderId", "sequenceNumber"], unique = true)]
)
data class OrderTimelineEventEntity(
    @PrimaryKey val id: String,
    val orderId: String,
    val sequenceNumber: Long,
    val eventType: String,
    val title: String,
    val description: String,
    val timestampMs: Long
)

@Entity(tableName = "checkout_attempts")
data class CheckoutAttemptEntity(
    @PrimaryKey val idempotencyKey: String,
    val userId: String,
    val restaurantId: String,
    val addressId: String,
    val paymentMethod: String,
    val couponCode: String?,
    val totalAmount: Double,
    val serverOrderId: String?,
    val paymentTransactionId: String?,
    val status: String,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

@Entity(
    tableName = "favorites",
    primaryKeys = ["userId", "restaurantId"]
)
data class FavoriteEntity(
    val userId: String,
    val restaurantId: String,
    val syncStatus: String,
    val updatedAtMs: Long
)

@Entity(
    tableName = "search_history",
    primaryKeys = ["userId", "query"]
)
data class SearchHistoryEntity(
    val userId: String,
    val query: String,
    val searchedAtMs: Long
)

@Entity(tableName = "support_tickets")
data class SupportTicketEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val orderId: String?,
    val issueType: String,
    val description: String,
    val status: String,
    val resolutionNote: String,
    val createdAtMs: Long
)

/**
 * Independent key-value store persisting the Local Mock Backend's "server-side" state
 * (idempotency map, processed operation IDs, payment callbacks, server orders) so that
 * simulated server state survives app restarts and can be reset independently of client cache.
 */
@Entity(tableName = "mock_server_state")
data class MockServerStateEntity(
    @PrimaryKey val key: String,
    val valueJson: String,
    val updatedAtMs: Long
)
