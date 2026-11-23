package com.foodflow.data.remote.dto

import com.foodflow.domain.model.OrderItem
import com.foodflow.domain.model.ScheduleRecurrenceRule
import kotlinx.serialization.Serializable

// --- Feature 1: Dynamic Restaurant Reviews & Ratings DTOs ---

@Serializable
data class SubmitReviewRequestDto(
    val idempotencyKey: String,
    val restaurantId: String,
    val orderId: String,
    val userId: String,
    val userName: String,
    val rating: Int,
    val comment: String
)

@Serializable
data class RestaurantReviewDto(
    val id: String,
    val restaurantId: String,
    val orderId: String,
    val userId: String,
    val userName: String,
    val rating: Int,
    val comment: String,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

@Serializable
data class RestaurantRatingAggregateDto(
    val restaurantId: String,
    val averageRating: Double,
    val totalReviewCount: Int,
    val ratingSum: Double
)

@Serializable
data class SubmitReviewResponseDto(
    val review: RestaurantReviewDto,
    val updatedAggregate: RestaurantRatingAggregateDto,
    val wasUpdated: Boolean = false
)

@Serializable
data class RestaurantReviewsResponseDto(
    val aggregate: RestaurantRatingAggregateDto,
    val reviews: List<RestaurantReviewDto>
)

// --- Feature 2: Scheduled Ordering & Live Availability/Pricing Validation DTOs ---

@Serializable
data class RestaurantAvailabilityResponseDto(
    val restaurantId: String,
    val isOpen: Boolean,
    val isAcceptingOrders: Boolean,
    val isTemporarilyPaused: Boolean,
    val supportsDeliveryAddress: Boolean,
    val deliverySlotAvailable: Boolean,
    val deliveryCapacityAvailable: Boolean,
    val openHour: Int,
    val openMinute: Int,
    val closeHour: Int,
    val closeMinute: Int,
    val preparationLeadMinutes: Int,
    val estimatedDeliveryMinutes: Int,
    val unavailableReason: String? = null
)

@Serializable
data class FoodItemAvailabilityItemDto(
    val foodId: String,
    val foodName: String,
    val exists: Boolean,
    val isAvailable: Boolean,
    val customizationsValid: Boolean = true,
    val unavailableReason: String? = null
)

@Serializable
data class LatestPricingQuoteDto(
    val restaurantId: String,
    val itemPricesById: Map<String, Double>,
    val subtotal: Double,
    val deliveryFee: Double,
    val packagingCharge: Double,
    val taxes: Double,
    val discount: Double,
    val totalPayable: Double,
    val couponStillValid: Boolean,
    val couponNote: String? = null
)

@Serializable
data class ScheduledPaymentValidationDto(
    val approved: Boolean,
    val paymentStatus: String,
    val transactionId: String? = null,
    val failureReason: String? = null,
    val retryable: Boolean = false
)

@Serializable
data class ScheduledOrderApiDto(
    val scheduleId: String,
    val customerId: String,
    val restaurantId: String,
    val addressId: String,
    val items: List<OrderItem>,
    val authorizedTotalPayable: Double,
    val couponCode: String? = null,
    val paymentMethod: String,
    val scheduledTimestampUtcMs: Long,
    val recurrenceRule: ScheduleRecurrenceRule,
    val status: String,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

@Serializable
data class ExecuteScheduledOccurrenceRequestDto(
    val scheduleId: String,
    val occurrenceId: String,
    val idempotencyKey: String,
    val customerId: String,
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
    val paymentMethod: String,
    val paymentStatus: String
)

@Serializable
data class ExecuteScheduledOccurrenceResponseDto(
    val occurrenceId: String,
    val idempotencyKey: String,
    val order: OrderDto,
    val duplicateIgnored: Boolean = false
)
