package com.foodflow.domain.usecase.scheduled

import com.foodflow.domain.model.ScheduledOrderFailurePopup
import com.foodflow.domain.repository.ScheduledOrderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Observes unacknowledged failed scheduled orders so the UI can display a clear in-app popup
 * when the customer opens the app or Order History, while keeping failures persisted in history.
 */
class ObserveScheduledFailureAlertsUseCase @Inject constructor(
    private val scheduledOrderRepository: ScheduledOrderRepository,
    private val buildScheduledFailurePopup: BuildScheduledFailurePopupUseCase
) {
    /** Provides popup details for scheduled orders that have failed and need attention. */
    operator fun invoke(): Flow<List<ScheduledOrderFailurePopup>> =
        scheduledOrderRepository.observeUnacknowledgedFailedSchedules()
            .map { failedSchedules -> failedSchedules.map(buildScheduledFailurePopup::invoke) }
}
