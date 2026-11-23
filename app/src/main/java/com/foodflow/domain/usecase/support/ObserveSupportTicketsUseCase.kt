package com.foodflow.domain.usecase.support

import com.foodflow.domain.model.SupportTicket
import com.foodflow.domain.repository.SupportRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveSupportTicketsUseCase @Inject constructor(
    private val supportRepository: SupportRepository
) {
    /** Provides live updates to the user's support requests. */
    operator fun invoke(): Flow<List<SupportTicket>> = supportRepository.observeTickets()
}
