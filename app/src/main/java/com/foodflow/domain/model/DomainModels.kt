package com.foodflow.domain.model

import kotlinx.serialization.Serializable

/**
 * Pure Kotlin domain models with zero Android/Room/Retrofit framework dependencies.
 */

data class FoodCategory(
    val id: String,
    val name: String,
    val subtitle: String,
    val imageKey: String,
    val accentHex: Long
)

data class PromoBanner(
    val id: String,
    val tag: String,
    val title: String,
    val subtitle: String,
    val ctaLabel: String,
    val imageKey: String,
    val accentHex: Long,
    val categoryFilter: String? = null,
    val couponCode: String = "",
    val restaurantId: String? = null
)

data class Restaurant(
    val id: String,
    val name: String,
    val cuisines: List<String>,
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
    val isFavorite: Boolean = false,
    val lastUpdatedMs: Long = 0L
) {
    /**
     * Data-driven highlight badge derived from real restaurant metrics.
     */
    val highlightBadge: String?
        get() = when {
            rating >= 4.8 -> "Bestseller"
            deliveryTimeMin <= 20 -> "Fast Delivery"
            isPopular -> "Popular"
            else -> null
        }

    val priceForTwo: Int
        get() = (minOrderAmount * 2).toInt().coerceAtLeast(250)

    val reviewCount: Int
        get() = ratingCount
}

@Serializable
data class FoodAddon(
    val id: String,
    val name: String,
    val price: Double
)

data class FoodItem(
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
) {
    val category: String get() = categoryName
}

data class Coupon(
    val code: String,
    val title: String,
    val description: String,
    val discountType: DiscountType,
    val discountValue: Double,
    val minOrderAmount: Double,
    val maxDiscount: Double,
    val expiresAtMs: Long,
    val isActive: Boolean = true
) {
    /** Returns whether this coupon is inactive or its expiry time has passed. */
    fun isExpired(nowMs: Long): Boolean = !isActive || expiresAtMs < nowMs
}

enum class DiscountType {
    PERCENTAGE,
    FLAT,
    FREE_DELIVERY
}

enum class SyncStatus {
    PENDING,
    SYNCING,
    SYNCED,
    FAILED
}

data class CartItem(
    val itemId: String,
    val restaurantId: String,
    val restaurantName: String,
    val foodName: String,
    val unitPrice: Double,
    val quantity: Int,
    val isVeg: Boolean,
    val imageKey: String,
    val selectedAddons: List<FoodAddon> = emptyList(),
    val cookingInstructions: String = "",
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
    val updatedAtMs: Long = 0L,
    val addedAtMs: Long = updatedAtMs
) {
    val addonsTotalPerUnit: Double get() = selectedAddons.sumOf { it.price }
    val lineTotal: Double get() = (unitPrice + addonsTotalPerUnit) * quantity
}

data class CartPriceBreakdown(
    val subtotal: Double = 0.0,
    val deliveryFee: Double = 0.0,
    val packagingCharge: Double = 0.0,
    val taxes: Double = 0.0,
    val discount: Double = 0.0,
    val totalPayable: Double = 0.0,
    val appliedCouponCode: String? = null
)

sealed interface CartReconciliationNotice {
    data class PriceChanged(
        val itemId: String,
        val foodName: String,
        val oldPrice: Double,
        val newPrice: Double
    ) : CartReconciliationNotice

    data class ItemUnavailable(
        val itemId: String,
        val foodName: String
    ) : CartReconciliationNotice

    data class RestaurantClosed(
        val restaurantName: String
    ) : CartReconciliationNotice
}

enum class AddressLabel(val displayName: String) {
    HOME("Home"),
    WORK("Work"),
    OTHER("Other")
}

data class DeliveryAddress(
    val id: String,
    val label: AddressLabel,
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
) {
    val formattedShort: String get() = "$line1, $city"
}

/**
 * Explicit Order lifecycle state machine.
 */
enum class OrderStatus(val stepIndex: Int, val label: String) {
    PLACED(0, "Order Placed"),
    ACCEPTED(1, "Restaurant Accepted"),
    PREPARING(2, "Food Preparing"),
    READY_FOR_PICKUP(3, "Ready for Pickup"),
    PARTNER_ASSIGNED(4, "Delivery Partner Assigned"),
    PICKED_UP(5, "Picked Up"),
    DELIVERED(6, "Delivered"),
    CANCELLED(-1, "Cancelled");

    val isTerminal: Boolean get() = this == DELIVERED || this == CANCELLED
    val isActive: Boolean get() = !isTerminal

    /** Checks whether the order may move to the requested status under normal transition rules. */
    fun canTransitionTo(next: OrderStatus): Boolean {
        if (this == next) return false
        if (this.isTerminal) return false
        if (next == CANCELLED) {
            return this.stepIndex <= PARTNER_ASSIGNED.stepIndex
        }
        return next.stepIndex == this.stepIndex + 1 ||
            (next.stepIndex > this.stepIndex && !(this == PLACED && next == DELIVERED))
    }

