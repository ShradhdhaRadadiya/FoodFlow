package com.foodflow.feature.payment

import com.foodflow.core.mvi.MviEffect
import com.foodflow.core.mvi.MviIntent
import com.foodflow.core.mvi.MviState
import com.foodflow.domain.model.Order
import com.foodflow.domain.model.PaymentStatus

data class PaymentUiState(
    val order: Order? = null,
    val idempotencyKey: String = "",
    val isProcessing: Boolean = false,
    val paymentStatus: PaymentStatus = PaymentStatus.NOT_STARTED,
    val errorMessage: String? = null
) : MviState

sealed interface PaymentIntent : MviIntent {
    data class Initialize(val orderId: String, val idempotencyKey: String) : PaymentIntent
    data object PayNow : PaymentIntent
}

sealed interface PaymentEffect : MviEffect {
    data class NavigateToConfirmation(val orderId: String) : PaymentEffect
    data class ShowMessage(val message: String) : PaymentEffect
}
