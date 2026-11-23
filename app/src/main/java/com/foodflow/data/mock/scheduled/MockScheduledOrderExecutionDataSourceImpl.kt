package com.foodflow.data.mock.scheduled

import com.foodflow.core.common.AppResult
import com.foodflow.data.remote.api.OrderRemoteDataSource
import com.foodflow.data.remote.api.ScheduledOrderExecutionDataSource
import com.foodflow.data.remote.dto.CreateOrderRequestDto
import com.foodflow.data.remote.dto.ExecuteScheduledOccurrenceRequestDto
import com.foodflow.data.remote.dto.ExecuteScheduledOccurrenceResponseDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Replaceable mock implementation for idempotent scheduled occurrence execution.
 * Delegates order creation through [OrderRemoteDataSource] with the occurrence's deterministic
 * [ExecuteScheduledOccurrenceRequestDto.idempotencyKey] so retries or concurrent worker runs
 * can never create duplicate orders.
 */
@Singleton
class MockScheduledOrderExecutionDataSourceImpl @Inject constructor(
    private val orderRemote: OrderRemoteDataSource
) : ScheduledOrderExecutionDataSource {

    private val executionLock = Mutex()

    /** Creates or recovers an order for one scheduled occurrence without duplicates. */
    override suspend fun executeOccurrence(
        request: ExecuteScheduledOccurrenceRequestDto
    ): AppResult<ExecuteScheduledOccurrenceResponseDto> = executionLock.withLock {
        val existing = orderRemote.getOrderByIdempotencyKey(request.idempotencyKey)
        if (existing is AppResult.Success && existing.data != null) {
            return@withLock AppResult.Success(
                ExecuteScheduledOccurrenceResponseDto(
                    occurrenceId = request.occurrenceId,
                    idempotencyKey = request.idempotencyKey,
                    order = existing.data.copy(paymentStatus = request.paymentStatus),
                    duplicateIgnored = true
                )
            )
        }

        val createReq = CreateOrderRequestDto(
            idempotencyKey = request.idempotencyKey,
            userId = request.customerId,
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
            paymentMethod = request.paymentMethod
        )

        when (val created = orderRemote.createOrder(createReq)) {
            is AppResult.Success -> AppResult.Success(
                ExecuteScheduledOccurrenceResponseDto(
                    occurrenceId = request.occurrenceId,
                    idempotencyKey = request.idempotencyKey,
                    order = created.data.copy(paymentStatus = request.paymentStatus),
                    duplicateIgnored = false
                )
            )
            is AppResult.Error -> created
        }
    }
}
