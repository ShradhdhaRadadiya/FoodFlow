package com.foodflow.data.mock

import com.foodflow.core.common.AppError
import com.foodflow.core.common.AppResult
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.data.local.dao.CatalogDao
import com.foodflow.data.local.dao.MockServerStateDao
import com.foodflow.data.local.database.DatabaseSeeder
import com.foodflow.data.local.entity.MockServerStateEntity
import com.foodflow.data.remote.api.CartRemoteDataSource
import com.foodflow.data.remote.api.OrderRemoteDataSource
import com.foodflow.data.remote.api.PaymentRemoteDataSource
import com.foodflow.data.remote.api.RestaurantRemoteDataSource
import com.foodflow.data.remote.api.ReviewRemoteDataSource
import com.foodflow.data.remote.api.UserRemoteDataSource
import com.foodflow.data.remote.dto.CartOperationRequestDto
import com.foodflow.data.remote.dto.CartSyncAckDto
import com.foodflow.data.remote.dto.CouponDto
import com.foodflow.data.remote.dto.CreateOrderRequestDto
import com.foodflow.data.remote.dto.FoodItemDto
import com.foodflow.data.remote.dto.OrderDto
import com.foodflow.data.remote.dto.PaymentRequestDto
import com.foodflow.data.remote.dto.PaymentResponseDto
import com.foodflow.data.remote.dto.RestaurantDto
import com.foodflow.data.remote.dto.RestaurantRatingAggregateDto
import com.foodflow.data.remote.dto.RestaurantReviewDto
import com.foodflow.data.remote.dto.RestaurantReviewsResponseDto
import com.foodflow.data.remote.dto.SubmitReviewRequestDto
import com.foodflow.data.remote.dto.SubmitReviewResponseDto
import com.foodflow.domain.model.RestaurantRatingCalculator
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class MockServerSnapshot(
    val idempotencyOrders: Map<String, OrderDto> = emptyMap(),
    val processedCartOperationIds: Set<String> = emptySet(),
    val processedPaymentCallbacks: Map<String, PaymentResponseDto> = emptyMap(),
    val orderPayments: Map<String, PaymentResponseDto> = emptyMap(),
    val restaurantRatingAggregates: Map<String, RestaurantRatingAggregateDto> = emptyMap(),
    val reviewsByOrderId: Map<String, RestaurantReviewDto> = emptyMap(),
    val processedReviewIdempotencyKeys: Set<String> = emptySet(),
    val orderSequenceCounter: Int = 9100
)

/**
 * Stateful Local Mock Backend simulating a realistic FoodFlow server cluster.
 *
 * Architectural notes:
 * 1. Persists simulated server-side state (idempotency-key -> Order map, processed cart
 *    operation IDs, processed payment callbacks, and authoritative restaurant rating aggregates
 *    + reviews) in `mock_server_state` so server state survives process restarts and can be
 *    reset independently of client cache.
 * 2. Implements idempotent `POST /orders` + `GET /orders/by-idempotency-key/{key}`,
 *    including the "server wrote order then response timed out" scenario so retries
 *    prove duplicate order prevention end-to-end.
 * 3. Strictly local simulation — does not claim cross-device cloud sync.
 */
