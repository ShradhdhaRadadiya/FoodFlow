package com.foodflow.domain.repository

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.CreateScheduledOrderParams
import com.foodflow.domain.model.RestaurantOperatingHours
import com.foodflow.domain.model.ScheduleExecutionOutcome
import com.foodflow.domain.model.ScheduleRecurrenceRule
import com.foodflow.domain.model.ScheduleValidationResult
import com.foodflow.domain.model.ScheduledOrder
import com.foodflow.domain.model.ScheduledOrderExecution
import kotlinx.coroutines.flow.Flow

interface ScheduledOrderRepository {
    /** Streams the user's scheduled orders. */
    fun observeScheduledOrders(): Flow<List<ScheduledOrder>>
    /** Streams failed schedules whose alerts have not been acknowledged. */
    fun observeUnacknowledgedFailedSchedules(): Flow<List<ScheduledOrder>>
    /** Streams one scheduled order as its state changes. */
    fun observeScheduledOrder(scheduleId: String): Flow<ScheduledOrder?>
    /** Streams execution attempts for one scheduled order. */
    fun observeExecutionHistory(scheduleId: String): Flow<List<ScheduledOrderExecution>>

    /** Loads opening hours used to validate the selected schedule time. */
    suspend fun getRestaurantOperatingHours(restaurantId: String): RestaurantOperatingHours

    /** Checks a proposed schedule before it is saved. */
    suspend fun validateBeforeCreate(params: CreateScheduledOrderParams): ScheduleValidationResult

    /** Saves a new scheduled order. */
    suspend fun createScheduledOrder(params: CreateScheduledOrderParams): AppResult<ScheduledOrder>

    /** Updates the placement time and recurrence settings of a schedule. */
    suspend fun updateScheduledOrder(
        scheduleId: String,
        newScheduledAtUtcMs: Long,
        newRecurrenceRule: ScheduleRecurrenceRule
    ): AppResult<ScheduledOrder>

    /** Cancels a schedule and stores the optional reason. */
    suspend fun cancelScheduledOrder(scheduleId: String, reason: String = ""): AppResult<ScheduledOrder>

    /** Pauses future executions of a schedule. */
    suspend fun pauseScheduledOrder(scheduleId: String): AppResult<ScheduledOrder>

    /** Resumes future executions of a paused schedule. */
    suspend fun resumeScheduledOrder(scheduleId: String): AppResult<ScheduledOrder>

    /** Marks the failure alert for a schedule as seen. */
    suspend fun acknowledgeScheduledOrderFailure(scheduleId: String): AppResult<Unit>

    /** Processes a due occurrence and returns whether an order was placed, failed, or skipped. */
    suspend fun executeDueOccurrence(
        scheduleId: String,
        expectedOccurrenceNumber: Int? = null,
        forceRunNowForDemo: Boolean = false
    ): AppResult<ScheduleExecutionOutcome>

    /** Recovers due schedules that were missed while the app was unavailable. */
    suspend fun reconcileOverdueSchedules(): Int
}
