package com.foodflow.data.repository

import com.foodflow.core.common.AppError
import com.foodflow.core.common.AppResult
import com.foodflow.core.common.DispatcherProvider
import com.foodflow.core.common.ErrorMapper
import com.foodflow.core.datastore.UserPreferencesDataStore
import com.foodflow.data.local.dao.SupportTicketDao
import com.foodflow.data.local.database.DatabaseSeeder
import com.foodflow.data.local.entity.SupportTicketEntity
import com.foodflow.data.local.mapper.toDomain
import com.foodflow.domain.model.SupportIssueType
import com.foodflow.domain.model.SupportTicket
import com.foodflow.domain.repository.SupportRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SupportRepositoryImpl @Inject constructor(
    private val supportTicketDao: SupportTicketDao,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val dispatchers: DispatcherProvider
) : SupportRepository {

    /** Streams the current user's saved support tickets as domain models. */
    override fun observeTickets(): Flow<List<SupportTicket>> =
        preferencesDataStore.preferences.flatMapLatest { prefs ->
            val userId = prefs.userId ?: DatabaseSeeder.DEFAULT_USER_ID
            supportTicketDao.observeTickets(userId).map { list -> list.map { it.toDomain() } }
        }

    /** Validates and saves a support request with its generated resolution note. */
    override suspend fun createTicket(
        orderId: String?,
        issueType: SupportIssueType,
        description: String
    ): AppResult<SupportTicket> = withContext(dispatchers.io) {
        try {
            if (description.isBlank()) {
                return@withContext AppResult.Error(AppError.Validation("Please describe the issue briefly."))
            }
            val userId = preferencesDataStore.getSnapshot().userId ?: DatabaseSeeder.DEFAULT_USER_ID
            val resolution = when (issueType) {
                SupportIssueType.MISSING_ITEM -> "Instant ₹120 FoodFlow credit queued for verification."
                SupportIssueType.WRONG_ITEM -> "Quality team notified; replacement or refund initiated."
                SupportIssueType.DELAYED_DELIVERY -> "Live dispatch escalated; priority rider tracking active."
                SupportIssueType.ORDER_CANCELLATION -> "Cancellation request reviewed under kitchen preparation policy."
                SupportIssueType.PAYMENT_DEDUCTED -> "Payment ledger verified; auto-reversal completes in 2-3 business days."
                SupportIssueType.REFUND_STATUS -> "Refund reference active with your issuing bank (2-5 working days)."
            }
            val entity = SupportTicketEntity(
                id = "SUP-${System.currentTimeMillis().toString().takeLast(6)}",
                userId = userId,
                orderId = orderId,
                issueType = issueType.name,
                description = description.trim(),
                status = "IN_REVIEW",
                resolutionNote = resolution,
                createdAtMs = System.currentTimeMillis()
            )
            supportTicketDao.insertTicket(entity)
            AppResult.Success(entity.toDomain())
        } catch (t: Throwable) {
            AppResult.Error(ErrorMapper.map(t))
        }
    }
}
