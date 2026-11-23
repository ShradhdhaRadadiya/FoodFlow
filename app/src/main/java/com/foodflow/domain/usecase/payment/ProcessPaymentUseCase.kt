package com.foodflow.domain.usecase.payment

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.PaymentMethod
import com.foodflow.domain.model.PaymentStatus
import com.foodflow.domain.repository.PaymentRepository
import javax.inject.Inject

class ProcessPaymentUseCase @Inject constructor(
    private val paymentRepository: PaymentRepository
) {
    /** Processes payment for an order using its retry-safe key and callback ID. */
    suspend operator fun invoke(
        orderId: String,
        idempotencyKey: String,
        method: PaymentMethod,
        amount: Double,
        callbackId: String
    ): AppResult<PaymentStatus> = paymentRepository.processPayment(
        orderId = orderId,
        idempotencyKey = idempotencyKey,
        method = method,
        amount = amount,
        callbackId = callbackId
    )
}
