package com.foodflow.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.foodflow.core.common.DispatcherProvider
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.core.connectivity.ConnectivityStatus
import com.foodflow.data.sync.CartSyncWorker
import com.foodflow.domain.usecase.scheduled.ReconcileOverdueSchedulesUseCase
import com.foodflow.domain.usecase.tracking.EnsureActiveOrderTrackingUseCase
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * FoodFlow Application entry point configuring Hilt DI, HiltWorkerFactory for WorkManager,
 * and automatic outbox sync triggering whenever connectivity transitions from OFFLINE -> RESTORED.
 */
@HiltAndroidApp
class FoodFlowApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var connectivityObserver: ConnectivityObserver

    @Inject
    lateinit var workManager: WorkManager

    @Inject
    lateinit var dispatchers: DispatcherProvider

    @Inject
    lateinit var ensureActiveOrderTracking: EnsureActiveOrderTrackingUseCase

    @Inject
    lateinit var reconcileOverdueSchedules: ReconcileOverdueSchedulesUseCase

    private val appScope by lazy { CoroutineScope(SupervisorJob() + dispatchers.io) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    /** Starts catalog/order maintenance and begins observing connectivity for deferred sync work. */
    override fun onCreate() {
        super.onCreate()
        com.foodflow.data.sync.CatalogSyncWorker.schedulePeriodic(workManager)
        com.foodflow.data.sync.CatalogSyncWorker.enqueueImmediate(workManager)
        ensureActiveOrderTracking()
        appScope.launch {
            runCatching { reconcileOverdueSchedules() }
                .onFailure { Log.w("FoodFlowApp", "Initial schedule reconciliation deferred", it) }
        }
        observeReconnectForSync()
    }

    /** Enqueues cart/catalog sync and reconciles schedules whenever connectivity returns. */
    private fun observeReconnectForSync() {
        appScope.launch {
            connectivityObserver.status.collect { status ->
                if (status == ConnectivityStatus.RESTORED) {
                    Log.i("FoodFlowApp", "Connectivity restored — scheduling background sync workers")
                    CartSyncWorker.enqueue(workManager)
                    com.foodflow.data.sync.CatalogSyncWorker.enqueueImmediate(workManager)
                    runCatching { reconcileOverdueSchedules() }
                        .onFailure { Log.w("FoodFlowApp", "Reconnect schedule reconciliation failed", it) }
                }
            }
        }
    }
}
