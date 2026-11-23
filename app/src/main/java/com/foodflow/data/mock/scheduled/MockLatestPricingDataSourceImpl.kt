package com.foodflow.data.mock.scheduled

import com.foodflow.core.common.AppError
import com.foodflow.core.common.AppResult
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.data.local.dao.CatalogDao
import com.foodflow.data.local.mapper.toDomain
import com.foodflow.data.mock.MockScenarioController
import com.foodflow.data.mock.SimulatedNetworkFault
import com.foodflow.data.remote.api.LatestPricingDataSource
import com.foodflow.data.remote.dto.LatestPricingQuoteDto
import com.foodflow.domain.model.CartItem
import com.foodflow.domain.model.OrderItem
import com.foodflow.domain.usecase.CalculateCartTotalsUseCase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Replaceable mock implementation fetching latest product prices, delivery fees, taxes,
 * and coupon validity at scheduled execution time.
 */
@Singleton
class MockLatestPricingDataSourceImpl @Inject constructor(
    private val catalogDao: CatalogDao,
    private val calculateCartTotals: CalculateCartTotalsUseCase,
    private val scenarioController: MockScenarioController,
    private val connectivityObserver: ConnectivityObserver
) : LatestPricingDataSource {

    /** Calculates current item prices and order totals for a scheduled occurrence. */
    override suspend fun fetchLatestPricingQuote(
        restaurantId: String,
        items: List<OrderItem>,
        couponCode: String?
    ): AppResult<LatestPricingQuoteDto> {
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

        val restaurant = catalogDao.getRestaurant(restaurantId)
            ?: return AppResult.Error(AppError.EmptyResponse("Restaurant $restaurantId not found."))

        val latestPrices = mutableMapOf<String, Double>()
        val cartItems = items.map { orderItem ->
            val entity = catalogDao.getFoodItem(orderItem.foodId)
            val authoritativePrice = cfg.priceOverrides[orderItem.foodId]
                ?: entity?.price
                ?: orderItem.unitPrice
            latestPrices[orderItem.foodId] = authoritativePrice
            CartItem(
                itemId = orderItem.foodId,
                restaurantId = restaurantId,
                restaurantName = restaurant.name,
                foodName = orderItem.foodName,
                unitPrice = authoritativePrice,
                quantity = orderItem.quantity,
                isVeg = orderItem.isVeg,
                imageKey = entity?.imageKey ?: "indian"
            )
        }

        val coupon = couponCode?.let { catalogDao.getCouponByCode(it)?.toDomain() }
        val couponStillValid = coupon == null || !coupon.isExpired(System.currentTimeMillis())
        val breakdown = calculateCartTotals(
            items = cartItems,
            baseDeliveryFee = restaurant.deliveryFee,
            coupon = coupon?.takeIf { couponStillValid }
        )

        return AppResult.Success(
            LatestPricingQuoteDto(
                restaurantId = restaurantId,
                itemPricesById = latestPrices,
                subtotal = breakdown.subtotal,
                deliveryFee = breakdown.deliveryFee,
                packagingCharge = breakdown.packagingCharge,
                taxes = breakdown.taxes,
                discount = breakdown.discount,
                totalPayable = breakdown.totalPayable,
                couponStillValid = couponStillValid,
                couponNote = if (!couponStillValid) "Previously applied coupon expired." else null
            )
        )
    }
}
