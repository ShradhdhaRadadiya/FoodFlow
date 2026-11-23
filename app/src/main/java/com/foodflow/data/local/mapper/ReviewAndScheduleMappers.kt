package com.foodflow.data.local.mapper

import com.foodflow.data.local.entity.RestaurantReviewEntity
import com.foodflow.data.local.entity.ScheduledOrderEntity
import com.foodflow.data.local.entity.ScheduledOrderExecutionEntity
import com.foodflow.data.remote.dto.RestaurantReviewDto
import com.foodflow.domain.model.OrderItem
import com.foodflow.domain.model.PaymentMethod
import com.foodflow.domain.model.PaymentStatus
import com.foodflow.domain.model.RestaurantReview
import com.foodflow.domain.model.ScheduleExecutionStatus
import com.foodflow.domain.model.ScheduleFailureCategory
import com.foodflow.domain.model.SchedulePriceConsentPolicy
import com.foodflow.domain.model.ScheduleRecurrenceRule
import com.foodflow.domain.model.ScheduledOrder
import com.foodflow.domain.model.ScheduledOrderExecution
import com.foodflow.domain.model.ScheduledOrderStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Converts a saved review entity into a domain review. */
fun RestaurantReviewEntity.toDomain() = RestaurantReview(
    id = id,
    restaurantId = restaurantId,
    orderId = orderId,
    userId = userId,
    userName = userName,
    rating = rating,
    comment = comment,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs
)

/** Converts remote review data into a Room entity. */
fun RestaurantReviewDto.toEntity() = RestaurantReviewEntity(
    id = id,
    restaurantId = restaurantId,
    orderId = orderId,
    userId = userId,
    userName = userName,
    rating = rating,
    comment = comment,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs
)

/** Converts a saved schedule into a domain model and safely decodes its items and recurrence rule. */
fun ScheduledOrderEntity.toDomain(json: Json): ScheduledOrder {
    val parsedItems = runCatching { json.decodeFromString<List<OrderItem>>(itemsJson) }
        .getOrDefault(emptyList())
    val parsedRule = runCatching { json.decodeFromString<ScheduleRecurrenceRule>(recurrenceRuleJson) }
        .getOrDefault(ScheduleRecurrenceRule())
    return ScheduledOrder(
        id = id,
        userId = userId,
        restaurantId = restaurantId,
        restaurantName = restaurantName,
        restaurantAddress = restaurantAddress,
        restaurantLat = restaurantLat,
        restaurantLng = restaurantLng,
        addressId = addressId,
        deliveryAddress = deliveryAddress,
        deliveryLat = deliveryLat,
        deliveryLng = deliveryLng,
        deliveryInstructions = deliveryInstructions,
        items = parsedItems,
        authorizedSubtotal = authorizedSubtotal,
        authorizedDeliveryFee = authorizedDeliveryFee,
        authorizedPackagingCharge = authorizedPackagingCharge,
        authorizedTaxes = authorizedTaxes,
        authorizedDiscount = authorizedDiscount,
        authorizedTotalPayable = authorizedTotalPayable,
        couponCode = couponCode,
        paymentMethod = runCatching { PaymentMethod.valueOf(paymentMethod) }.getOrDefault(PaymentMethod.UPI),
        priceConsentPolicy = runCatching { SchedulePriceConsentPolicy.valueOf(priceConsentPolicy) }
            .getOrDefault(SchedulePriceConsentPolicy.STRICT_NO_INCREASE),
        recurrenceRule = parsedRule,
        nextExecutionAtUtcMs = nextExecutionAtUtcMs,
        status = runCatching { ScheduledOrderStatus.valueOf(status) }.getOrDefault(ScheduledOrderStatus.UPCOMING),
        completedOccurrences = completedOccurrences,
        lastExecutedAtUtcMs = lastExecutedAtUtcMs,
        lastGeneratedOrderId = lastGeneratedOrderId,
        lastFailureCategory = runCatching { ScheduleFailureCategory.valueOf(lastFailureCategory) }
            .getOrDefault(ScheduleFailureCategory.NONE),
        lastFailureReason = lastFailureReason,
        failureAcknowledged = failureAcknowledged,
        creationIdempotencyKey = creationIdempotencyKey,
        retryCount = retryCount,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs
    )
}

