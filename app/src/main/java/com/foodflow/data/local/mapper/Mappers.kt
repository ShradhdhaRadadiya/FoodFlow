package com.foodflow.data.local.mapper

import com.foodflow.data.local.entity.AddressEntity
import com.foodflow.data.local.entity.CartItemEntity
import com.foodflow.data.local.entity.CheckoutAttemptEntity
import com.foodflow.data.local.entity.CouponEntity
import com.foodflow.data.local.entity.DeliveryPartnerEntity
import com.foodflow.data.local.entity.FoodCategoryEntity
import com.foodflow.data.local.entity.FoodItemEntity
import com.foodflow.data.local.entity.OrderEntity
import com.foodflow.data.local.entity.OrderTimelineEventEntity
import com.foodflow.data.local.entity.RestaurantEntity
import com.foodflow.data.local.entity.SupportTicketEntity
import com.foodflow.data.remote.dto.AddressDto
import com.foodflow.data.remote.dto.CategoryDto
import com.foodflow.data.remote.dto.CouponDto
import com.foodflow.data.remote.dto.DeliveryPartnerDto
import com.foodflow.data.remote.dto.FoodItemDto
import com.foodflow.data.remote.dto.OrderDto
import com.foodflow.data.remote.dto.RestaurantDto
import com.foodflow.domain.model.AddressLabel
import com.foodflow.domain.model.CartItem
import com.foodflow.domain.model.CheckoutAttempt
import com.foodflow.domain.model.CheckoutAttemptStatus
import com.foodflow.domain.model.Coupon
import com.foodflow.domain.model.DeliveryAddress
import com.foodflow.domain.model.DeliveryPartner
import com.foodflow.domain.model.DiscountType
import com.foodflow.domain.model.FoodAddon
import com.foodflow.domain.model.FoodCategory
import com.foodflow.domain.model.FoodItem
import com.foodflow.domain.model.Order
import com.foodflow.domain.model.OrderItem
import com.foodflow.domain.model.OrderStatus
import com.foodflow.domain.model.OrderTimelineEvent
import com.foodflow.domain.model.PaymentMethod
import com.foodflow.domain.model.PaymentStatus
import com.foodflow.domain.model.Restaurant
import com.foodflow.domain.model.RiderLocation
import com.foodflow.domain.model.SupportIssueType
import com.foodflow.domain.model.SupportTicket
import com.foodflow.domain.model.SyncStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val ONE_DAY_MS = 86_400_000L
private const val ONE_HOUR_MS = 3_600_000L

/** Converts a category from the remote format into a Room entity. */
fun CategoryDto.toEntity() = FoodCategoryEntity(
    id = id,
    name = name,
    subtitle = subtitle,
    imageKey = imageKey,
    accentHex = accentHex
)

/** Converts a saved category entity into a domain model. */
fun FoodCategoryEntity.toDomain() = FoodCategory(
    id = id,
    name = name,
    subtitle = subtitle,
    imageKey = imageKey,
    accentHex = accentHex
)

/** Converts remote restaurant data into a Room entity with its update time. */
fun RestaurantDto.toEntity(nowMs: Long) = RestaurantEntity(
    id = id,
    name = name,
    cuisinesCsv = cuisines.joinToString("|"),
    rating = rating,
    ratingCount = ratingCount,
    deliveryTimeMin = deliveryTimeMin,
    deliveryFee = deliveryFee,
    minOrderAmount = minOrderAmount,
    offerText = offerText,
    isPureVeg = isPureVeg,
    isOpen = isOpen,
    isPopular = isPopular,
    isRecommended = isRecommended,
    imageKey = imageKey,
    address = address,
    distanceKm = distanceKm,
    latitude = latitude,
    longitude = longitude,
    lastUpdatedMs = nowMs
)

/** Converts a saved restaurant entity into a domain model with favorite state. */
fun RestaurantEntity.toDomain(isFavorite: Boolean) = Restaurant(
    id = id,
    name = name,
    cuisines = cuisinesCsv.split("|").filter { it.isNotBlank() },
    rating = rating,
    ratingCount = ratingCount,
    deliveryTimeMin = deliveryTimeMin,
    deliveryFee = deliveryFee,
    minOrderAmount = minOrderAmount,
    offerText = offerText,
    isPureVeg = isPureVeg,
    isOpen = isOpen,
    isPopular = isPopular,
    isRecommended = isRecommended,
    imageKey = imageKey,
    address = address,
    distanceKm = distanceKm,
    latitude = latitude,
    longitude = longitude,
    isFavorite = isFavorite,
    lastUpdatedMs = lastUpdatedMs
)

/** Converts remote menu data into a Room entity, encoding its add-ons. */
fun FoodItemDto.toEntity(json: Json) = FoodItemEntity(
    id = id,
    restaurantId = restaurantId,
    name = name,
    description = description,
    categoryName = categoryName,
    price = price,
    originalPrice = originalPrice,
    isVeg = isVeg,
    isAvailable = isAvailable,
    isRecommended = isRecommended,
    isBestseller = isBestseller,
    rating = rating,
    imageKey = imageKey,
    addonsJson = json.encodeToString(addons),
    spiceLevel = spiceLevel
)

