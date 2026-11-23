package com.foodflow.data.repository

import com.foodflow.core.common.AppError
import com.foodflow.core.common.AppResult
import com.foodflow.core.common.DispatcherProvider
import com.foodflow.core.common.ErrorMapper
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.core.datastore.UserPreferencesDataStore
import com.foodflow.data.local.dao.AddressDao
import com.foodflow.data.local.dao.CartDao
import com.foodflow.data.local.dao.CatalogDao
import com.foodflow.data.local.dao.OrderDao
import com.foodflow.data.local.database.DatabaseSeeder
import com.foodflow.data.local.entity.CheckoutAttemptEntity
import com.foodflow.data.local.entity.OrderTimelineEventEntity
import com.foodflow.data.local.mapper.toDomain
import com.foodflow.data.local.mapper.toEntity
import com.foodflow.data.remote.api.OrderRemoteDataSource
import com.foodflow.data.remote.dto.CreateOrderRequestDto
import com.foodflow.data.remote.dto.OrderDto
import com.foodflow.domain.model.CheckoutAttempt
import com.foodflow.domain.model.CheckoutAttemptStatus
import com.foodflow.domain.model.Order
import com.foodflow.domain.model.OrderItem
import com.foodflow.domain.model.OrderStatus
import com.foodflow.domain.model.OrderTimelineEvent
import com.foodflow.domain.model.PaymentMethod
import com.foodflow.domain.repository.CartRepository
import com.foodflow.domain.repository.OrderRepository
import com.foodflow.domain.usecase.CalculateCartTotalsUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Idempotent Order & Checkout Repository.
 *
 * Offline order creation is NEVER marked confirmed; checkout requires connectivity
 * to verify stock, pricing, restaurant acceptance, and payment status while preserving
 * the customer's cart locally.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class OrderRepositoryImpl @Inject constructor(
    private val orderDao: OrderDao,
    private val cartDao: CartDao,
    private val catalogDao: CatalogDao,
    private val addressDao: AddressDao,
    private val orderRemote: OrderRemoteDataSource,
    private val cartRepository: CartRepository,
    private val calculateCartTotals: CalculateCartTotalsUseCase,
    private val connectivityObserver: ConnectivityObserver,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val json: Json,
    private val dispatchers: DispatcherProvider
) : OrderRepository {

    private val checkoutMutex = Mutex()

    /** Streams the signed-in user's ID, falling back to the demo user when needed. */
    private fun currentUserIdFlow(): Flow<String> =
        preferencesDataStore.preferences.map { it.userId ?: DatabaseSeeder.DEFAULT_USER_ID }

    /** Streams saved orders with their delivery-partner details attached. */
    override fun observeOrders(): Flow<List<Order>> =
        currentUserIdFlow().flatMapLatest { userId ->
            combine(
                orderDao.observeOrders(userId),
                catalogDao.observeDeliveryPartners()
            ) { orders, partners ->
                val partnerMap = partners.associateBy { it.id }
                orders.map { entity ->
                    val partner = entity.deliveryPartnerId?.let { partnerMap[it]?.toDomain() }
                    entity.toDomain(json, partner)
                }
            }
        }

    /** Streams one saved order with its delivery-partner details attached. */
    override fun observeOrder(orderId: String): Flow<Order?> =
        combine(
            orderDao.observeOrder(orderId),
            catalogDao.observeDeliveryPartners()
        ) { entity, partners ->
            if (entity == null) null
            else {
                val partner = entity.deliveryPartnerId?.let { id ->
                    partners.firstOrNull { it.id == id }?.toDomain()
                }
                entity.toDomain(json, partner)
            }
        }

    /** Streams the saved timeline for one order. */
    override fun observeOrderTimeline(orderId: String): Flow<List<OrderTimelineEvent>> =
        orderDao.observeTimeline(orderId).map { list -> list.map { it.toDomain() } }

    /** Streams the current user's unfinished checkout attempt, if present. */
    override fun observeActiveCheckoutAttempt(): Flow<CheckoutAttempt?> =
        currentUserIdFlow().flatMapLatest { userId ->
            orderDao.observeActiveCheckoutAttempt(userId).map { it?.toDomain() }
        }

    /** Fetches remote orders and merges them into the local Room cache. */
    override suspend fun refreshOrders(): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            val userId = preferencesDataStore.getSnapshot().userId ?: DatabaseSeeder.DEFAULT_USER_ID
            when (val res = orderRemote.fetchOrders(userId)) {
                is AppResult.Success -> {
                    val now = System.currentTimeMillis()
                    res.data.forEach { dto ->
                        val existing = orderDao.getOrder(dto.id)
                        if (existing == null) {
                            orderDao.upsertOrder(dto.toEntity(json, now, userId))
                        } else {
                            val merged = existing.copy(
                                paymentStatus = dto.paymentStatus,
                                updatedAtMs = now
                            )
                            orderDao.upsertOrder(merged)
                        }
                    }
                    AppResult.Success(Unit)
                }
                is AppResult.Error -> res
            }
        } catch (t: Throwable) {
            AppResult.Error(ErrorMapper.map(t))
        }
    }

    /** Validates the local cart and saves or reuses a checkout attempt. */
    override suspend fun startOrResumeCheckout(
        addressId: String,
        paymentMethod: PaymentMethod,
        couponCode: String?
    ): AppResult<CheckoutAttempt> = withContext(dispatchers.io) {
        try {
            checkoutMutex.withLock {
                if (!connectivityObserver.isCurrentlyOnline()) {
                    return@withLock AppResult.Error(
                        AppError.Offline(
                            userMessage = "Internet connection is required to verify and place your order. Your cart is safely saved."
                        )
                    )
                }

                val userId = preferencesDataStore.getSnapshot().userId ?: DatabaseSeeder.DEFAULT_USER_ID
                val cartEntities = cartDao.getCartItems(userId)
                if (cartEntities.isEmpty()) {
                    return@withLock AppResult.Error(AppError.Validation("Your cart is empty."))
                }

                val address = addressDao.getAddress(addressId)
                    ?: return@withLock AppResult.Error(AppError.Validation("Please select a delivery address."))
                if (!address.isServiceable) {
                    return@withLock AppResult.Error(
                        AppError.Validation("Selected address (${address.city}) is outside our delivery radius.")
                    )
                }

                val restaurantId = cartEntities.first().restaurantId
                val restaurant = catalogDao.getRestaurant(restaurantId)
                    ?: return@withLock AppResult.Error(AppError.Validation("Restaurant info unavailable."))
                val coupon = couponCode?.let { catalogDao.getCouponByCode(it)?.toDomain() }
                val breakdown = calculateCartTotals(
                    items = cartEntities.map { it.toDomain(json) },
                    baseDeliveryFee = restaurant.deliveryFee,
                    coupon = coupon
                )

                val now = System.currentTimeMillis()
                val existingAttempt = orderDao.getActiveCheckoutAttempt(userId)
                if (existingAttempt != null &&
                    existingAttempt.restaurantId == restaurantId &&
                    kotlin.math.abs(existingAttempt.totalAmount - breakdown.totalPayable) < 0.01
                ) {
                    val refreshed = existingAttempt.copy(
                        addressId = addressId,
                        paymentMethod = paymentMethod.name,
                        couponCode = breakdown.appliedCouponCode,
                        updatedAtMs = now
                    )
                    orderDao.upsertCheckoutAttempt(refreshed)
                    return@withLock AppResult.Success(refreshed.toDomain())
                }

                val newAttempt = CheckoutAttemptEntity(
                    idempotencyKey = "idem_${UUID.randomUUID()}",
                    userId = userId,
                    restaurantId = restaurantId,
                    addressId = addressId,
                    paymentMethod = paymentMethod.name,
                    couponCode = breakdown.appliedCouponCode,
                    totalAmount = breakdown.totalPayable,
                    serverOrderId = null,
                    paymentTransactionId = null,
                    status = CheckoutAttemptStatus.IN_PROGRESS.name,
                    createdAtMs = now,
                    updatedAtMs = now
                )
                orderDao.upsertCheckoutAttempt(newAttempt)
                AppResult.Success(newAttempt.toDomain())
            }
        } catch (t: Throwable) {
            AppResult.Error(ErrorMapper.map(t))
        }
    }

    /** Creates or recovers an order using the checkout attempt's retry-safe key. */
    override suspend fun submitIdempotentOrder(attempt: CheckoutAttempt): AppResult<Order> =
        withContext(dispatchers.io) {
            try {
                checkoutMutex.withLock {
                    if (!connectivityObserver.isCurrentlyOnline()) {
                        return@withLock AppResult.Error(
                            AppError.Offline(
                                userMessage = "You went offline before order confirmation. Tap Retry once connected — duplicate orders are prevented."
                            )
                        )
                    }

                    orderDao.getOrderByIdempotencyKey(attempt.idempotencyKey)?.let { localOrder ->
                        val partner = localOrder.deliveryPartnerId?.let { catalogDao.getDeliveryPartner(it)?.toDomain() }
                        return@withLock AppResult.Success(localOrder.toDomain(json, partner))
                    }

                    when (val lookup = orderRemote.getOrderByIdempotencyKey(attempt.idempotencyKey)) {
                        is AppResult.Success -> {
                            val existingRemote = lookup.data
                            if (existingRemote != null) {
                                return@withLock persistConfirmedOrderAndUpdateAttempt(existingRemote, attempt)
                            }
                        }
                        is AppResult.Error -> {
                            // Continue to POST /orders which also enforces idempotency on server
                        }
                    }

                    val userId = attempt.userId
                    val cartItems = cartDao.getCartItems(userId).map { it.toDomain(json) }
                    if (cartItems.isEmpty()) {
                        return@withLock AppResult.Error(AppError.Validation("Cart is empty."))
                    }

                    val restaurant = catalogDao.getRestaurant(attempt.restaurantId)
                        ?: return@withLock AppResult.Error(AppError.Validation("Restaurant unavailable."))
                    val address = addressDao.getAddress(attempt.addressId)
                        ?: return@withLock AppResult.Error(AppError.Validation("Delivery address missing."))
                    val coupon = attempt.couponCode?.let { catalogDao.getCouponByCode(it)?.toDomain() }
                    val breakdown = calculateCartTotals(
                        items = cartItems,
                        baseDeliveryFee = restaurant.deliveryFee,
                        coupon = coupon
                    )

                    val request = CreateOrderRequestDto(
                        idempotencyKey = attempt.idempotencyKey,
                        userId = userId,
                        restaurantId = restaurant.id,
                        restaurantName = restaurant.name,
                        restaurantAddress = restaurant.address,
                        restaurantLat = restaurant.latitude,
                        restaurantLng = restaurant.longitude,
                        deliveryAddress = "${address.line1}, ${address.city} - ${address.pincode}",
                        deliveryLat = address.latitude,
                        deliveryLng = address.longitude,
                        deliveryInstructions = address.deliveryInstructions,
                        items = cartItems.map { item ->
                            OrderItem(
                                foodId = item.itemId,
                                foodName = item.foodName,
                                quantity = item.quantity,
                                unitPrice = item.unitPrice + item.addonsTotalPerUnit,
                                isVeg = item.isVeg,
                                addonsSummary = item.selectedAddons.joinToString(", ") { it.name }
                            )
                        },
                        subtotal = breakdown.subtotal,
                        deliveryFee = breakdown.deliveryFee,
                        packagingCharge = breakdown.packagingCharge,
                        taxes = breakdown.taxes,
                        discount = breakdown.discount,
                        totalPayable = breakdown.totalPayable,
                        couponCode = breakdown.appliedCouponCode,
                        paymentMethod = attempt.paymentMethod.name
                    )

                    when (val createdRes = orderRemote.createOrder(request)) {
                        is AppResult.Success -> persistConfirmedOrderAndUpdateAttempt(createdRes.data, attempt)
                        is AppResult.Error -> createdRes
                    }
                }
            } catch (t: Throwable) {
                AppResult.Error(ErrorMapper.map(t))
            }
        }

    /** Saves a confirmed order, its first timeline event, and updated checkout state. */
    private suspend fun persistConfirmedOrderAndUpdateAttempt(
        dto: OrderDto,
        attempt: CheckoutAttempt
    ): AppResult<Order> {
        val now = System.currentTimeMillis()
        val entity = dto.toEntity(json, now, attempt.userId)
        orderDao.upsertOrder(entity)
        orderDao.insertTimelineEvent(
            OrderTimelineEventEntity(
                id = "evt_${entity.id}_placed",
                orderId = entity.id,
                sequenceNumber = 1L,
                eventType = "ORDER_PLACED",
                title = "Order Placed",
                description = "Order #${entity.id} registered with ${entity.restaurantName}.",
                timestampMs = now
            )
        )
        orderDao.upsertCheckoutAttempt(
            CheckoutAttemptEntity(
                idempotencyKey = attempt.idempotencyKey,
                userId = attempt.userId,
                restaurantId = attempt.restaurantId,
                addressId = attempt.addressId,
                paymentMethod = attempt.paymentMethod.name,
                couponCode = attempt.couponCode,
                totalAmount = attempt.totalAmount,
                serverOrderId = entity.id,
                paymentTransactionId = attempt.paymentTransactionId,
                status = CheckoutAttemptStatus.AWAITING_PAYMENT.name,
                createdAtMs = attempt.createdAtMs,
                updatedAtMs = now
            )
        )
        return AppResult.Success(entity.toDomain(json, null))
    }

    /** Checks the remote source for an order and caches it locally when found. */
    override suspend fun lookupOrderByIdempotencyKey(idempotencyKey: String): AppResult<Order?> =
        withContext(dispatchers.io) {
            try {
                when (val remote = orderRemote.getOrderByIdempotencyKey(idempotencyKey)) {
                    is AppResult.Success -> {
                        val dto = remote.data ?: return@withContext AppResult.Success(null)
                        val entity = dto.toEntity(json, System.currentTimeMillis())
                        orderDao.upsertOrder(entity)
                        AppResult.Success(entity.toDomain(json, null))
                    }
                    is AppResult.Error -> remote
                }
            } catch (t: Throwable) {
                AppResult.Error(ErrorMapper.map(t))
            }
        }

    /** Checks cancellation rules, requests cancellation, and updates local order history. */
    override suspend fun cancelOrder(orderId: String, reason: String): AppResult<Order> =
        withContext(dispatchers.io) {
            try {
                val local = orderDao.getOrder(orderId)
                    ?: return@withContext AppResult.Error(AppError.Validation("Order not found."))
                val currentStatus = runCatching { OrderStatus.valueOf(local.orderStatus) }.getOrDefault(OrderStatus.PLACED)
                if (!currentStatus.canTransitionTo(OrderStatus.CANCELLED)) {
                    return@withContext AppResult.Error(
                        AppError.Conflict("Orders cannot be cancelled once picked up or delivered.")
                    )
                }
                when (val res = orderRemote.cancelOrder(orderId, reason)) {
                    is AppResult.Success -> {
                        val now = System.currentTimeMillis()
                        val updated = local.copy(
                            orderStatus = OrderStatus.CANCELLED.name,
                            paymentStatus = res.data.paymentStatus,
                            updatedAtMs = now
                        )
                        orderDao.upsertOrder(updated)
                        orderDao.insertTimelineEvent(
                            OrderTimelineEventEntity(
                                id = "evt_${orderId}_cancel_$now",
                                orderId = orderId,
                                sequenceNumber = local.lastEventSequence + 1L,
                                eventType = "ORDER_CANCELLED",
                                title = "Order Cancelled",
                                description = reason.ifBlank { "Cancelled by customer." },
                                timestampMs = now
                            )
                        )
                        AppResult.Success(updated.toDomain(json, null))
                    }
                    is AppResult.Error -> res
                }
            } catch (t: Throwable) {
                AppResult.Error(ErrorMapper.map(t))
            }
        }

    /** Saves the customer's rating and comment on the local order record. */
    override suspend fun rateOrder(orderId: String, rating: Int, comment: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            try {
                val local = orderDao.getOrder(orderId)
                    ?: return@withContext AppResult.Error(AppError.Validation("Order not found."))
                orderDao.upsertOrder(
                    local.copy(
                        ratingGiven = rating.coerceIn(1, 5),
                        reviewComment = comment.trim(),
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
                AppResult.Success(Unit)
            } catch (t: Throwable) {
                AppResult.Error(ErrorMapper.map(t))
            }
        }

    /** Adds available items from a past order back to the cart. */
    override suspend fun reorder(orderId: String): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            val local = orderDao.getOrder(orderId)
                ?: return@withContext AppResult.Error(AppError.Validation("Order not found."))
            val restaurant = catalogDao.getRestaurant(local.restaurantId)?.toDomain(false)
                ?: return@withContext AppResult.Error(AppError.Validation("Restaurant is no longer listed."))
            val items = runCatching { json.decodeFromString<List<OrderItem>>(local.itemsJson) }
                .getOrDefault(emptyList())
            if (items.isEmpty()) return@withContext AppResult.Error(AppError.Validation("No items to reorder."))

            var first = true
            for (orderItem in items) {
                val foodEntity = catalogDao.getFoodItem(orderItem.foodId) ?: continue
                val food = foodEntity.toDomain(json)
                if (!food.isAvailable) continue
                repeat(orderItem.quantity) {
                    cartRepository.addItem(
                        restaurant = restaurant,
                        foodItem = food,
                        addons = emptyList(),
                        clearExistingDifferentRestaurant = first
                    )
                    first = false
                }
            }
            AppResult.Success(Unit)
        } catch (t: Throwable) {
            AppResult.Error(ErrorMapper.map(t))
        }
    }

    /** Removes the saved checkout attempt with this retry-safe key. */
    override suspend fun clearActiveCheckoutAttempt(idempotencyKey: String) = withContext(dispatchers.io) {
        orderDao.deleteCheckoutAttempt(idempotencyKey)
    }
}