/** Converts a scheduled-order domain model into a Room entity, encoding its items and rule. */
fun ScheduledOrder.toEntity(json: Json) = ScheduledOrderEntity(
    id = id,
    userId = userId,
    restaurantId = restaurantId,
    restaurantName = restaurantName,
    restaurantAddress = restaurantAddress,
    restaurantLat = restaurantLat,
    restaurantLng = restaurantLng,
    addressId = addressId,
    deliveryAddress = deliveryAddress,
    deliveryLat = deliveryLat,
    deliveryLng = deliveryLng,
    deliveryInstructions = deliveryInstructions,
    itemsJson = json.encodeToString(items),
    authorizedSubtotal = authorizedSubtotal,
    authorizedDeliveryFee = authorizedDeliveryFee,
    authorizedPackagingCharge = authorizedPackagingCharge,
    authorizedTaxes = authorizedTaxes,
    authorizedDiscount = authorizedDiscount,
    authorizedTotalPayable = authorizedTotalPayable,
    couponCode = couponCode,
    paymentMethod = paymentMethod.name,
    priceConsentPolicy = priceConsentPolicy.name,
    recurrenceRuleJson = json.encodeToString(recurrenceRule),
    nextExecutionAtUtcMs = nextExecutionAtUtcMs,
    status = status.name,
    completedOccurrences = completedOccurrences,
    lastExecutedAtUtcMs = lastExecutedAtUtcMs,
    lastGeneratedOrderId = lastGeneratedOrderId,
    lastFailureCategory = lastFailureCategory.name,
    lastFailureReason = lastFailureReason,
    failureAcknowledged = failureAcknowledged,
    creationIdempotencyKey = creationIdempotencyKey,
    retryCount = retryCount,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs
)

/** Converts a saved schedule execution entity into a domain model. */
fun ScheduledOrderExecutionEntity.toDomain() = ScheduledOrderExecution(
    id = id,
    scheduleId = scheduleId,
    occurrenceNumber = occurrenceNumber,
    idempotencyKey = idempotencyKey,
    scheduledForUtcMs = scheduledForUtcMs,
    executedAtUtcMs = executedAtUtcMs,
    status = runCatching { ScheduleExecutionStatus.valueOf(status) }
        .getOrDefault(ScheduleExecutionStatus.FAILED_VALIDATION),
    failureCategory = runCatching { ScheduleFailureCategory.valueOf(failureCategory) }
        .getOrDefault(ScheduleFailureCategory.NONE),
    failureReason = failureReason,
    generatedOrderId = generatedOrderId,
    quotedTotalPayable = quotedTotalPayable,
    authorizedTotalPayable = authorizedTotalPayable,
    paymentStatus = runCatching { PaymentStatus.valueOf(paymentStatus) }
        .getOrDefault(PaymentStatus.NOT_STARTED),
    retryCount = retryCount
)

/** Converts a schedule execution domain model into a Room entity. */
fun ScheduledOrderExecution.toEntity() = ScheduledOrderExecutionEntity(
    id = id,
    scheduleId = scheduleId,
    occurrenceNumber = occurrenceNumber,
    idempotencyKey = idempotencyKey,
    scheduledForUtcMs = scheduledForUtcMs,
    executedAtUtcMs = executedAtUtcMs,
    status = status.name,
    failureCategory = failureCategory.name,
    failureReason = failureReason,
    generatedOrderId = generatedOrderId,
    quotedTotalPayable = quotedTotalPayable,
    authorizedTotalPayable = authorizedTotalPayable,
    paymentStatus = paymentStatus.name,
    retryCount = retryCount
)
