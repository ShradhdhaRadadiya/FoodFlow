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
import com.foodflow.data.local.dao.ScheduledOrderDao
import com.foodflow.data.local.database.DatabaseSeeder
import com.foodflow.data.local.entity.OrderTimelineEventEntity
import com.foodflow.data.local.mapper.toDomain
import com.foodflow.data.local.mapper.toEntity
import com.foodflow.data.remote.api.FoodItemAvailabilityDataSource
import com.foodflow.data.remote.api.LatestPricingDataSource
import com.foodflow.data.remote.api.RestaurantAvailabilityDataSource
import com.foodflow.data.remote.api.ScheduledOrderExecutionDataSource
import com.foodflow.data.remote.api.ScheduledPaymentValidationDataSource
import com.foodflow.data.remote.dto.ExecuteScheduledOccurrenceRequestDto
import com.foodflow.data.sync.ScheduledOrderWorkScheduler
import com.foodflow.domain.model.CartPriceBreakdown
import com.foodflow.domain.model.CreateScheduledOrderParams
import com.foodflow.domain.model.OrderItem
import com.foodflow.domain.model.PaymentStatus
import com.foodflow.domain.model.RestaurantOperatingHours
import com.foodflow.domain.model.ScheduleExecutionOutcome
import com.foodflow.domain.model.ScheduleExecutionStatus
import com.foodflow.domain.model.ScheduleFailureCategory
import com.foodflow.domain.model.SchedulePriceConsentPolicy
import com.foodflow.domain.model.ScheduleRecurrenceRule
import com.foodflow.domain.model.ScheduleValidationResult
import com.foodflow.domain.model.ScheduledOrder
import com.foodflow.domain.model.ScheduledOrderExecution
import com.foodflow.domain.model.ScheduledOrderStatus
import com.foodflow.domain.repository.OrderTrackingRepository
import com.foodflow.domain.repository.ScheduledOrderRepository
import com.foodflow.domain.usecase.scheduled.CalculateNextScheduleOccurrenceUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for Advanced Scheduled Ordering.
 *
 * Persists schedules and independent execution history records in Room, orchestrates
 * live pre-creation and pre-execution validation via separate replaceable mock data sources
 * (restaurant availability, food item availability, latest pricing, payment authorization,
 * and scheduled order execution), chains exact-delay WorkManager jobs, prevents duplicate
 * executions via occurrence-level idempotency keys + Mutex, and routes every generated order
 * directly into FoodFlow's existing real-time [OrderTrackingRepository].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ScheduledOrderRepositoryImpl @Inject constructor(
    private val scheduledOrderDao: ScheduledOrderDao,
    private val cartDao: CartDao,
    private val catalogDao: CatalogDao,
    private val addressDao: AddressDao,
    private val orderDao: OrderDao,
    private val restaurantAvailabilityDataSource: RestaurantAvailabilityDataSource,
    private val foodItemAvailabilityDataSource: FoodItemAvailabilityDataSource,
    private val latestPricingDataSource: LatestPricingDataSource,
    private val paymentValidationDataSource: ScheduledPaymentValidationDataSource,
    private val executionDataSource: ScheduledOrderExecutionDataSource,
    private val orderTrackingRepository: OrderTrackingRepository,
    private val calculateNextOccurrence: CalculateNextScheduleOccurrenceUseCase,
    private val workScheduler: ScheduledOrderWorkScheduler,
    private val connectivityObserver: ConnectivityObserver,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val json: Json,
    private val dispatchers: DispatcherProvider
) : ScheduledOrderRepository {

    private val executionMutex = Mutex()

    /** Emits the current user's ID, falling back to the local demo account. */
    private fun currentUserIdFlow(): Flow<String> =
        preferencesDataStore.preferences.map { it.userId ?: DatabaseSeeder.DEFAULT_USER_ID }

    /** Observes and maps all scheduled orders belonging to the active user. */
    override fun observeScheduledOrders(): Flow<List<ScheduledOrder>> =
        currentUserIdFlow().flatMapLatest { userId ->
            scheduledOrderDao.observeScheduledOrders(userId).map { list ->
                list.map { it.toDomain(json) }
            }
        }

    /** Observes failed schedules whose alerts still need user acknowledgement. */
    override fun observeUnacknowledgedFailedSchedules(): Flow<List<ScheduledOrder>> =
        currentUserIdFlow().flatMapLatest { userId ->
            scheduledOrderDao.observeUnacknowledgedFailedSchedules(userId).map { list ->
                list.map { it.toDomain(json) }
            }
        }

    /** Observes one scheduled order and maps database changes to the domain model. */
    override fun observeScheduledOrder(scheduleId: String): Flow<ScheduledOrder?> =
        scheduledOrderDao.observeScheduledOrder(scheduleId).map { it?.toDomain(json) }

    /** Observes the recorded execution attempts for a scheduled order. */
    override fun observeExecutionHistory(scheduleId: String): Flow<List<ScheduledOrderExecution>> =
        scheduledOrderDao.observeExecutionsForSchedule(scheduleId).map { list ->
            list.map { it.toDomain() }
        }

    /** Marks the schedule's current failure alert as seen by the user. */
    override suspend fun acknowledgeScheduledOrderFailure(scheduleId: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            try {
                scheduledOrderDao.markFailureAcknowledged(scheduleId, System.currentTimeMillis())
                AppResult.Success(Unit)
            } catch (t: Throwable) {
                AppResult.Error(ErrorMapper.map(t))
            }
        }

    /** Loads operating hours for schedule validation, using defaults if availability cannot be checked. */
    override suspend fun getRestaurantOperatingHours(restaurantId: String): RestaurantOperatingHours =
        withContext(dispatchers.io) {
            val userId = preferencesDataStore.getSnapshot().userId ?: DatabaseSeeder.DEFAULT_USER_ID
            val defaultAddressId = addressDao.observeAddresses(userId).first().firstOrNull()?.id.orEmpty()
            val probeSlot = System.currentTimeMillis() + 3_600_000L
            when (
                val res = restaurantAvailabilityDataSource.checkAvailability(
                    restaurantId = restaurantId,
                    addressId = defaultAddressId,
                    targetSlotUtcMs = probeSlot
                )
            ) {
                is AppResult.Success -> RestaurantOperatingHours(
                    openHour = res.data.openHour,
                    openMinute = res.data.openMinute,
                    closeHour = res.data.closeHour,
                    closeMinute = res.data.closeMinute,
                    preparationLeadMinutes = res.data.preparationLeadMinutes
                )
                is AppResult.Error -> RestaurantOperatingHours()
            }
        }

    /** Validates the cart, restaurant availability, and latest pricing before schedule creation. */
    override suspend fun validateBeforeCreate(
        params: CreateScheduledOrderParams
    ): ScheduleValidationResult = withContext(dispatchers.io) {
        val userId = preferencesDataStore.getSnapshot().userId ?: DatabaseSeeder.DEFAULT_USER_ID
        val cartItems = cartDao.getCartItems(userId).map { it.toDomain(json) }
        if (cartItems.isEmpty()) {
            return@withContext ScheduleValidationResult(
                isValid = false,
                failureCategory = ScheduleFailureCategory.ITEM_UNAVAILABLE,
                errors = listOf("Your cart is empty. Add items before scheduling an order.")
            )
        }

        val orderItems = cartItems.map { item ->
            OrderItem(
                foodId = item.itemId,
                foodName = item.foodName,
                quantity = item.quantity,
                unitPrice = item.unitPrice + item.addonsTotalPerUnit,
                isVeg = item.isVeg,
                addonsSummary = item.selectedAddons.joinToString(", ") { it.name }
            )
        }

        val now = System.currentTimeMillis()
        val effectiveFirstSlot = resolveFirstExecutionTimestamp(
            scheduledAtUtcMs = params.scheduledAtUtcMs,
            rule = params.recurrenceRule,
            nowUtcMs = now
        )

        val availabilityRes = restaurantAvailabilityDataSource.checkAvailability(
            restaurantId = params.restaurantId,
            addressId = params.addressId,
            targetSlotUtcMs = effectiveFirstSlot
        )
        val availability = when (availabilityRes) {
            is AppResult.Success -> availabilityRes.data
            is AppResult.Error -> return@withContext ScheduleValidationResult(
                isValid = false,
                failureCategory = ScheduleFailureCategory.NETWORK_ERROR,
                errors = listOf(availabilityRes.error.userMessage)
            )
        }

        val operatingHours = RestaurantOperatingHours(
            openHour = availability.openHour,
            openMinute = availability.openMinute,
            closeHour = availability.closeHour,
            closeMinute = availability.closeMinute,
            preparationLeadMinutes = availability.preparationLeadMinutes
        )

        val errors = mutableListOf<String>()
        errors += calculateNextOccurrence.validateScheduleConfiguration(
            scheduledAtUtcMs = effectiveFirstSlot,
            rule = params.recurrenceRule,
            operatingHours = operatingHours,
            nowUtcMs = now
        )

        var failureCategory = ScheduleFailureCategory.NONE
        if (!availability.supportsDeliveryAddress || !availability.deliverySlotAvailable || !availability.deliveryCapacityAvailable) {
            errors += availability.unavailableReason ?: "Delivery is unavailable for the selected address or slot."
            failureCategory = ScheduleFailureCategory.DELIVERY_UNAVAILABLE
        }
        if (availability.isTemporarilyPaused) {
            errors += availability.unavailableReason ?: "Restaurant has temporarily paused new orders."
            if (failureCategory == ScheduleFailureCategory.NONE) {
                failureCategory = ScheduleFailureCategory.RESTAURANT_PAUSED
            }
        } else if (!availability.isOpen || !availability.isAcceptingOrders) {
            errors += availability.unavailableReason
                ?: "Restaurant is closed at the selected time (${operatingHours.formattedWindow()})."
            if (failureCategory == ScheduleFailureCategory.NONE) {
                failureCategory = ScheduleFailureCategory.RESTAURANT_CLOSED
            }
        }

        val unavailableNames = mutableListOf<String>()
        when (val itemsRes = foodItemAvailabilityDataSource.checkItemsAvailability(params.restaurantId, orderItems)) {
            is AppResult.Success -> {
                itemsRes.data.filter { !it.exists || !it.isAvailable || !it.customizationsValid }.forEach { bad ->
                    unavailableNames += bad.foodName
                    errors += bad.unavailableReason ?: "${bad.foodName} is currently unavailable."
                }
                if (unavailableNames.isNotEmpty() && failureCategory == ScheduleFailureCategory.NONE) {
                    failureCategory = ScheduleFailureCategory.ITEM_UNAVAILABLE
                }
            }
            is AppResult.Error -> {
                errors += itemsRes.error.userMessage
                failureCategory = ScheduleFailureCategory.NETWORK_ERROR
            }
        }

        val breakdown = when (
            val pricingRes = latestPricingDataSource.fetchLatestPricingQuote(
                restaurantId = params.restaurantId,
                items = orderItems,
                couponCode = params.couponCode
            )
        ) {
            is AppResult.Success -> CartPriceBreakdown(
                subtotal = pricingRes.data.subtotal,
                deliveryFee = pricingRes.data.deliveryFee,
                packagingCharge = pricingRes.data.packagingCharge,
                taxes = pricingRes.data.taxes,
                discount = pricingRes.data.discount,
                totalPayable = pricingRes.data.totalPayable,
                appliedCouponCode = params.couponCode.takeIf { pricingRes.data.couponStillValid }
            )
            is AppResult.Error -> {
                errors += pricingRes.error.userMessage
                null
            }
        }

        ScheduleValidationResult(
            isValid = errors.isEmpty(),
            restaurantOpen = availability.isOpen,
            restaurantAcceptingOrders = availability.isAcceptingOrders && !availability.isTemporarilyPaused,
            deliveryAvailable = availability.supportsDeliveryAddress && availability.deliverySlotAvailable,
            estimatedDeliveryMinutes = availability.estimatedDeliveryMinutes,
            unavailableItemNames = unavailableNames,
            recalculatedBreakdown = breakdown,
            failureCategory = failureCategory,
            errors = errors
        )
    }

    /** Saves a new schedule, clears its source cart, and registers its first WorkManager run. */
    override suspend fun createScheduledOrder(
        params: CreateScheduledOrderParams
    ): AppResult<ScheduledOrder> = withContext(dispatchers.io) {
        try {
            executionMutex.withLock {
                val userId = preferencesDataStore.getSnapshot().userId ?: DatabaseSeeder.DEFAULT_USER_ID
                val now = System.currentTimeMillis()
                val firstExecutionUtcMs = resolveFirstExecutionTimestamp(
                    scheduledAtUtcMs = params.scheduledAtUtcMs,
                    rule = params.recurrenceRule,
                    nowUtcMs = now
                )

                val cartItems = cartDao.getCartItems(userId).map { it.toDomain(json) }
                val creationKey = params.creationIdempotencyKey?.takeIf { it.isNotBlank() }
                    ?: buildCreationSignature(
                        userId = userId,
                        restaurantId = params.restaurantId,
                        addressId = params.addressId,
                        slotUtcMs = firstExecutionUtcMs,
                        recurrenceType = params.recurrenceRule.type.name,
                        itemSignature = cartItems.sortedBy { it.itemId }
                            .joinToString("|") { "${it.itemId}:${it.quantity}" }
                    )

                // Duplicate-schedule guard: if an identical active schedule already exists, return it idempotently
                val existingDuplicate = scheduledOrderDao.getActiveScheduleByCreationKey(userId, creationKey)
                if (existingDuplicate != null) {
                    return@withLock AppResult.Success(existingDuplicate.toDomain(json))
                }

                val validation = validateBeforeCreate(params)
                if (!validation.isValid) {
                    return@withLock AppResult.Error(
                        AppError.Validation(validation.errors.firstOrNull() ?: "Schedule validation failed.")
                    )
                }

                val restaurant = catalogDao.getRestaurant(params.restaurantId)
                    ?: return@withLock AppResult.Error(AppError.Validation("Restaurant not found."))
                val address = addressDao.getAddress(params.addressId)
                    ?: return@withLock AppResult.Error(AppError.Validation("Delivery address not found."))
                val breakdown = validation.recalculatedBreakdown
                    ?: return@withLock AppResult.Error(AppError.Validation("Could not verify latest order pricing."))

                val orderItems = cartItems.map { item ->
                    OrderItem(
                        foodId = item.itemId,
                        foodName = item.foodName,
                        quantity = item.quantity,
                        unitPrice = item.unitPrice + item.addonsTotalPerUnit,
                        isVeg = item.isVeg,
                        addonsSummary = item.selectedAddons.joinToString(", ") { it.name }
                    )
                }

                val scheduleId = "SCH-${UUID.randomUUID().toString().take(8).uppercase(Locale.US)}"
                val scheduledOrder = ScheduledOrder(
                    id = scheduleId,
                    userId = userId,
                    restaurantId = restaurant.id,
                    restaurantName = restaurant.name,
                    restaurantAddress = restaurant.address,
                    restaurantLat = restaurant.latitude,
                    restaurantLng = restaurant.longitude,
                    addressId = address.id,
                    deliveryAddress = "${address.line1}, ${address.city} - ${address.pincode}",
                    deliveryLat = address.latitude,
                    deliveryLng = address.longitude,
                    deliveryInstructions = address.deliveryInstructions,
                    items = orderItems,
                    authorizedSubtotal = breakdown.subtotal,
                    authorizedDeliveryFee = breakdown.deliveryFee,
                    authorizedPackagingCharge = breakdown.packagingCharge,
                    authorizedTaxes = breakdown.taxes,
                    authorizedDiscount = breakdown.discount,
                    authorizedTotalPayable = breakdown.totalPayable,
                    couponCode = breakdown.appliedCouponCode,
                    paymentMethod = params.paymentMethod,
                    priceConsentPolicy = params.priceConsentPolicy,
                    recurrenceRule = params.recurrenceRule,
                    nextExecutionAtUtcMs = firstExecutionUtcMs,
                    status = ScheduledOrderStatus.UPCOMING,
                    completedOccurrences = 0,
                    lastExecutedAtUtcMs = null,
                    lastGeneratedOrderId = null,
                    lastFailureCategory = ScheduleFailureCategory.NONE,
                    lastFailureReason = null,
                    failureAcknowledged = false,
                    creationIdempotencyKey = creationKey,
                    retryCount = 0,
                    createdAtMs = now,
                    updatedAtMs = now
                )

                scheduledOrderDao.upsertScheduledOrder(scheduledOrder.toEntity(json))
                cartDao.clearUserCart(userId)

                workScheduler.scheduleExecution(
                    scheduleId = scheduleId,
                    targetExecutionAtUtcMs = firstExecutionUtcMs,
                    expectedOccurrenceNumber = 1
                )

                AppResult.Success(scheduledOrder)
            }
        } catch (t: Throwable) {
            AppResult.Error(ErrorMapper.map(t))
        }
    }

    /** Updates an editable schedule and reschedules its next occurrence. */
    override suspend fun updateScheduledOrder(
        scheduleId: String,
        newScheduledAtUtcMs: Long,
        newRecurrenceRule: ScheduleRecurrenceRule
    ): AppResult<ScheduledOrder> = withContext(dispatchers.io) {
        try {
            executionMutex.withLock {
                val existing = scheduledOrderDao.getScheduledOrder(scheduleId)?.toDomain(json)
                    ?: return@withLock AppResult.Error(AppError.Validation("Scheduled order not found."))

                if (existing.status == ScheduledOrderStatus.PLACING_ORDER) {
                    return@withLock AppResult.Error(
                        AppError.Conflict("Order placement has already started with the restaurant and can no longer be rescheduled.")
                    )
                }
                if (existing.status == ScheduledOrderStatus.CANCELLED ||
                    existing.status == ScheduledOrderStatus.COMPLETED
                ) {
                    return@withLock AppResult.Error(
                        AppError.Conflict("Completed or cancelled schedules cannot be edited.")
                    )
                }

                val now = System.currentTimeMillis()
                val nextSlot = resolveFirstExecutionTimestamp(
                    scheduledAtUtcMs = newScheduledAtUtcMs,
                    rule = newRecurrenceRule,
                    nowUtcMs = now
                )
                val operatingHours = getRestaurantOperatingHours(existing.restaurantId)
                val configErrors = calculateNextOccurrence.validateScheduleConfiguration(
                    scheduledAtUtcMs = nextSlot,
                    rule = newRecurrenceRule,
                    operatingHours = operatingHours,
                    nowUtcMs = now
                )
                if (configErrors.isNotEmpty()) {
                    return@withLock AppResult.Error(AppError.Validation(configErrors.first()))
                }

                val updated = existing.copy(
                    recurrenceRule = newRecurrenceRule,
                    nextExecutionAtUtcMs = nextSlot,
                    status = ScheduledOrderStatus.UPCOMING,
                    lastFailureCategory = ScheduleFailureCategory.NONE,
                    lastFailureReason = null,
                    failureAcknowledged = true,
                    retryCount = 0,
                    updatedAtMs = now
                )
                scheduledOrderDao.upsertScheduledOrder(updated.toEntity(json))
                workScheduler.scheduleExecution(
                    scheduleId = updated.id,
                    targetExecutionAtUtcMs = updated.nextExecutionAtUtcMs,
                    expectedOccurrenceNumber = updated.completedOccurrences + 1
                )
                AppResult.Success(updated)
            }
        } catch (t: Throwable) {
            AppResult.Error(ErrorMapper.map(t))
        }
    }

    /** Cancels an eligible schedule and removes its pending WorkManager execution. */
    override suspend fun cancelScheduledOrder(
        scheduleId: String,
        reason: String
    ): AppResult<ScheduledOrder> = withContext(dispatchers.io) {
        try {
            executionMutex.withLock {
                val existing = scheduledOrderDao.getScheduledOrder(scheduleId)?.toDomain(json)
                    ?: return@withLock AppResult.Error(AppError.Validation("Scheduled order not found."))

                if (existing.status == ScheduledOrderStatus.PLACING_ORDER) {
                    return@withLock AppResult.Error(
                        AppError.Conflict("Order placement is already in progress and cannot be cancelled here.")
                    )
                }
                if (existing.status == ScheduledOrderStatus.COMPLETED) {
                    return@withLock AppResult.Error(
                        AppError.Conflict("This scheduled order has already been placed with the restaurant.")
                    )
                }

                val updated = existing.copy(
                    status = ScheduledOrderStatus.CANCELLED,
                    lastFailureReason = reason.takeIf { it.isNotBlank() } ?: existing.lastFailureReason,
                    failureAcknowledged = true,
                    updatedAtMs = System.currentTimeMillis()
                )
                scheduledOrderDao.upsertScheduledOrder(updated.toEntity(json))
                workScheduler.cancel(scheduleId)
                AppResult.Success(updated)
            }
        } catch (t: Throwable) {
            AppResult.Error(ErrorMapper.map(t))
        }
    }

    /** Pauses an upcoming schedule and cancels its pending worker. */
    override suspend fun pauseScheduledOrder(scheduleId: String): AppResult<ScheduledOrder> =
        withContext(dispatchers.io) {
            try {
                executionMutex.withLock {
                    val existing = scheduledOrderDao.getScheduledOrder(scheduleId)?.toDomain(json)
                        ?: return@withLock AppResult.Error(AppError.Validation("Scheduled order not found."))

                    if (existing.status != ScheduledOrderStatus.UPCOMING) {
                        return@withLock AppResult.Error(
                            AppError.Conflict("Only upcoming schedules can be paused.")
                        )
                    }

                    val updated = existing.copy(
                        status = ScheduledOrderStatus.PAUSED,
                        updatedAtMs = System.currentTimeMillis()
                    )
                    scheduledOrderDao.upsertScheduledOrder(updated.toEntity(json))
                    workScheduler.cancel(scheduleId)
                    AppResult.Success(updated)
                }
            } catch (t: Throwable) {
                AppResult.Error(ErrorMapper.map(t))
            }
        }

    /** Resumes a paused or retryable failed schedule and registers its next run. */
    override suspend fun resumeScheduledOrder(scheduleId: String): AppResult<ScheduledOrder> =
        withContext(dispatchers.io) {
            try {
                executionMutex.withLock {
                    val existing = scheduledOrderDao.getScheduledOrder(scheduleId)?.toDomain(json)
                        ?: return@withLock AppResult.Error(AppError.Validation("Scheduled order not found."))

                    if (existing.status != ScheduledOrderStatus.PAUSED &&
                        existing.status != ScheduledOrderStatus.FAILED
                    ) {
                        return@withLock AppResult.Error(
                            AppError.Conflict("Only paused or failed schedules can be resumed.")
                        )
                    }

                    val now = System.currentTimeMillis()
                    val nextSlot = if (existing.nextExecutionAtUtcMs > now) {
                        existing.nextExecutionAtUtcMs
                    } else if (existing.recurrenceRule.isRecurring) {
                        calculateNextOccurrence(
                            rule = existing.recurrenceRule,
                            baseScheduledAtUtcMs = existing.nextExecutionAtUtcMs,
                            afterUtcMs = now,
                            completedOccurrences = existing.completedOccurrences
                        ) ?: return@withLock AppResult.Error(
                            AppError.Validation("This recurring schedule has reached its end condition.")
                        )
                    } else {
                        return@withLock AppResult.Error(
                            AppError.Validation("The scheduled time has passed. Reschedule to pick a new future slot.")
                        )
                    }

                    val updated = existing.copy(
                        status = ScheduledOrderStatus.UPCOMING,
                        nextExecutionAtUtcMs = nextSlot,
                        lastFailureCategory = ScheduleFailureCategory.NONE,
                        lastFailureReason = null,
                        retryCount = 0,
                        updatedAtMs = now
                    )
                    scheduledOrderDao.upsertScheduledOrder(updated.toEntity(json))
                    workScheduler.scheduleExecution(
                        scheduleId = updated.id,
                        targetExecutionAtUtcMs = updated.nextExecutionAtUtcMs,
                        expectedOccurrenceNumber = updated.completedOccurrences + 1
                    )
                    AppResult.Success(updated)
                }
            } catch (t: Throwable) {
                AppResult.Error(ErrorMapper.map(t))
            }
        }

    /** Validates and executes one due occurrence, recording success, failure, or retry state. */
    override suspend fun executeDueOccurrence(
        scheduleId: String,
        expectedOccurrenceNumber: Int?,
        forceRunNowForDemo: Boolean
    ): AppResult<ScheduleExecutionOutcome> = withContext(dispatchers.io) {
        try {
            executionMutex.withLock {
                val schedule = scheduledOrderDao.getScheduledOrder(scheduleId)?.toDomain(json)
                    ?: return@withLock AppResult.Success(
                        ScheduleExecutionOutcome.Skipped("Scheduled order no longer exists.")
                    )

                if (schedule.status != ScheduledOrderStatus.UPCOMING) {
                    return@withLock AppResult.Success(
                        ScheduleExecutionOutcome.Skipped(
                            "Schedule is ${schedule.status.label} and will not execute."
                        )
                    )
                }

                val now = System.currentTimeMillis()
                if (!forceRunNowForDemo && schedule.nextExecutionAtUtcMs > now + CLOCK_SKEW_GRACE_MS) {
                    return@withLock AppResult.Success(
                        ScheduleExecutionOutcome.Skipped("Scheduled time has not been reached yet.")
                    )
                }

                val occurrenceNumber = expectedOccurrenceNumber ?: (schedule.completedOccurrences + 1)
                val idempotencyKey = "sched_${schedule.id}_occ_${occurrenceNumber}"

                // Strong duplicate execution guard: never create a second order for the same occurrence
                val existingExecution = scheduledOrderDao.getExecutionByIdempotencyKey(idempotencyKey)
                if (existingExecution != null &&
                    existingExecution.status == ScheduleExecutionStatus.SUCCEEDED.name
                ) {
                    return@withLock AppResult.Success(
                        ScheduleExecutionOutcome.Skipped(
                            "Occurrence #$occurrenceNumber already placed order ${existingExecution.generatedOrderId}."
                        )
                    )
                }

                // Transition explicitly to PLACING_ORDER so concurrent rescheduling/cancellation is blocked
                scheduledOrderDao.updateStatus(
                    scheduleId = schedule.id,
                    status = ScheduledOrderStatus.PLACING_ORDER.name,
                    updatedAtMs = now
                )

                // 1. Connectivity check (retryable transient failure)
                if (!connectivityObserver.isCurrentlyOnline()) {
                    return@withLock recordExecutionFailure(
                        schedule = schedule,
                        occurrenceNumber = occurrenceNumber,
                        idempotencyKey = idempotencyKey,
                        status = ScheduleExecutionStatus.RETRY_SCHEDULED,
                        failureCategory = ScheduleFailureCategory.NETWORK_ERROR,
                        failureReason = "Device was offline at scheduled time. Retry queued automatically.",
                        quotedTotal = schedule.authorizedTotalPayable,
                        paymentStatus = PaymentStatus.NOT_STARTED,
                        retryable = true,
                        now = now
                    )
                }

                // 2. Live restaurant availability & delivery slot validation
                val availabilityRes = restaurantAvailabilityDataSource.checkAvailability(
                    restaurantId = schedule.restaurantId,
                    addressId = schedule.addressId,
                    targetSlotUtcMs = if (forceRunNowForDemo) schedule.nextExecutionAtUtcMs else now
                )
                val availability = when (availabilityRes) {
                    is AppResult.Success -> availabilityRes.data
                    is AppResult.Error -> return@withLock recordExecutionFailure(
                        schedule = schedule,
                        occurrenceNumber = occurrenceNumber,
                        idempotencyKey = idempotencyKey,
                        status = if (availabilityRes.error.isRetryable) ScheduleExecutionStatus.RETRY_SCHEDULED else ScheduleExecutionStatus.FAILED_VALIDATION,
                        failureCategory = ScheduleFailureCategory.NETWORK_ERROR,
                        failureReason = availabilityRes.error.userMessage,
                        quotedTotal = schedule.authorizedTotalPayable,
                        paymentStatus = PaymentStatus.NOT_STARTED,
                        retryable = availabilityRes.error.isRetryable,
                        now = now
                    )
                }

                if (availability.isTemporarilyPaused) {
                    return@withLock recordExecutionFailure(
                        schedule = schedule,
                        occurrenceNumber = occurrenceNumber,
                        idempotencyKey = idempotencyKey,
                        status = ScheduleExecutionStatus.FAILED_VALIDATION,
                        failureCategory = ScheduleFailureCategory.RESTAURANT_PAUSED,
                        failureReason = availability.unavailableReason
                            ?: "${schedule.restaurantName} temporarily paused incoming orders.",
                        quotedTotal = schedule.authorizedTotalPayable,
                        paymentStatus = PaymentStatus.NOT_STARTED,
                        retryable = false,
                        now = now
                    )
                }
                if (!availability.isOpen || !availability.isAcceptingOrders) {
                    return@withLock recordExecutionFailure(
                        schedule = schedule,
                        occurrenceNumber = occurrenceNumber,
                        idempotencyKey = idempotencyKey,
                        status = ScheduleExecutionStatus.FAILED_VALIDATION,
                        failureCategory = ScheduleFailureCategory.RESTAURANT_CLOSED,
                        failureReason = availability.unavailableReason
                            ?: "${schedule.restaurantName} is currently closed.",
                        quotedTotal = schedule.authorizedTotalPayable,
                        paymentStatus = PaymentStatus.NOT_STARTED,
                        retryable = false,
                        now = now
                    )
                }
                if (!availability.supportsDeliveryAddress || !availability.deliveryCapacityAvailable) {
                    return@withLock recordExecutionFailure(
                        schedule = schedule,
                        occurrenceNumber = occurrenceNumber,
                        idempotencyKey = idempotencyKey,
                        status = ScheduleExecutionStatus.FAILED_VALIDATION,
                        failureCategory = ScheduleFailureCategory.DELIVERY_UNAVAILABLE,
                        failureReason = availability.unavailableReason
                            ?: "Delivery capacity is currently unavailable for your address.",
                        quotedTotal = schedule.authorizedTotalPayable,
                        paymentStatus = PaymentStatus.NOT_STARTED,
                        retryable = false,
                        now = now
                    )
                }

                // 3. Live food item availability validation
                val itemsRes = foodItemAvailabilityDataSource.checkItemsAvailability(
                    restaurantId = schedule.restaurantId,
                    items = schedule.items
                )
                val itemStatuses = when (itemsRes) {
                    is AppResult.Success -> itemsRes.data
                    is AppResult.Error -> return@withLock recordExecutionFailure(
                        schedule = schedule,
                        occurrenceNumber = occurrenceNumber,
                        idempotencyKey = idempotencyKey,
                        status = if (itemsRes.error.isRetryable) ScheduleExecutionStatus.RETRY_SCHEDULED else ScheduleExecutionStatus.FAILED_VALIDATION,
                        failureCategory = ScheduleFailureCategory.NETWORK_ERROR,
                        failureReason = itemsRes.error.userMessage,
                        quotedTotal = schedule.authorizedTotalPayable,
                        paymentStatus = PaymentStatus.NOT_STARTED,
                        retryable = itemsRes.error.isRetryable,
                        now = now
                    )
                }
                val unavailableItems = itemStatuses.filter { !it.exists || !it.isAvailable || !it.customizationsValid }
                if (unavailableItems.isNotEmpty()) {
                    val missingSummary = unavailableItems.joinToString(", ") { it.foodName }
                    return@withLock recordExecutionFailure(
                        schedule = schedule,
                        occurrenceNumber = occurrenceNumber,
                        idempotencyKey = idempotencyKey,
                        status = ScheduleExecutionStatus.FAILED_VALIDATION,
                        failureCategory = ScheduleFailureCategory.ITEM_UNAVAILABLE,
                        failureReason = "Unavailable item(s): $missingSummary. Order was not placed.",
                        quotedTotal = schedule.authorizedTotalPayable,
                        paymentStatus = PaymentStatus.NOT_STARTED,
                        retryable = false,
                        now = now
                    )
                }

                // 4. Live pricing & strict customer price-consent enforcement
                val pricingRes = latestPricingDataSource.fetchLatestPricingQuote(
                    restaurantId = schedule.restaurantId,
                    items = schedule.items,
                    couponCode = schedule.couponCode
                )
                val quote = when (pricingRes) {
                    is AppResult.Success -> pricingRes.data
                    is AppResult.Error -> return@withLock recordExecutionFailure(
                        schedule = schedule,
                        occurrenceNumber = occurrenceNumber,
                        idempotencyKey = idempotencyKey,
                        status = if (pricingRes.error.isRetryable) ScheduleExecutionStatus.RETRY_SCHEDULED else ScheduleExecutionStatus.FAILED_VALIDATION,
                        failureCategory = ScheduleFailureCategory.NETWORK_ERROR,
                        failureReason = pricingRes.error.userMessage,
                        quotedTotal = schedule.authorizedTotalPayable,
                        paymentStatus = PaymentStatus.NOT_STARTED,
                        retryable = pricingRes.error.isRetryable,
                        now = now
                    )
                }

                val allowedMaxTotal = when (schedule.priceConsentPolicy) {
                    SchedulePriceConsentPolicy.STRICT_NO_INCREASE -> schedule.authorizedTotalPayable + 0.01
                    SchedulePriceConsentPolicy.ALLOW_SMALL_VARIANCE -> schedule.authorizedTotalPayable * 1.05 + 0.01
                }
                if (quote.totalPayable > allowedMaxTotal) {
                    val reason = String.format(
                        Locale.US,
                        "Order total increased from ₹%.2f to ₹%.2f. Customer re-approval is required before charging.",
                        schedule.authorizedTotalPayable,
                        quote.totalPayable
                    )
                    return@withLock recordExecutionFailure(
                        schedule = schedule,
                        occurrenceNumber = occurrenceNumber,
                        idempotencyKey = idempotencyKey,
                        status = ScheduleExecutionStatus.FAILED_VALIDATION,
                        failureCategory = ScheduleFailureCategory.PRICE_CHANGED_REQUIRES_CONSENT,
                        failureReason = reason,
                        quotedTotal = quote.totalPayable,
                        paymentStatus = PaymentStatus.NOT_STARTED,
                        retryable = false,
                        now = now
                    )
                }

                // 5. Payment validation & authorization
                val paymentRes = paymentValidationDataSource.validateAndAuthorizePayment(
                    scheduleId = schedule.id,
                    occurrenceIdempotencyKey = idempotencyKey,
                    paymentMethod = schedule.paymentMethod.name,
                    amount = quote.totalPayable
                )
                val paymentAuth = when (paymentRes) {
                    is AppResult.Success -> paymentRes.data
                    is AppResult.Error -> return@withLock recordExecutionFailure(
                        schedule = schedule,
                        occurrenceNumber = occurrenceNumber,
                        idempotencyKey = idempotencyKey,
                        status = if (paymentRes.error.isRetryable) ScheduleExecutionStatus.RETRY_SCHEDULED else ScheduleExecutionStatus.FAILED_PAYMENT,
                        failureCategory = ScheduleFailureCategory.PAYMENT_FAILED,
                        failureReason = paymentRes.error.userMessage,
                        quotedTotal = quote.totalPayable,
                        paymentStatus = PaymentStatus.FAILED,
                        retryable = paymentRes.error.isRetryable,
                        now = now
                    )
                }

                if (!paymentAuth.approved) {
                    return@withLock recordExecutionFailure(
                        schedule = schedule,
                        occurrenceNumber = occurrenceNumber,
                        idempotencyKey = idempotencyKey,
                        status = if (paymentAuth.retryable) ScheduleExecutionStatus.RETRY_SCHEDULED else ScheduleExecutionStatus.FAILED_PAYMENT,
                        failureCategory = ScheduleFailureCategory.PAYMENT_FAILED,
                        failureReason = paymentAuth.failureReason ?: "Payment authorization failed.",
                        quotedTotal = quote.totalPayable,
                        paymentStatus = PaymentStatus.FAILED,
                        retryable = paymentAuth.retryable,
                        now = now
                    )
                }

                // 6. Execute idempotent order creation
                val refreshedItems = schedule.items.map { item ->
                    val latestPrice = quote.itemPricesById[item.foodId]
                    if (latestPrice != null) item.copy(unitPrice = latestPrice) else item
                }
                val execRequest = ExecuteScheduledOccurrenceRequestDto(
                    scheduleId = schedule.id,
                    occurrenceId = "occ_${schedule.id}_$occurrenceNumber",
                    idempotencyKey = idempotencyKey,
                    customerId = schedule.userId,
                    restaurantId = schedule.restaurantId,
                    restaurantName = schedule.restaurantName,
                    restaurantAddress = schedule.restaurantAddress,
                    restaurantLat = schedule.restaurantLat,
                    restaurantLng = schedule.restaurantLng,
                    deliveryAddress = schedule.deliveryAddress,
                    deliveryLat = schedule.deliveryLat,
                    deliveryLng = schedule.deliveryLng,
                    deliveryInstructions = schedule.deliveryInstructions,
                    items = refreshedItems,
                    subtotal = quote.subtotal,
                    deliveryFee = quote.deliveryFee,
                    packagingCharge = quote.packagingCharge,
                    taxes = quote.taxes,
                    discount = quote.discount,
                    totalPayable = quote.totalPayable,
                    couponCode = schedule.couponCode.takeIf { quote.couponStillValid },
                    paymentMethod = schedule.paymentMethod.name,
                    paymentStatus = paymentAuth.paymentStatus
                )

                val execRes = executionDataSource.executeOccurrence(execRequest)
                val execResponse = when (execRes) {
                    is AppResult.Success -> execRes.data
                    is AppResult.Error -> return@withLock recordExecutionFailure(
                        schedule = schedule,
                        occurrenceNumber = occurrenceNumber,
                        idempotencyKey = idempotencyKey,
                        status = if (execRes.error.isRetryable) ScheduleExecutionStatus.RETRY_SCHEDULED else ScheduleExecutionStatus.FAILED_VALIDATION,
                        failureCategory = ScheduleFailureCategory.NETWORK_ERROR,
                        failureReason = execRes.error.userMessage,
                        quotedTotal = quote.totalPayable,
                        paymentStatus = runCatching { PaymentStatus.valueOf(paymentAuth.paymentStatus) }
                            .getOrDefault(PaymentStatus.NOT_STARTED),
                        retryable = execRes.error.isRetryable,
                        now = now
                    )
                }

                // 7. Persist generated order into Room & feed into existing real-time tracking workflow
                val orderEntity = execResponse.order.toEntity(json, now, schedule.userId)
                orderDao.upsertOrder(orderEntity)
                orderDao.insertTimelineEvent(
                    OrderTimelineEventEntity(
                        id = "evt_${orderEntity.id}_scheduled_placed",
                        orderId = orderEntity.id,
                        sequenceNumber = 1L,
                        eventType = "ORDER_PLACED",
                        title = "Scheduled Order Placed",
                        description = "Scheduled order #${schedule.id} (Occurrence #$occurrenceNumber) placed with ${orderEntity.restaurantName}.",
                        timestampMs = now
                    )
                )

                val newCompletedCount = schedule.completedOccurrences + 1
                val nextUtcMs = calculateNextOccurrence(
                    rule = schedule.recurrenceRule,
                    baseScheduledAtUtcMs = schedule.nextExecutionAtUtcMs,
                    afterUtcMs = maxOf(now, schedule.nextExecutionAtUtcMs),
                    completedOccurrences = newCompletedCount
                )

                val updatedSchedule = schedule.copy(
                    status = if (nextUtcMs != null) ScheduledOrderStatus.UPCOMING else ScheduledOrderStatus.COMPLETED,
                    nextExecutionAtUtcMs = nextUtcMs ?: schedule.nextExecutionAtUtcMs,
                    completedOccurrences = newCompletedCount,
                    lastExecutedAtUtcMs = now,
                    lastGeneratedOrderId = orderEntity.id,
                    lastFailureCategory = ScheduleFailureCategory.NONE,
                    lastFailureReason = null,
                    retryCount = 0,
                    updatedAtMs = now
                )

                val executionRecord = ScheduledOrderExecution(
                    id = "exec_${UUID.randomUUID().toString().take(8)}",
                    scheduleId = schedule.id,
                    occurrenceNumber = occurrenceNumber,
                    idempotencyKey = idempotencyKey,
                    scheduledForUtcMs = schedule.nextExecutionAtUtcMs,
                    executedAtUtcMs = now,
                    status = ScheduleExecutionStatus.SUCCEEDED,
                    failureCategory = ScheduleFailureCategory.NONE,
                    failureReason = null,
                    generatedOrderId = orderEntity.id,
                    quotedTotalPayable = quote.totalPayable,
                    authorizedTotalPayable = schedule.authorizedTotalPayable,
                    paymentStatus = runCatching { PaymentStatus.valueOf(paymentAuth.paymentStatus) }
                        .getOrDefault(PaymentStatus.SUCCESS),
                    retryCount = schedule.retryCount
                )

                scheduledOrderDao.recordExecutionAndUpdateSchedule(
                    schedule = updatedSchedule.toEntity(json),
                    execution = executionRecord.toEntity()
                )

                // Chain next WorkManager job if recurring, otherwise cancel work
                if (nextUtcMs != null) {
                    workScheduler.scheduleExecution(
                        scheduleId = schedule.id,
                        targetExecutionAtUtcMs = nextUtcMs,
                        expectedOccurrenceNumber = newCompletedCount + 1
                    )
                } else {
                    workScheduler.cancel(schedule.id)
                }

                // Immediately connect the generated order to FoodFlow's centralized real-time tracking!
                orderTrackingRepository.startTracking(orderEntity.id)

                AppResult.Success(
                    ScheduleExecutionOutcome.Succeeded(
                        generatedOrder = orderEntity.toDomain(json, null),
                        execution = executionRecord
                    )
                )
            }
        } catch (t: Throwable) {
            runCatching {
                val stuck = scheduledOrderDao.getScheduledOrder(scheduleId)
                if (stuck != null && stuck.status == ScheduledOrderStatus.PLACING_ORDER.name) {
                    scheduledOrderDao.updateStatus(
                        scheduleId = scheduleId,
                        status = ScheduledOrderStatus.UPCOMING.name,
                        updatedAtMs = System.currentTimeMillis()
                    )
                }
            }
            AppResult.Error(ErrorMapper.map(t))
        }
    }

    /** Executes overdue schedules and restores worker registrations for future ones. */
    override suspend fun reconcileOverdueSchedules(): Int = withContext(dispatchers.io) {
        val now = System.currentTimeMillis()
        val upcoming = scheduledOrderDao.getAllUpcomingSchedules()
        var reconciledCount = 0
        for (entity in upcoming) {
            if (entity.nextExecutionAtUtcMs <= now) {
                executeDueOccurrence(
                    scheduleId = entity.id,
                    expectedOccurrenceNumber = entity.completedOccurrences + 1,
                    forceRunNowForDemo = false
                )
                reconciledCount += 1
            } else {
                // Re-ensure WorkManager registration survives app updates / process kills
                workScheduler.scheduleExecution(
                    scheduleId = entity.id,
                    targetExecutionAtUtcMs = entity.nextExecutionAtUtcMs,
                    expectedOccurrenceNumber = entity.completedOccurrences + 1
                )
            }
        }
        reconciledCount
    }

    /** Persists a failed attempt and decides whether another scheduled retry is allowed. */
    private suspend fun recordExecutionFailure(
        schedule: ScheduledOrder,
        occurrenceNumber: Int,
        idempotencyKey: String,
        status: ScheduleExecutionStatus,
        failureCategory: ScheduleFailureCategory,
        failureReason: String,
        quotedTotal: Double,
        paymentStatus: PaymentStatus,
        retryable: Boolean,
        now: Long
    ): AppResult<ScheduleExecutionOutcome> {
        val nextRetryCount = schedule.retryCount + 1
        val willRetry = retryable && nextRetryCount < MAX_EXECUTION_RETRIES
        val newScheduleStatus = if (willRetry) {
            ScheduledOrderStatus.UPCOMING
        } else {
            ScheduledOrderStatus.FAILED
        }

        val updatedSchedule = schedule.copy(
            status = newScheduleStatus,
            lastExecutedAtUtcMs = now,
            lastFailureCategory = failureCategory,
            lastFailureReason = failureReason,
            failureAcknowledged = willRetry,
            retryCount = nextRetryCount,
            updatedAtMs = now
        )

        val executionRecord = ScheduledOrderExecution(
            id = "exec_${UUID.randomUUID().toString().take(8)}",
            scheduleId = schedule.id,
            occurrenceNumber = occurrenceNumber,
            idempotencyKey = idempotencyKey,
            scheduledForUtcMs = schedule.nextExecutionAtUtcMs,
            executedAtUtcMs = now,
            status = if (willRetry) ScheduleExecutionStatus.RETRY_SCHEDULED else status,
            failureCategory = failureCategory,
            failureReason = failureReason,
            generatedOrderId = null,
            quotedTotalPayable = quotedTotal,
            authorizedTotalPayable = schedule.authorizedTotalPayable,
            paymentStatus = paymentStatus,
            retryCount = nextRetryCount
        )

        scheduledOrderDao.recordExecutionAndUpdateSchedule(
            schedule = updatedSchedule.toEntity(json),
            execution = executionRecord.toEntity()
        )

        if (!willRetry) {
            workScheduler.cancel(schedule.id)
        }

        return AppResult.Success(
            ScheduleExecutionOutcome.Failed(
                execution = executionRecord,
                shouldRetry = willRetry
            )
        )
    }

    /** Builds a stable key used to prevent duplicate schedules for identical requests. */
    private fun buildCreationSignature(
        userId: String,
        restaurantId: String,
        addressId: String,
        slotUtcMs: Long,
        recurrenceType: String,
        itemSignature: String
    ): String {
        val roundedMinute = slotUtcMs / 60_000L
        return "$userId|$restaurantId|$addressId|$roundedMinute|$recurrenceType|$itemSignature"
    }

    /** Chooses the first future execution time for a one-time or recurring schedule. */
    private fun resolveFirstExecutionTimestamp(
        scheduledAtUtcMs: Long,
        rule: ScheduleRecurrenceRule,
        nowUtcMs: Long
    ): Long {
        if (!rule.isRecurring) return scheduledAtUtcMs
        return calculateNextOccurrence(
            rule = rule,
            baseScheduledAtUtcMs = scheduledAtUtcMs,
            afterUtcMs = nowUtcMs,
            completedOccurrences = 0
        ) ?: scheduledAtUtcMs
    }

    companion object {
        private const val MAX_EXECUTION_RETRIES = 3
        private const val CLOCK_SKEW_GRACE_MS = 10_000L
    }
}