@Singleton
class LocalMockBackend @Inject constructor(
    private val connectivityObserver: ConnectivityObserver,
    private val scenarioController: MockScenarioController,
    private val seeder: DatabaseSeeder,
    private val catalogDao: CatalogDao,
    private val serverStateDao: MockServerStateDao,
    private val json: Json
) : RestaurantRemoteDataSource,
    CartRemoteDataSource,
    OrderRemoteDataSource,
    PaymentRemoteDataSource,
    UserRemoteDataSource,
    ReviewRemoteDataSource {

    private val mutex = Mutex()
    private var cachedSnapshot: MockServerSnapshot? = null

    /** Loads the persisted mock-server state, using the cached snapshot when available. */
    private suspend fun loadSnapshot(): MockServerSnapshot {
        cachedSnapshot?.let { return it }
        val raw = serverStateDao.getValue(SERVER_STATE_KEY)?.valueJson
        val parsed = if (raw != null) {
            runCatching { json.decodeFromString<MockServerSnapshot>(raw) }.getOrNull()
        } else null
        return (parsed ?: MockServerSnapshot()).also { cachedSnapshot = it }
    }

    /** Updates the in-memory snapshot and saves it to the mock-server state table. */
    private suspend fun saveSnapshot(snapshot: MockServerSnapshot) {
        cachedSnapshot = snapshot
        serverStateDao.putValue(
            MockServerStateEntity(
                key = SERVER_STATE_KEY,
                valueJson = json.encodeToString(snapshot),
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    /**
     * Resets ONLY the mock server's persisted state independently from local app Room tables.
     */
    /** Clears persisted mock-server state without clearing the app's local catalog or cart. */
    suspend fun resetMockServerData() = mutex.withLock {
        cachedSnapshot = MockServerSnapshot()
        serverStateDao.clearAll()
    }

    /** Applies configured delay and simulated connection failures before a mock request. */
    private suspend fun <T> checkFaultsBeforeRequest(allowTimeoutAfterWrite: Boolean = false): AppResult.Error? {
        val cfg = scenarioController.current()
        if (cfg.simulatedLatencyMs > 0) {
            delay(cfg.simulatedLatencyMs)
        }
        if (cfg.forcedOffline || !connectivityObserver.isCurrentlyOnline()) {
            return AppResult.Error(AppError.Offline())
        }
        return when (cfg.networkFault) {
            SimulatedNetworkFault.NONE -> null
            SimulatedNetworkFault.TIMEOUT -> AppResult.Error(AppError.Timeout())
            SimulatedNetworkFault.TIMEOUT_AFTER_SERVER_WRITE ->
                if (allowTimeoutAfterWrite) null else AppResult.Error(AppError.Timeout())
            SimulatedNetworkFault.SERVER_ERROR_500 -> AppResult.Error(AppError.ServerError(500))
            SimulatedNetworkFault.INVALID_JSON_RESPONSE -> AppResult.Error(AppError.InvalidResponse())
        }
    }

    /** Loads seeded restaurants and overlays any mock-server rating totals. */
    override suspend fun fetchRestaurants(): AppResult<List<RestaurantDto>> {
        checkFaultsBeforeRequest<List<RestaurantDto>>()?.let { return it }
        val dataset = seeder.loadSeedDataset()
            ?: return AppResult.Error(AppError.EmptyResponse())
        val aggregates = mutex.withLock { loadSnapshot().restaurantRatingAggregates }
        val enriched = dataset.restaurants.map { dto ->
            val agg = aggregates[dto.id]
            if (agg != null) {
                dto.copy(rating = agg.averageRating, ratingCount = agg.totalReviewCount)
            } else {
                dto
            }
        }
        return AppResult.Success(enriched)
    }

    /** Loads a seeded menu and applies configured availability and price scenarios. */
    override suspend fun fetchMenu(restaurantId: String): AppResult<List<FoodItemDto>> {
        checkFaultsBeforeRequest<List<FoodItemDto>>()?.let { return it }
        val cfg = scenarioController.current()
        val dataset = seeder.loadSeedDataset()
            ?: return AppResult.Error(AppError.EmptyResponse())
        val items = dataset.foodItems
            .filter { it.restaurantId == restaurantId }
            .map { item ->
                item.copy(
                    isAvailable = item.isAvailable && item.id !in cfg.unavailableFoodIds,
                    price = cfg.priceOverrides[item.id] ?: item.price
                )
            }
        return AppResult.Success(items)
    }

    /** Checks a coupon's existence, expiry, and minimum order amount in the mock server. */
    override suspend fun validateCoupon(code: String, subtotal: Double): AppResult<CouponDto> {
        checkFaultsBeforeRequest<CouponDto>()?.let { return it }
        val dataset = seeder.loadSeedDataset() ?: return AppResult.Error(AppError.EmptyResponse())
        val match = dataset.coupons.firstOrNull { it.code.equals(code.trim(), ignoreCase = true) }
            ?: return AppResult.Error(AppError.Validation("Coupon code '$code' is invalid."))
        if (!match.isActive || match.expiresInDays < 0) {
            return AppResult.Error(AppError.Validation("Coupon '${match.code}' has expired."))
        }
        if (subtotal < match.minOrderAmount) {
            return AppResult.Error(
                AppError.Validation("Minimum order of ₹${match.minOrderAmount.toInt()} required for ${match.code}.")
            )
        }
        return AppResult.Success(match)
    }

    /** Applies a queued cart change once and returns the mock server's price and availability. */
    override suspend fun syncOperation(request: CartOperationRequestDto): AppResult<CartSyncAckDto> {
        checkFaultsBeforeRequest<CartSyncAckDto>()?.let { return it }
        val cfg = scenarioController.current()
        return mutex.withLock {
            val snap = loadSnapshot()
            // Deduplication guard: if an operation already succeeded on server but ACK was lost,
            // return a safe idempotent acknowledgement instead of re-applying it.
            if (request.operationId in snap.processedCartOperationIds) {
                return@withLock AppResult.Success(
                    CartSyncAckDto(
                        operationId = request.operationId,
                        accepted = true,
                        duplicateIgnored = true,
                        serverMessage = "Operation already applied on server"
                    )
                )
            }

            val restaurant = catalogDao.getRestaurant(request.restaurantId)
            val foodEntity = catalogDao.getFoodItem(request.itemId)
            val isRestaurantOpen = restaurant?.isOpen ?: true
            val isAvailable = (foodEntity?.isAvailable ?: true) && request.itemId !in cfg.unavailableFoodIds
            val serverPrice = cfg.priceOverrides[request.itemId] ?: foodEntity?.price ?: request.expectedUnitPrice

            val updatedSnap = snap.copy(
                processedCartOperationIds = snap.processedCartOperationIds + request.operationId
            )
            saveSnapshot(updatedSnap)

            AppResult.Success(
                CartSyncAckDto(
                    operationId = request.operationId,
                    accepted = isAvailable && isRestaurantOpen,
                    duplicateIgnored = false,
                    authoritativePrice = serverPrice,
                    itemAvailable = isAvailable,
                    restaurantOpen = isRestaurantOpen,
                    serverMessage = when {
                        !isRestaurantOpen -> "Restaurant is currently closed"
                        !isAvailable -> "Item is currently out of stock"
                        serverPrice != request.expectedUnitPrice -> "Item price updated to ₹${serverPrice.toInt()}"
                        else -> "Synced"
                    }
                )
            )
        }
    }

    /** Returns the requested items that still appear in the restaurant's current menu. */
    override suspend fun validateCartItems(
        restaurantId: String,
        itemIds: List<String>
    ): AppResult<List<FoodItemDto>> = fetchMenu(restaurantId).let { result ->
        when (result) {
            is AppResult.Success -> AppResult.Success(result.data.filter { it.id in itemIds })
            is AppResult.Error -> result
        }
    }

    /** Creates or recovers an order by its idempotency key, including timeout simulations. */
    override suspend fun createOrder(request: CreateOrderRequestDto): AppResult<OrderDto> {
        checkFaultsBeforeRequest<OrderDto>(allowTimeoutAfterWrite = true)?.let { return it }
        val cfg = scenarioController.current()

        return mutex.withLock {
            val snap = loadSnapshot()
            // IDEMPOTENCY GUARANTEE: If this idempotency key already created an order,
            // return the exact existing order without creating a duplicate!
            snap.idempotencyOrders[request.idempotencyKey]?.let { existing ->
                return@withLock AppResult.Success(existing)
            }

            // Check restaurant availability / rejection simulation
            if (cfg.restaurantRejectsOrder) {
                return@withLock AppResult.Error(
                    AppError.Conflict("Restaurant could not accept new orders right now. Try another kitchen.")
                )
            }

            // Validate item availability on server before confirming
            val unavailable = request.items.firstOrNull { it.foodId in cfg.unavailableFoodIds }
            if (unavailable != null) {
                return@withLock AppResult.Error(
                    AppError.Conflict("${unavailable.foodName} just went out of stock. Please update your cart.")
                )
            }

            val nextNumber = snap.orderSequenceCounter + 1
            val orderId = "FF-2026-$nextNumber"
            val initialPaymentStatus = if (request.paymentMethod == "COD") "COD_DUE" else "PENDING"

            val createdOrder = OrderDto(
                id = orderId,
                userId = request.userId,
                idempotencyKey = request.idempotencyKey,
                restaurantId = request.restaurantId,
                restaurantName = request.restaurantName,
                restaurantAddress = request.restaurantAddress,
                restaurantLat = request.restaurantLat,
                restaurantLng = request.restaurantLng,
                deliveryAddress = request.deliveryAddress,
                deliveryLat = request.deliveryLat,
                deliveryLng = request.deliveryLng,
                deliveryInstructions = request.deliveryInstructions,
                items = request.items,
                subtotal = request.subtotal,
                deliveryFee = request.deliveryFee,
                packagingCharge = request.packagingCharge,
                taxes = request.taxes,
                discount = request.discount,
                totalPayable = request.totalPayable,
                couponCode = request.couponCode,
                orderStatus = "PLACED",
                paymentStatus = initialPaymentStatus,
                paymentMethod = request.paymentMethod,
                estimatedDeliveryMinutes = if (cfg.delayedOrderActive) 45 else 26,
                deliveryPartnerId = null,
                createdHoursAgo = 0
            )

            val updatedSnap = snap.copy(
                idempotencyOrders = snap.idempotencyOrders + (request.idempotencyKey to createdOrder),
                orderSequenceCounter = nextNumber
            )
            saveSnapshot(updatedSnap)

            // Fault Scenario #16: Server committed the order, THEN the response timed out in transit!
            // Subsequent retry with the SAME idempotencyKey will recover `createdOrder` without duplication.
            if (cfg.networkFault == SimulatedNetworkFault.TIMEOUT_AFTER_SERVER_WRITE) {
                scenarioController.setNetworkFault(SimulatedNetworkFault.NONE)
                return@withLock AppResult.Error(
                    AppError.Timeout(
                        userMessage = "Connection timed out while confirming your order. Tap Retry — we'll check if it already went through safely.",
                        technicalDetail = "Simulated timeout AFTER server persisted $orderId for key ${request.idempotencyKey}"
                    )
                )
            }

            AppResult.Success(createdOrder)
        }
    }

    /** Looks up an existing order using the checkout's retry-safe key. */
    override suspend fun getOrderByIdempotencyKey(idempotencyKey: String): AppResult<OrderDto?> {
        checkFaultsBeforeRequest<OrderDto?>()?.let { return it }
        return mutex.withLock {
            AppResult.Success(loadSnapshot().idempotencyOrders[idempotencyKey])
        }
    }

    /** Loads an order from persisted mock-server state or seeded sample orders. */
    override suspend fun getOrder(orderId: String): AppResult<OrderDto> {
        checkFaultsBeforeRequest<OrderDto>()?.let { return it }
        return mutex.withLock {
            val snap = loadSnapshot()
            val found = snap.idempotencyOrders.values.firstOrNull { it.id == orderId }
                ?: seeder.loadSeedDataset()?.sampleOrders?.firstOrNull { it.id == orderId }
                ?: return@withLock AppResult.Error(AppError.EmptyResponse("Order $orderId not found"))
            AppResult.Success(found)
        }
    }

    /** Marks an order cancelled and moves successful payment toward refund. */
    override suspend fun cancelOrder(orderId: String, reason: String): AppResult<OrderDto> {
        checkFaultsBeforeRequest<OrderDto>()?.let { return it }
        return mutex.withLock {
            val snap = loadSnapshot()
            val entry = snap.idempotencyOrders.entries.firstOrNull { it.value.id == orderId }
            val existing = entry?.value
                ?: seeder.loadSeedDataset()?.sampleOrders?.firstOrNull { it.id == orderId }
                ?: return@withLock AppResult.Error(AppError.EmptyResponse("Order not found"))

            val nextPaymentStatus = when (existing.paymentStatus) {
                "SUCCESS" -> "REFUND_PENDING"
                else -> existing.paymentStatus
            }
            val updated = existing.copy(
                orderStatus = "CANCELLED",
                paymentStatus = nextPaymentStatus
            )
            if (entry != null) {
                saveSnapshot(snap.copy(idempotencyOrders = snap.idempotencyOrders + (entry.key to updated)))
            }
            AppResult.Success(updated)
        }
    }

    /** Returns mock-server orders together with seeded example orders. */
    override suspend fun fetchOrders(userId: String): AppResult<List<OrderDto>> {
        checkFaultsBeforeRequest<List<OrderDto>>()?.let { return it }
        return mutex.withLock {
            val snap = loadSnapshot()
            val seeded = seeder.loadSeedDataset()?.sampleOrders.orEmpty()
            val combined = (snap.idempotencyOrders.values + seeded)
                .distinctBy { it.id }
            AppResult.Success(combined)
        }
    }

    /** Processes a mock payment once per callback and persists its result on the server snapshot. */
    override suspend fun processPayment(request: PaymentRequestDto): AppResult<PaymentResponseDto> {
        checkFaultsBeforeRequest<PaymentResponseDto>()?.let { return it }
        val cfg = scenarioController.current()
        return mutex.withLock {
            val snap = loadSnapshot()
            // Duplicate payment callback protection: ignore duplicate callbackId safely
            snap.processedPaymentCallbacks[request.callbackId]?.let { existingResp ->
                return@withLock AppResult.Success(existingResp.copy(duplicateCallbackIgnored = true))
            }

            val resolvedStatus = if (request.method == "COD") {
                "COD_DUE"
            } else {
                when (cfg.paymentOutcome) {
                    SimulatedPaymentOutcome.SUCCESS -> "SUCCESS"
                    SimulatedPaymentOutcome.FAILED -> "FAILED"
                    SimulatedPaymentOutcome.PENDING -> "PENDING"
                }
            }

            val response = PaymentResponseDto(
                orderId = request.orderId,
                transactionId = "TXN-${request.orderId.takeLast(4)}-${System.currentTimeMillis() % 10000}",
                paymentStatus = resolvedStatus,
                duplicateCallbackIgnored = false,
                message = when (resolvedStatus) {
                    "SUCCESS" -> "Payment verified successfully"
                    "COD_DUE" -> "Cash on Delivery confirmed"
                    "PENDING" -> "Awaiting confirmation from bank gateway"
                    else -> "Payment authorization declined by bank"
                }
            )

            // Update server order's payment status authoritatively
            val updatedOrders = snap.idempotencyOrders.mapValues { (_, order) ->
                if (order.id == request.orderId) order.copy(paymentStatus = resolvedStatus) else order
            }

            saveSnapshot(
                snap.copy(
                    idempotencyOrders = updatedOrders,
                    processedPaymentCallbacks = snap.processedPaymentCallbacks + (request.callbackId to response),
                    orderPayments = snap.orderPayments + (request.orderId to response)
                )
            )
            AppResult.Success(response)
        }
    }

    /** Validates a phone number and returns the mock code resend delay. */
    override suspend fun sendOtp(phoneNumber: String): AppResult<Int> {
        checkFaultsBeforeRequest<Int>()?.let { return it }
        val digits = phoneNumber.filter { it.isDigit() }
        if (digits.length != 10) {
            return AppResult.Error(AppError.Validation("Please enter a valid 10-digit Indian mobile number."))
        }
        return AppResult.Success(30) // 30-second resend timer
    }

    /** Checks the demo sign-in code and returns a mock user ID and session token. */
    override suspend fun verifyOtp(phoneNumber: String, otp: String): AppResult<Pair<String, String>> {
        checkFaultsBeforeRequest<Pair<String, String>>()?.let { return it }
        if (scenarioController.current().otpExpiredMode) {
            return AppResult.Error(AppError.Validation("This OTP has expired. Tap Resend OTP to get a fresh code."))
        }
        if (otp.trim() != DEMO_OTP) {
            return AppResult.Error(AppError.Validation("Invalid verification code. Please check the 6-digit OTP and try again."))
        }
        val cleanPhone = phoneNumber.filter { it.isDigit() }
        val userId = DatabaseSeeder.DEFAULT_USER_ID
        val token = "session_jwt_${cleanPhone}_${System.currentTimeMillis()}"
        return AppResult.Success(userId to token)
    }

    /** Acknowledges favorite synchronization after applying configured mock request failures. */
    override suspend fun syncFavorite(userId: String, restaurantId: String, isFavorite: Boolean): AppResult<Unit> {
        checkFaultsBeforeRequest<Unit>()?.let { return it }
        return AppResult.Success(Unit)
    }

    /** Saves or updates a review and the authoritative restaurant rating in mock server state. */
    override suspend fun submitReview(
        request: SubmitReviewRequestDto,
        allowUpdate: Boolean
    ): AppResult<SubmitReviewResponseDto> {
        checkFaultsBeforeRequest<SubmitReviewResponseDto>()?.let { return it }
        if (request.rating !in 1..5) {
            return AppResult.Error(AppError.Validation("Please choose a rating between 1 and 5 stars."))
        }
        return mutex.withLock {
            val snap = loadSnapshot()
            val existingReview = snap.reviewsByOrderId[request.orderId]
            val currentAgg = resolveAuthoritativeAggregate(snap, request.restaurantId)
            val now = System.currentTimeMillis()

            // Safe idempotent retry when exact same submission request is retried after network drop
            if (request.idempotencyKey in snap.processedReviewIdempotencyKeys && existingReview != null) {
                return@withLock AppResult.Success(
                    SubmitReviewResponseDto(
                        review = existingReview,
                        updatedAggregate = currentAgg,
                        wasUpdated = allowUpdate
                    )
                )
            }

            if (existingReview != null && !allowUpdate) {
                return@withLock AppResult.Error(
                    AppError.Conflict("You have already submitted a review for this order. You can edit your existing review instead.")
                )
            }

            val updatedAggregate: RestaurantRatingAggregateDto
            val savedReview: RestaurantReviewDto
            val wasUpdated: Boolean

            if (existingReview != null) {
                val (newAvg, newCount) = RestaurantRatingCalculator.calculateAfterUpdatedReview(
                    currentRatingSum = currentAgg.ratingSum,
                    currentTotalReviews = currentAgg.totalReviewCount,
                    previousRating = existingReview.rating,
                    updatedRating = request.rating
                )
                val nextSum = (currentAgg.ratingSum - existingReview.rating + request.rating).coerceAtLeast(1.0)
                updatedAggregate = currentAgg.copy(
                    averageRating = newAvg,
                    totalReviewCount = newCount,
                    ratingSum = nextSum
                )
                savedReview = existingReview.copy(
                    rating = request.rating,
                    comment = request.comment.trim(),
                    updatedAtMs = now
                )
                wasUpdated = true
            } else {
                val (newAvg, newCount) = RestaurantRatingCalculator.calculateAfterNewReview(
                    currentRatingSum = currentAgg.ratingSum,
                    currentTotalReviews = currentAgg.totalReviewCount,
                    newRating = request.rating
                )
                val nextSum = (if (currentAgg.totalReviewCount == 0) 0.0 else currentAgg.ratingSum) + request.rating
                updatedAggregate = currentAgg.copy(
                    averageRating = newAvg,
                    totalReviewCount = newCount,
                    ratingSum = nextSum
                )
                savedReview = RestaurantReviewDto(
                    id = "rev_${request.orderId}",
                    restaurantId = request.restaurantId,
                    orderId = request.orderId,
                    userId = request.userId,
                    userName = request.userName.ifBlank { "FoodFlow Customer" },
                    rating = request.rating,
                    comment = request.comment.trim(),
                    createdAtMs = now,
                    updatedAtMs = now
                )
                wasUpdated = false
            }

            saveSnapshot(
                snap.copy(
                    restaurantRatingAggregates = snap.restaurantRatingAggregates + (request.restaurantId to updatedAggregate),
                    reviewsByOrderId = snap.reviewsByOrderId + (request.orderId to savedReview),
                    processedReviewIdempotencyKeys = snap.processedReviewIdempotencyKeys + request.idempotencyKey
                )
            )
            AppResult.Success(
                SubmitReviewResponseDto(
                    review = savedReview,
                    updatedAggregate = updatedAggregate,
                    wasUpdated = wasUpdated
                )
            )
        }
    }

    /** Returns a restaurant's mock-server rating summary and reviews, newest first. */
    override suspend fun fetchRestaurantReviews(restaurantId: String): AppResult<RestaurantReviewsResponseDto> {
        checkFaultsBeforeRequest<RestaurantReviewsResponseDto>()?.let { return it }
        return mutex.withLock {
            val snap = loadSnapshot()
            val agg = resolveAuthoritativeAggregate(snap, restaurantId)
            val reviews = snap.reviewsByOrderId.values
                .filter { it.restaurantId == restaurantId }
                .sortedByDescending { it.updatedAtMs }
            AppResult.Success(RestaurantReviewsResponseDto(aggregate = agg, reviews = reviews))
        }
    }

    /** Returns the mock server's authoritative rating summary for a restaurant. */
    override suspend fun fetchRestaurantRating(restaurantId: String): AppResult<RestaurantRatingAggregateDto> {
        checkFaultsBeforeRequest<RestaurantRatingAggregateDto>()?.let { return it }
        return mutex.withLock {
            val snap = loadSnapshot()
            AppResult.Success(resolveAuthoritativeAggregate(snap, restaurantId))
        }
    }

    /** Loads a saved rating aggregate or derives its starting value from catalog data. */
    private suspend fun resolveAuthoritativeAggregate(
        snap: MockServerSnapshot,
        restaurantId: String
    ): RestaurantRatingAggregateDto {
        snap.restaurantRatingAggregates[restaurantId]?.let { return it }
        val localRest = catalogDao.getRestaurant(restaurantId)
        val seedRest = seeder.loadSeedDataset()?.restaurants?.firstOrNull { it.id == restaurantId }
        val baseCount = (localRest?.ratingCount ?: seedRest?.ratingCount ?: 0).coerceAtLeast(0)
        val baseRating = if (baseCount == 0) 0.0 else (localRest?.rating ?: seedRest?.rating ?: 0.0)
        return RestaurantRatingAggregateDto(
            restaurantId = restaurantId,
            averageRating = RestaurantRatingCalculator.roundToTwoDecimals(baseRating),
            totalReviewCount = baseCount,
            ratingSum = baseRating * baseCount
        )
    }

    companion object {
        const val DEMO_OTP = "123456"
        private const val SERVER_STATE_KEY = "mock_server_snapshot_v1"
    }
}
