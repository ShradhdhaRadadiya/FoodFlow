package com.foodflow.feature.orderconfirmation

import androidx.lifecycle.viewModelScope
import com.foodflow.core.mvi.MviViewModel
import com.foodflow.domain.usecase.order.ObserveOrderUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@HiltViewModel
class OrderConfirmationViewModel @Inject constructor(
    private val observeOrder: ObserveOrderUseCase
) : MviViewModel<OrderConfirmationUiState, OrderConfirmationIntent, OrderConfirmationEffect>(
    OrderConfirmationUiState()
) {
    private var loadJob: Job? = null

    /** Starts observing the order shown on the confirmation screen. */
    override fun onIntent(intent: OrderConfirmationIntent) {
        when (intent) {
            is OrderConfirmationIntent.Load -> {
                loadJob?.cancel()
                loadJob = observeOrder(intent.orderId)
                    .onEach { order -> updateState { copy(order = order) } }
                    .launchIn(viewModelScope)
            }
        }
    }
}
