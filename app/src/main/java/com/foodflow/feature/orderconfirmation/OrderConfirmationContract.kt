package com.foodflow.feature.orderconfirmation

import com.foodflow.core.mvi.MviEffect
import com.foodflow.core.mvi.MviIntent
import com.foodflow.core.mvi.MviState
import com.foodflow.domain.model.Order

data class OrderConfirmationUiState(
    val order: Order? = null
) : MviState

sealed interface OrderConfirmationIntent : MviIntent {
    data class Load(val orderId: String) : OrderConfirmationIntent
}

sealed interface OrderConfirmationEffect : MviEffect
