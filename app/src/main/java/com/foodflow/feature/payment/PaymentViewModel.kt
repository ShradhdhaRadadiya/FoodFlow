package com.foodflow.feature.payment

import androidx.lifecycle.viewModelScope
import com.foodflow.core.common.AppResult
import com.foodflow.core.mvi.MviViewModel
import com.foodflow.domain.model.PaymentStatus
import com.foodflow.domain.usecase.order.ObserveOrderUseCase
import com.foodflow.domain.usecase.payment.ProcessPaymentUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class PaymentViewModel @Inject constructor(
    private val observeOrder: ObserveOrderUseCase,
    private val processPayment: ProcessPaymentUseCase
) : MviViewModel<PaymentUiState, PaymentIntent, PaymentEffect>(PaymentUiState()) {

    private var orderJob: Job? = null
    private var currentCallbackId: String = "cb_${UUID.randomUUID().toString().take(10)}"

    /** Initializes payment details or starts payment when requested. */
    override fun onIntent(intent: PaymentIntent) {
        when (intent) {
            is PaymentIntent.Initialize -> initialize(intent.orderId, intent.idempotencyKey)
            PaymentIntent.PayNow -> submitPayment()
        }
    }

    /** Observes the order and saves the retry-safe key used for payment. */
    private fun initialize(orderId: String, idempotencyKey: String) {
        updateState { copy(idempotencyKey = idempotencyKey) }
        orderJob?.cancel()
        orderJob = observeOrder(orderId)
            .onEach { order ->
                if (order != null) {
                    updateState { copy(order = order, paymentStatus = order.paymentStatus) }
                }
            }
            .launchIn(viewModelScope)
    }

    /** Processes payment and routes or reports status based on the result. */
    private fun submitPayment() {
        val order = state.value.order ?: return
        val key = state.value.idempotencyKey
        if (state.value.isProcessing) return

        viewModelScope.launch {
            updateState { copy(isProcessing = true, errorMessage = null) }
            when (
                val res = processPayment(
                    orderId = order.id,
                    idempotencyKey = key,
                    method = order.paymentMethod,
                    amount = order.totalPayable,
                    callbackId = currentCallbackId
                )
            ) {
                is AppResult.Success -> {
                    updateState { copy(isProcessing = false, paymentStatus = res.data) }
                    when (res.data) {
                        PaymentStatus.SUCCESS, PaymentStatus.COD_DUE -> {
                            emitEffect(PaymentEffect.NavigateToConfirmation(order.id))
                        }
                        PaymentStatus.PENDING -> {
                            emitEffect(PaymentEffect.ShowMessage("Payment is pending confirmation."))
                        }
                        PaymentStatus.FAILED -> {
                            currentCallbackId = "cb_${UUID.randomUUID().toString().take(10)}"
                            updateState {
                                copy(errorMessage = "Payment failed. You can try again.")
                            }
                        }
                        else -> Unit
                    }
                }
                is AppResult.Error -> {
                    updateState { copy(isProcessing = false, errorMessage = res.error.userMessage) }
                }
            }
        }
    }
}
