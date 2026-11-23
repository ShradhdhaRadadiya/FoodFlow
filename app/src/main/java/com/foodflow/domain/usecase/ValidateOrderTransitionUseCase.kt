package com.foodflow.domain.usecase

import com.foodflow.domain.model.OrderStatus
import javax.inject.Inject

class ValidateOrderTransitionUseCase @Inject constructor() {
    /** Checks whether an order can move from its current status to the requested status. */
    operator fun invoke(
        currentStatus: OrderStatus,
        targetStatus: OrderStatus,
        strictSequential: Boolean = false
    ): Boolean {
        return if (strictSequential) {
            currentStatus.canAdvanceSequentiallyTo(targetStatus)
        } else {
            currentStatus.canTransitionTo(targetStatus)
        }
    }
}
