package com.foodflow.data.repository

import com.foodflow.core.common.AppResult
import com.foodflow.core.common.DispatcherProvider
import com.foodflow.core.common.ErrorMapper
import com.foodflow.core.datastore.UserPreferencesDataStore
import com.foodflow.data.local.dao.CartDao
import com.foodflow.data.local.dao.OrderDao
import com.foodflow.data.local.database.DatabaseSeeder
import com.foodflow.data.local.entity.OrderTimelineEventEntity
import com.foodflow.data.remote.api.PaymentRemoteDataSource
import com.foodflow.data.remote.dto.PaymentRequestDto
import com.foodflow.domain.model.CheckoutAttemptStatus
import com.foodflow.domain.model.PaymentMethod
import com.foodflow.domain.model.PaymentStatus
import com.foodflow.domain.repository.PaymentRepository
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PaymentRepositoryImpl @Inject constructor(
    private val paymentRemote: PaymentRemoteDataSource,
    private val orderDao: OrderDao,
    private val cartDao: CartDao,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val dispatchers: DispatcherProvider
) : PaymentRepository {

    /** Sends a payment request and persists the resulting order and checkout status. */
    override suspend fun processPayment(
        orderId: String,
        idempotencyKey: String,
        method: PaymentMethod,
        amount: Double,
        callbackId: String
    ): AppResult<PaymentStatus> = withContext(dispatchers.io) {
        try {
            val request = PaymentRequestDto(
                orderId = orderId,
                idempotencyKey = idempotencyKey,
                callbackId = callbackId,
                method = method.name,
                amount = amount
            )
            when (val res = paymentRemote.processPayment(request)) {
                is AppResult.Success -> {
                    val paymentStatus = runCatching { PaymentStatus.valueOf(res.data.paymentStatus) }
                        .getOrDefault(PaymentStatus.PENDING)
                    updateLocalOrderAndAttempt(
                        orderId = orderId,
                        idempotencyKey = idempotencyKey,
                        paymentStatus = paymentStatus,
                        txId = res.data.transactionId
                    )
                    AppResult.Success(paymentStatus)
                }
                is AppResult.Error -> res
            }
        } catch (t: Throwable) {
            AppResult.Error(ErrorMapper.map(t))
        }
    }

    /** Updates local payment state, adds a timeline event, and clears the cart on success. */
    private suspend fun updateLocalOrderAndAttempt(
        orderId: String,
        idempotencyKey: String,
        paymentStatus: PaymentStatus,
        txId: String
    ) {
        val now = System.currentTimeMillis()
        orderDao.getOrder(orderId)?.let { existing ->
            orderDao.upsertOrder(existing.copy(paymentStatus = paymentStatus.name, updatedAtMs = now))
            if (paymentStatus == PaymentStatus.SUCCESS || paymentStatus == PaymentStatus.COD_DUE) {
                orderDao.insertTimelineEvent(
                    OrderTimelineEventEntity(
                        id = "evt_${orderId}_pay_confirmed",
                        orderId = orderId,
                        sequenceNumber = 2L,
                        eventType = "PAYMENT_CONFIRMED",
                        title = if (paymentStatus == PaymentStatus.COD_DUE) "Cash on Delivery Confirmed" else "Payment Confirmed",
                        description = "Reference $txId verified.",
                        timestampMs = now
                    )
                )
            }
        }

        orderDao.getCheckoutAttempt(idempotencyKey)?.let { attempt ->
            val nextStatus = when (paymentStatus) {
                PaymentStatus.SUCCESS, PaymentStatus.COD_DUE -> CheckoutAttemptStatus.COMPLETED
                PaymentStatus.PENDING -> CheckoutAttemptStatus.PAYMENT_PENDING
                else -> CheckoutAttemptStatus.FAILED
            }
            orderDao.upsertCheckoutAttempt(
                attempt.copy(
                    paymentTransactionId = txId,
                    status = nextStatus.name,
                    updatedAtMs = now
                )
            )
            if (nextStatus == CheckoutAttemptStatus.COMPLETED) {
                val userId = preferencesDataStore.getSnapshot().userId ?: DatabaseSeeder.DEFAULT_USER_ID
                cartDao.clearUserCart(userId)
            }
        }
    }
}