/**
 * Gracefully guards against corrupted cached JSON (Edge Case #30) by returning
 * an empty add-on list instead of crashing the UI stream.
 */
/** Converts a saved menu entity into a domain model and safely decodes its add-ons. */
fun FoodItemEntity.toDomain(json: Json): FoodItem {
    val parsedAddons = runCatching {
        json.decodeFromString<List<FoodAddon>>(addonsJson)
    }.getOrDefault(emptyList())
    return FoodItem(
        id = id,
        restaurantId = restaurantId,
        name = name,
        description = description,
        categoryName = categoryName,
        price = price,
        originalPrice = originalPrice,
        isVeg = isVeg,
        isAvailable = isAvailable,
        isRecommended = isRecommended,
        isBestseller = isBestseller,
        rating = rating,
        imageKey = imageKey,
        addons = parsedAddons,
        spiceLevel = spiceLevel
    )
}

/** Converts a remote coupon into a Room entity with an absolute expiry time. */
fun CouponDto.toEntity(nowMs: Long) = CouponEntity(
    code = code,
    title = title,
    description = description,
    discountType = discountType,
    discountValue = discountValue,
    minOrderAmount = minOrderAmount,
    maxDiscount = maxDiscount,
    expiresAtMs = nowMs + (expiresInDays * ONE_DAY_MS),
    isActive = isActive
)

/** Converts a saved coupon entity into a domain model. */
fun CouponEntity.toDomain() = Coupon(
    code = code,
    title = title,
    description = description,
    discountType = runCatching { DiscountType.valueOf(discountType) }.getOrDefault(DiscountType.FLAT),
    discountValue = discountValue,
    minOrderAmount = minOrderAmount,
    maxDiscount = maxDiscount,
    expiresAtMs = expiresAtMs,
    isActive = isActive
)

/** Converts remote address data into an entity owned by the specified user. */
fun AddressDto.toEntity(userId: String) = AddressEntity(
    id = id,
    userId = userId,
    label = label,
    recipientName = recipientName,
    line1 = line1,
    landmark = landmark,
    city = city,
    pincode = pincode,
    deliveryInstructions = deliveryInstructions,
    latitude = latitude,
    longitude = longitude,
    isDefault = isDefault,
    isServiceable = isServiceable
)

/** Converts a saved address entity into a domain model. */
fun AddressEntity.toDomain() = DeliveryAddress(
    id = id,
    label = runCatching { AddressLabel.valueOf(label) }.getOrDefault(AddressLabel.OTHER),
    recipientName = recipientName,
    line1 = line1,
    landmark = landmark,
    city = city,
    pincode = pincode,
    deliveryInstructions = deliveryInstructions,
    latitude = latitude,
    longitude = longitude,
    isDefault = isDefault,
    isServiceable = isServiceable
)

/** Converts a domain address into a Room entity for the specified user. */
fun DeliveryAddress.toEntity(userId: String) = AddressEntity(
    id = id,
    userId = userId,
    label = label.name,
    recipientName = recipientName,
    line1 = line1,
    landmark = landmark,
    city = city,
    pincode = pincode,
    deliveryInstructions = deliveryInstructions,
    latitude = latitude,
    longitude = longitude,
    isDefault = isDefault,
    isServiceable = isServiceable
)

/** Converts remote delivery-partner data into a Room entity. */
fun DeliveryPartnerDto.toEntity() = DeliveryPartnerEntity(
    id = id,
    name = name,
    phone = phone,
    rating = rating,
    vehicleNumber = vehicleNumber,
    totalDeliveries = totalDeliveries
)

/** Converts a saved delivery-partner entity into a domain model. */
fun DeliveryPartnerEntity.toDomain() = DeliveryPartner(
    id = id,
    name = name,
    phone = phone,
    rating = rating,
    vehicleNumber = vehicleNumber,
    totalDeliveries = totalDeliveries
)

/** Converts a saved cart entity and safely decodes its selected add-ons. */
fun CartItemEntity.toDomain(json: Json): CartItem {
    val addons = runCatching { json.decodeFromString<List<FoodAddon>>(selectedAddonsJson) }
        .getOrDefault(emptyList())
    return CartItem(
        itemId = itemId,
        restaurantId = restaurantId,
        restaurantName = restaurantName,
        foodName = foodName,
        unitPrice = unitPrice,
        quantity = quantity,
        isVeg = isVeg,
        imageKey = imageKey,
        selectedAddons = addons,
        cookingInstructions = cookingInstructions,
        syncStatus = runCatching { SyncStatus.valueOf(syncStatus) }.getOrDefault(SyncStatus.PENDING),
        updatedAtMs = updatedAtMs,
        addedAtMs = addedAtMs
    )
}

