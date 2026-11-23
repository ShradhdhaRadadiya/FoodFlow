package com.foodflow.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.foodflow.core.common.AppResult
import com.foodflow.domain.repository.CartRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker guaranteeing deferred delivery of offline cart & favorite outbox operations.
 *
 * Why WorkManager:
 * If the user edits their cart offline and the OS kills the app process, in-memory coroutine
 * scopes disappear. WorkManager persists the work request in its own SQLite store and executes
 * [CartSyncWorker] automatically once network connectivity returns, with bounded exponential backoff.
 */
@HiltWorker
class CartSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val cartRepository: CartRepository
) : CoroutineWorker(appContext, workerParams) {

    /** Sends queued cart changes when WorkManager runs this job with network available. */
    override suspend fun doWork(): Result {
        if (runAttemptCount >= MAX_RETRY_ATTEMPTS) {
            Log.w(TAG, "Max sync attempts reached; leaving operations pending for next manual or reconnect trigger.")
            return Result.failure()
        }
        return when (val syncResult = cartRepository.syncPendingOperations()) {
            is AppResult.Success -> {
                Log.i(TAG, "Cart outbox synced successfully. Reconciliation notices: ${syncResult.data.size}")
                Result.success()
            }
            is AppResult.Error -> {
                Log.w(TAG, "Cart outbox sync deferred: ${syncResult.error.technicalDetail}")
                if (syncResult.error.isRetryable) Result.retry() else Result.failure()
            }
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "foodflow_cart_outbox_sync"
        private const val MAX_RETRY_ATTEMPTS = 5
        private const val TAG = "CartSyncWorker"

        /** Schedules one unique cart-sync job that waits for a network connection. */
        fun enqueue(workManager: WorkManager) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<CartSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag(UNIQUE_WORK_NAME)
                .build()

            // APPEND_OR_REPLACE guarantees we never run two overlapping cart sync workers simultaneously
            workManager.enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
