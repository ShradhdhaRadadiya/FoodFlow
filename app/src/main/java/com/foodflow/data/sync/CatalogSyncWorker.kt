package com.foodflow.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.foodflow.core.common.AppResult
import com.foodflow.core.datastore.UserPreferencesDataStore
import com.foodflow.domain.repository.OrderRepository
import com.foodflow.domain.repository.RestaurantRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Automatic background catalog & order synchronization worker.
 *
 * Keeps Room fresh on app launch, periodic intervals, and network restoration
 * without blocking UI rendering or requiring manual refresh buttons.
 */
@HiltWorker
class CatalogSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val restaurantRepository: RestaurantRepository,
    private val orderRepository: OrderRepository,
    private val preferencesDataStore: UserPreferencesDataStore
) : CoroutineWorker(appContext, workerParams) {

    /** Refreshes catalog and order data in the background when a sync is due. */
    override suspend fun doWork(): Result {
        val lastSyncMs = preferencesDataStore.getSnapshot().lastCatalogSyncEpochMs
        val now = System.currentTimeMillis()
        val force = inputData.getBoolean(KEY_FORCE_SYNC, false)
        if (!force && lastSyncMs > 0L && (now - lastSyncMs) < MIN_FRESHNESS_INTERVAL_MS) {
            Log.d(TAG, "Skipping catalog sync — local Room cache is fresh.")
            return Result.success()
        }

        val catalogRes = restaurantRepository.refreshHomeCatalog()
        orderRepository.refreshOrders()

        return when (catalogRes) {
            is AppResult.Success -> {
                Log.i(TAG, "Background catalog sync succeeded.")
                Result.success()
            }
            is AppResult.Error -> {
                Log.w(TAG, "Background catalog sync failed: ${catalogRes.error.technicalDetail}")
                if (catalogRes.error.isRetryable && runAttemptCount < MAX_RETRIES) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
        }
    }

    companion object {
        const val UNIQUE_PERIODIC_WORK = "foodflow_catalog_periodic_sync"
        const val UNIQUE_ONETIME_WORK = "foodflow_catalog_onetime_sync"
        const val KEY_FORCE_SYNC = "force_sync"
        const val MIN_FRESHNESS_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
        private const val MAX_RETRIES = 3
        private const val TAG = "CatalogSyncWorker"

        /** Registers recurring catalog synchronization that requires network access. */
        fun schedulePeriodic(workManager: WorkManager) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<CatalogSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** Queues a one-time catalog sync that runs when network access is available. */
        fun enqueueImmediate(workManager: WorkManager) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<CatalogSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniqueWork(
                UNIQUE_ONETIME_WORK,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
