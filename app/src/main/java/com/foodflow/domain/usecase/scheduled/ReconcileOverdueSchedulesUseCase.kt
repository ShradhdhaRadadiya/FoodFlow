package com.foodflow.domain.usecase.scheduled

import com.foodflow.domain.repository.ScheduledOrderRepository
import javax.inject.Inject

class ReconcileOverdueSchedulesUseCase @Inject constructor(
    private val scheduledOrderRepository: ScheduledOrderRepository
) {
    /** Checks overdue schedules and processes those that need recovery after a delay or restart. */
    suspend operator fun invoke(): Int =
        scheduledOrderRepository.reconcileOverdueSchedules()
}
