package com.foodflow.domain.repository

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.PaymentMethod
import com.foodflow.domain.model.PaymentStatus

interface PaymentRepository {
    /** Processes payment once for the specified order and retry-safe attempt. */
    suspend fun processPayment(
        orderId: String,
        idempotencyKey: String,
        method: PaymentMethod,
        amount: Double,
        callbackId: String
    ): AppResult<PaymentStatus>
}
