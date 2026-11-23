package com.foodflow.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.ScheduleExecutionOutcome
import com.foodflow.domain.usecase.scheduled.ExecuteScheduledOrderUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager [CoroutineWorker] that executes a due scheduled order occurrence locally.
 *
 * Production Architecture Note:
 * In a real production deployment, scheduled order triggering lives on a server-side
 * scheduler (e.g., Cloud Scheduler / Temporal / distributed cron worker) so orders execute
 * reliably even if the customer's phone is powered off, offline, or in deep Doze mode.
 * this worker simulates that scheduled execution
 * locally via WorkManager + Room + replaceable mock data sources.
 */
@HiltWorker
class ScheduledOrderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val executeScheduledOrder: ExecuteScheduledOrderUseCase
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val scheduleId = inputData.getString(KEY_SCHEDULE_ID)
        if (scheduleId.isNullOrBlank()) {
            Log.w(TAG, "Missing scheduleId input data; terminating worker.")
            return Result.failure()
        }
        val expectedOccurrence = inputData.getInt(KEY_EXPECTED_OCCURRENCE, -1)
            .takeIf { it > 0 }

        if (runAttemptCount >= MAX_RETRY_ATTEMPTS) {
            Log.w(TAG, "Max WorkManager retry attempts reached for schedule $scheduleId.")
            return Result.failure()
        }

        return when (
            val result = executeScheduledOrder(
                scheduleId = scheduleId,
                expectedOccurrenceNumber = expectedOccurrence,
                forceRunNowForDemo = false
            )
        ) {
            is AppResult.Success -> when (val outcome = result.data) {
                is ScheduleExecutionOutcome.Succeeded -> {
                    Log.i(
                        TAG,
                        "Scheduled order $scheduleId executed -> created order ${outcome.generatedOrder.id}"
                    )
                    Result.success()
                }
                is ScheduleExecutionOutcome.Skipped -> {
                    Log.i(TAG, "Scheduled order $scheduleId skipped: ${outcome.reason}")
                    Result.success()
                }
                is ScheduleExecutionOutcome.Failed -> {
                    Log.w(
                        TAG,
                        "Scheduled order $scheduleId failed (${outcome.execution.failureCategory}): ${outcome.execution.failureReason}"
                    )
                    if (outcome.shouldRetry) Result.retry() else Result.failure()
                }
            }

            is AppResult.Error -> {
                Log.w(TAG, "Scheduled order $scheduleId error: ${result.error.technicalDetail}")
                if (result.error.isRetryable) Result.retry() else Result.failure()
            }
        }
    }

    companion object {
        const val KEY_SCHEDULE_ID = "schedule_id"
        const val KEY_EXPECTED_OCCURRENCE = "expected_occurrence"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val TAG = "ScheduledOrderWorker"
    }
}
