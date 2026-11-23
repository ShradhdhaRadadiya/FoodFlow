package com.foodflow.data.sync

import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Abstraction over Android [WorkManager] for scheduled order execution.
 *
 * Uses unique chained [androidx.work.OneTimeWorkRequest] instances per schedule ID with
 * exact initial delays rather than [androidx.work.PeriodicWorkRequest] (which cannot honor
 * weekly multi-day, monthly 28/29/30/31 clamping, or exact user-selected clock times).
 */
interface ScheduledOrderWorkScheduler {
    fun scheduleExecution(
        scheduleId: String,
        targetExecutionAtUtcMs: Long,
        expectedOccurrenceNumber: Int
    )

    fun cancel(scheduleId: String)
}

@Singleton
class WorkManagerScheduledOrderSchedulerImpl @Inject constructor(
    private val workManager: WorkManager
) : ScheduledOrderWorkScheduler {

    override fun scheduleExecution(
        scheduleId: String,
        targetExecutionAtUtcMs: Long,
        expectedOccurrenceNumber: Int
    ) {
        val delayMs = (targetExecutionAtUtcMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val inputData = Data.Builder()
            .putString(ScheduledOrderWorker.KEY_SCHEDULE_ID, scheduleId)
            .putInt(ScheduledOrderWorker.KEY_EXPECTED_OCCURRENCE, expectedOccurrenceNumber)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<ScheduledOrderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(inputData)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(TAG_ALL_SCHEDULED_ORDERS)
            .addTag(uniqueWorkName(scheduleId))
            .build()

        workManager.enqueueUniqueWork(
            uniqueWorkName(scheduleId),
            ExistingWorkPolicy.REPLACE,
            request
        )
        Log.i(
            TAG,
            "Scheduled WorkManager job ${uniqueWorkName(scheduleId)} for occurrence #$expectedOccurrenceNumber in ${delayMs}ms"
        )
    }

    override fun cancel(scheduleId: String) {
        workManager.cancelUniqueWork(uniqueWorkName(scheduleId))
        Log.i(TAG, "Cancelled WorkManager job ${uniqueWorkName(scheduleId)}")
    }

    companion object {
        private const val TAG = "ScheduledOrderWorkSched"
        const val TAG_ALL_SCHEDULED_ORDERS = "foodflow_scheduled_orders"

        fun uniqueWorkName(scheduleId: String): String = "foodflow_scheduled_order_$scheduleId"
    }
}
