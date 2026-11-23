package com.foodflow.domain.repository

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.SupportIssueType
import com.foodflow.domain.model.SupportTicket
import kotlinx.coroutines.flow.Flow

interface SupportRepository {
    /** Streams the user's saved support requests. */
    fun observeTickets(): Flow<List<SupportTicket>>
    /** Creates a support request, optionally linked to an order. */
    suspend fun createTicket(
        orderId: String?,
        issueType: SupportIssueType,
        description: String
    ): AppResult<SupportTicket>
}
