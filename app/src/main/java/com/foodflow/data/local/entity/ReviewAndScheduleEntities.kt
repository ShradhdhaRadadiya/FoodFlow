package com.foodflow.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "restaurant_reviews",
    indices = [
        Index(value = ["orderId"], unique = true),
        Index(value = ["restaurantId", "createdAtMs"])
    ]
)
data class RestaurantReviewEntity(
    @PrimaryKey val id: String,
    val restaurantId: String,
    val orderId: String,
    val userId: String,
    val userName: String,
    val rating: Int,
    val comment: String,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

@Entity(
    tableName = "scheduled_orders",
    indices = [
        Index(value = ["userId", "status"]),
        Index(value = ["nextExecutionAtUtcMs"])
    ]
)
data class ScheduledOrderEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val restaurantId: String,
    val restaurantName: String,
    val restaurantAddress: String,
    val restaurantLat: Double,
    val restaurantLng: Double,
    val addressId: String,
    val deliveryAddress: String,
    val deliveryLat: Double,
    val deliveryLng: Double,
    val deliveryInstructions: String,
    val itemsJson: String,
    val authorizedSubtotal: Double,
    val authorizedDeliveryFee: Double,
    val authorizedPackagingCharge: Double,
    val authorizedTaxes: Double,
    val authorizedDiscount: Double,
    val authorizedTotalPayable: Double,
    val couponCode: String?,
    val paymentMethod: String,
    val priceConsentPolicy: String,
    val recurrenceRuleJson: String,
    val nextExecutionAtUtcMs: Long,
    val status: String,
    val completedOccurrences: Int,
    val lastExecutedAtUtcMs: Long?,
    val lastGeneratedOrderId: String?,
    val lastFailureCategory: String,
    val lastFailureReason: String?,
    val failureAcknowledged: Boolean = false,
    val creationIdempotencyKey: String? = null,
    val retryCount: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

@Entity(
    tableName = "scheduled_order_executions",
    indices = [
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["scheduleId", "executedAtUtcMs"])
    ]
)
data class ScheduledOrderExecutionEntity(
    @PrimaryKey val id: String,
    val scheduleId: String,
    val occurrenceNumber: Int,
    val idempotencyKey: String,
    val scheduledForUtcMs: Long,
    val executedAtUtcMs: Long,
    val status: String,
    val failureCategory: String,
    val failureReason: String?,
    val generatedOrderId: String?,
    val quotedTotalPayable: Double,
    val authorizedTotalPayable: Double,
    val paymentStatus: String,
    val retryCount: Int
)
