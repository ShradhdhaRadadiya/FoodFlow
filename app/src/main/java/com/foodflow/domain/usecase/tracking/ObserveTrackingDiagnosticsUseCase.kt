package com.foodflow.domain.usecase.tracking

import com.foodflow.domain.model.TrackingDiagnosticInfo
import com.foodflow.domain.repository.OrderTrackingRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class ObserveTrackingDiagnosticsUseCase @Inject constructor(
    private val orderTrackingRepository: OrderTrackingRepository
) {
    /** Provides tracking diagnostics such as connection and event status. */
    operator fun invoke(): StateFlow<TrackingDiagnosticInfo> = orderTrackingRepository.diagnostics
}
