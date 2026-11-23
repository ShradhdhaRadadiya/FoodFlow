package com.foodflow.domain.usecase.order

import com.foodflow.domain.model.CheckoutAttempt
import com.foodflow.domain.repository.OrderRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveActiveCheckoutAttemptUseCase @Inject constructor(
    private val orderRepository: OrderRepository
) {
    /** Provides live updates for the current unfinished checkout attempt, if any. */
    operator fun invoke(): Flow<CheckoutAttempt?> = orderRepository.observeActiveCheckoutAttempt()
}