/** Converts remote order data into a Room entity, encoding items and deriving timestamps. */
fun OrderDto.toEntity(json: Json, nowMs: Long, overrideUserId: String? = null): OrderEntity {
    val created = nowMs - (createdHoursAgo * ONE_HOUR_MS)
    return OrderEntity(
        id = id,
        userId = overrideUserId ?: userId,
        idempotencyKey = idempotencyKey,
        restaurantId = restaurantId,
        restaurantName = restaurantName,
        restaurantAddress = restaurantAddress,
        restaurantLat = restaurantLat,
        restaurantLng = restaurantLng,
        deliveryAddress = deliveryAddress,
        deliveryLat = deliveryLat,
        deliveryLng = deliveryLng,
        deliveryInstructions = deliveryInstructions,
        itemsJson = json.encodeToString(items),
        subtotal = subtotal,
        deliveryFee = deliveryFee,
        packagingCharge = packagingCharge,
        taxes = taxes,
        discount = discount,
        totalPayable = totalPayable,
        couponCode = couponCode,
        orderStatus = orderStatus,
        paymentStatus = paymentStatus,
        paymentMethod = paymentMethod,
        estimatedDeliveryMinutes = estimatedDeliveryMinutes,
        deliveryPartnerId = deliveryPartnerId,
        riderLat = if (deliveryPartnerId != null) (restaurantLat + deliveryLat) / 2.0 else null,
        riderLng = if (deliveryPartnerId != null) (restaurantLng + deliveryLng) / 2.0 else null,
        riderProgress = if (orderStatus == "DELIVERED") 1f else if (orderStatus == "PICKED_UP") 0.55f else 0.1f,
        riderUpdatedAtMs = nowMs,
        lastEventSequence = if (orderStatus == "PREPARING") 2L else if (orderStatus == "DELIVERED") 9L else 1L,
        createdAtMs = created,
        updatedAtMs = nowMs
    )
}

/** Converts a saved order entity into a domain model with its partner and rider details. */
fun OrderEntity.toDomain(json: Json, partner: DeliveryPartner?): Order {
    val parsedItems = runCatching { json.decodeFromString<List<OrderItem>>(itemsJson) }
        .getOrDefault(emptyList())
    val riderLoc = if (riderLat != null && riderLng != null) {
        RiderLocation(
            latitude = riderLat,
            longitude = riderLng,
            progressFraction = riderProgress,
            etaMinutes = estimatedDeliveryMinutes,
            updatedAtMs = riderUpdatedAtMs
        )
    } else null

    return Order(
        id = id,
        userId = userId,
        idempotencyKey = idempotencyKey,
        restaurantId = restaurantId,
        restaurantName = restaurantName,
        restaurantAddress = restaurantAddress,
        restaurantLat = restaurantLat,
        restaurantLng = restaurantLng,
        deliveryAddress = deliveryAddress,
        deliveryLat = deliveryLat,
        deliveryLng = deliveryLng,
        deliveryInstructions = deliveryInstructions,
        items = parsedItems,
        subtotal = subtotal,
        deliveryFee = deliveryFee,
        packagingCharge = packagingCharge,
        taxes = taxes,
        discount = discount,
        totalPayable = totalPayable,
        couponCode = couponCode,
        orderStatus = runCatching { OrderStatus.valueOf(orderStatus) }.getOrDefault(OrderStatus.PLACED),
        paymentStatus = runCatching { PaymentStatus.valueOf(paymentStatus) }.getOrDefault(PaymentStatus.PENDING),
        paymentMethod = runCatching { PaymentMethod.valueOf(paymentMethod) }.getOrDefault(PaymentMethod.UPI),
        estimatedDeliveryMinutes = estimatedDeliveryMinutes,
        isDelayed = isDelayed,
        delayReason = delayReason,
        deliveryPartner = partner,
        riderLocation = riderLoc,
        lastEventSequence = lastEventSequence,
        ratingGiven = ratingGiven,
        reviewComment = reviewComment,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs
    )
}

/** Converts a saved order timeline event into a domain model. */
fun OrderTimelineEventEntity.toDomain() = OrderTimelineEvent(
    id = id,
    orderId = orderId,
    sequenceNumber = sequenceNumber,
    eventType = eventType,
    title = title,
    description = description,
    timestampMs = timestampMs
)

/** Converts a saved checkout attempt into a domain model. */
fun CheckoutAttemptEntity.toDomain() = CheckoutAttempt(
    idempotencyKey = idempotencyKey,
    userId = userId,
    restaurantId = restaurantId,
    addressId = addressId,
    paymentMethod = runCatching { PaymentMethod.valueOf(paymentMethod) }.getOrDefault(PaymentMethod.UPI),
    couponCode = couponCode,
    totalAmount = totalAmount,
    serverOrderId = serverOrderId,
    paymentTransactionId = paymentTransactionId,
    status = runCatching { CheckoutAttemptStatus.valueOf(status) }
        .getOrDefault(CheckoutAttemptStatus.IN_PROGRESS),
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs
)

/** Converts a saved support ticket entity into a domain model. */
fun SupportTicketEntity.toDomain() = SupportTicket(
    id = id,
    userId = userId,
    orderId = orderId,
    issueType = runCatching { SupportIssueType.valueOf(issueType) }.getOrDefault(SupportIssueType.DELAYED_DELIVERY),
    description = description,
    status = status,
    resolutionNote = resolutionNote,
    createdAtMs = createdAtMs
)
