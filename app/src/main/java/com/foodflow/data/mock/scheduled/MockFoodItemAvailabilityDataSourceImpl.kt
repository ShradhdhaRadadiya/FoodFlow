package com.foodflow.data.mock.scheduled

import com.foodflow.core.common.AppError
import com.foodflow.core.common.AppResult
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.data.local.dao.CatalogDao
import com.foodflow.data.mock.MockScenarioController
import com.foodflow.data.mock.SimulatedNetworkFault
import com.foodflow.data.remote.api.FoodItemAvailabilityDataSource
import com.foodflow.data.remote.dto.FoodItemAvailabilityItemDto
import com.foodflow.domain.model.OrderItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Replaceable mock implementation verifying each scheduled food item still exists
 * and is currently in stock before executing a scheduled occurrence.
 */
@Singleton
class MockFoodItemAvailabilityDataSourceImpl @Inject constructor(
    private val catalogDao: CatalogDao,
    private val scenarioController: MockScenarioController,
    private val connectivityObserver: ConnectivityObserver
) : FoodItemAvailabilityDataSource {

    /** Checks that each scheduled food item still belongs to the restaurant and is available. */
    override suspend fun checkItemsAvailability(
        restaurantId: String,
        items: List<OrderItem>
    ): AppResult<List<FoodItemAvailabilityItemDto>> {
        val cfg = scenarioController.current()
        if (cfg.forcedOffline || !connectivityObserver.isCurrentlyOnline()) {
            return AppResult.Error(AppError.Offline())
        }
        if (cfg.networkFault == SimulatedNetworkFault.TIMEOUT) {
            return AppResult.Error(AppError.Timeout())
        }
        if (cfg.networkFault == SimulatedNetworkFault.SERVER_ERROR_500) {
            return AppResult.Error(AppError.ServerError(500))
        }

        val results = items.map { orderItem ->
            val entity = catalogDao.getFoodItem(orderItem.foodId)
            val exists = entity != null && entity.restaurantId == restaurantId
            val inStock = exists && entity!!.isAvailable && orderItem.foodId !in cfg.unavailableFoodIds
            val reason = when {
                !exists -> "${orderItem.foodName} is no longer on the menu."
                !inStock -> "${orderItem.foodName} is currently out of stock."
                else -> null
            }
            FoodItemAvailabilityItemDto(
                foodId = orderItem.foodId,
                foodName = orderItem.foodName,
                exists = exists,
                isAvailable = inStock,
                customizationsValid = exists,
                unavailableReason = reason
            )
        }
        return AppResult.Success(results)
    }
}
