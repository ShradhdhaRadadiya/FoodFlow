package com.foodflow.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.foodflow.core.common.AppResult
import com.foodflow.core.datastore.UserPreferences
import com.foodflow.core.datastore.UserPreferencesDataStore
import com.foodflow.data.sync.CatalogSyncWorker
import com.foodflow.domain.repository.OrderRepository
import com.foodflow.domain.repository.RestaurantRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CatalogSyncWorkerTest {

    @Test
    fun `skips sync when local cache is fresh within 5-minute window`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val restaurantRepo = mockk<RestaurantRepository>(relaxed = true)
        val orderRepo = mockk<OrderRepository>(relaxed = true)
        val prefs = mockk<UserPreferencesDataStore>()
        coEvery { prefs.getSnapshot() } returns UserPreferences(
            lastCatalogSyncEpochMs = System.currentTimeMillis() - 30_000L
        )

        val worker = buildWorker(context, restaurantRepo, orderRepo, prefs)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { restaurantRepo.refreshHomeCatalog() }
        coVerify(exactly = 0) { orderRepo.refreshOrders() }
    }

    @Test
    fun `executes sync when cache is stale or forced`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val restaurantRepo = mockk<RestaurantRepository>(relaxed = true)
        val orderRepo = mockk<OrderRepository>(relaxed = true)
        val prefs = mockk<UserPreferencesDataStore>()
        coEvery { prefs.getSnapshot() } returns UserPreferences(
            lastCatalogSyncEpochMs = System.currentTimeMillis() - (10 * 60 * 1000L)
        )
        coEvery { restaurantRepo.refreshHomeCatalog() } returns AppResult.Success(1_000L)
        coEvery { orderRepo.refreshOrders() } returns AppResult.Success(Unit)

        val worker = buildWorker(
            context = context,
            restaurantRepo = restaurantRepo,
            orderRepo = orderRepo,
            prefs = prefs,
            force = false
        )
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { restaurantRepo.refreshHomeCatalog() }
        coVerify(exactly = 1) { orderRepo.refreshOrders() }
    }

    private fun buildWorker(
        context: Context,
        restaurantRepo: RestaurantRepository,
        orderRepo: OrderRepository,
        prefs: UserPreferencesDataStore,
        force: Boolean = false
    ): CatalogSyncWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker = CatalogSyncWorker(
                appContext = appContext,
                workerParams = workerParameters,
                restaurantRepository = restaurantRepo,
                orderRepository = orderRepo,
                preferencesDataStore = prefs
            )
        }
        return TestListenableWorkerBuilder<CatalogSyncWorker>(context)
            .setInputData(workDataOf(CatalogSyncWorker.KEY_FORCE_SYNC to force))
            .setWorkerFactory(factory)
            .build()
    }
}
