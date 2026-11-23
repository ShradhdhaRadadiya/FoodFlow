package com.foodflow.domain.usecase.support

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.SupportIssueType
import com.foodflow.domain.model.SupportTicket
import com.foodflow.domain.repository.SupportRepository
import javax.inject.Inject

class CreateSupportTicketUseCase @Inject constructor(
    private val supportRepository: SupportRepository
) {
    /** Creates a support request, optionally linked to an order. */
    suspend operator fun invoke(
        orderId: String?,
        issueType: SupportIssueType,
        description: String
    ): AppResult<SupportTicket> = supportRepository.createTicket(orderId, issueType, description)
}