    /** Checks whether the order may advance exactly one lifecycle step. */
    fun canAdvanceSequentiallyTo(next: OrderStatus): Boolean {
        if (this.isTerminal) return false
        if (next == CANCELLED) return this.stepIndex <= PARTNER_ASSIGNED.stepIndex
        return next.stepIndex == this.stepIndex + 1
    }
}

/**
 * Payment lifecycle kept strictly independent of [OrderStatus].
 */
enum class PaymentStatus(val label: String) {
    NOT_STARTED("Not Started"),
    PENDING("Payment Pending"),
    SUCCESS("Paid"),
    FAILED("Payment Failed"),
    REFUND_PENDING("Refund Pending"),
    REFUNDED("Refunded"),
    COD_DUE("Cash on Delivery")
}

enum class PaymentMethod(val displayName: String, val subtitle: String) {
    UPI("UPI", "Instant pay via GPay, PhonePe or Paytm"),
    CARD("Credit / Debit Card", "Visa •••• 4242 · Instant checkout"),
    COD("Cash on Delivery", "Pay cash or UPI at your doorstep")
}

data class DeliveryPartner(
    val id: String,
    val name: String,
    val phone: String,
    val rating: Double,
    val vehicleNumber: String,
    val totalDeliveries: Int
)

data class RiderLocation(
    val latitude: Double,
    val longitude: Double,
    val progressFraction: Float,
    val etaMinutes: Int,
    val updatedAtMs: Long
)

@Serializable
data class OrderItem(
    val foodId: String,
    val foodName: String,
    val quantity: Int,
    val unitPrice: Double,
    val isVeg: Boolean,
    val addonsSummary: String = ""
) {
    val lineTotal: Double get() = unitPrice * quantity
}

data class OrderTimelineEvent(
    val id: String,
    val orderId: String,
    val sequenceNumber: Long,
    val eventType: String,
    val title: String,
    val description: String,
    val timestampMs: Long
)

data class Order(
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
    val couponCode: String?,
    val orderStatus: OrderStatus,
    val paymentStatus: PaymentStatus,
    val paymentMethod: PaymentMethod,
    val estimatedDeliveryMinutes: Int,
    val isDelayed: Boolean = false,
    val delayReason: String? = null,
    val deliveryPartner: DeliveryPartner? = null,
    val riderLocation: RiderLocation? = null,
    val lastEventSequence: Long = 0L,
    val ratingGiven: Int? = null,
    val reviewComment: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long
) {
    val riderProgress: Float
        get() = riderLocation?.progressFraction ?: when (orderStatus) {
            OrderStatus.PLACED -> 0.05f
            OrderStatus.ACCEPTED -> 0.18f
            OrderStatus.PREPARING -> 0.35f
            OrderStatus.READY_FOR_PICKUP -> 0.50f
            OrderStatus.PARTNER_ASSIGNED -> 0.62f
            OrderStatus.PICKED_UP -> 0.82f
            OrderStatus.DELIVERED -> 1.0f
            OrderStatus.CANCELLED -> 0f
        }
}

data class InvoiceDocument(
    val orderId: String,
    val fileName: String,
    val uriString: String,
    val savedLocationLabel: String
)

data class CheckoutAttempt(
    val idempotencyKey: String,
    val userId: String,
    val restaurantId: String,
    val addressId: String,
    val paymentMethod: PaymentMethod,
    val couponCode: String?,
    val totalAmount: Double,
    val serverOrderId: String?,
    val paymentTransactionId: String?,
    val status: CheckoutAttemptStatus,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

enum class CheckoutAttemptStatus {
    IN_PROGRESS,
    AWAITING_PAYMENT,
    PAYMENT_PENDING,
    COMPLETED,
    FAILED
}

enum class SupportIssueType(val title: String, val subtitle: String) {
    MISSING_ITEM("Missing Item", "One or more items were missing from my order"),
    WRONG_ITEM("Wrong Item Delivered", "I received an incorrect dish or customization"),
    DELAYED_DELIVERY("Delayed Delivery", "My order is taking longer than the promised ETA"),
    ORDER_CANCELLATION("Cancel Order", "Request cancellation or check cancellation policy"),
    PAYMENT_DEDUCTED("Payment Deducted, Order Not Confirmed", "Money debited during timeout or pending payment"),
    REFUND_STATUS("Refund Status", "Track refund timeline for a cancelled or failed order")
}

data class SupportTicket(
    val id: String,
    val userId: String,
    val orderId: String?,
    val issueType: SupportIssueType,
    val description: String,
    val status: String,
    val resolutionNote: String,
    val createdAtMs: Long
)

enum class RestaurantSortOption(val label: String) {
    RELEVANCE("Relevance"),
    DELIVERY_TIME("Fastest Delivery"),
    RATING("Rating: High to Low"),
    COST_LOW("Min Order: Low to High")
}

data class TrackingDiagnosticInfo(
    val lastIgnoredEventNote: String? = null,
    val duplicatesIgnoredCount: Int = 0,
    val staleIgnoredCount: Int = 0
)
