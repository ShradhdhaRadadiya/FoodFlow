package com.foodflow.domain.repository

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.CheckoutAttempt
import com.foodflow.domain.model.Order
import com.foodflow.domain.model.OrderTimelineEvent
import com.foodflow.domain.model.PaymentMethod
import kotlinx.coroutines.flow.Flow

interface OrderRepository {
    /** Streams the user's saved orders. */
    fun observeOrders(): Flow<List<Order>>
    /** Streams one order as its status changes. */
    fun observeOrder(orderId: String): Flow<Order?>
    /** Streams the status and activity history for one order. */
    fun observeOrderTimeline(orderId: String): Flow<List<OrderTimelineEvent>>
    /** Streams the unfinished checkout attempt, if one exists. */
    fun observeActiveCheckoutAttempt(): Flow<CheckoutAttempt?>
    /** Fetches the latest orders and updates the local order data. */
    suspend fun refreshOrders(): AppResult<Unit>
    /** Creates a checkout attempt or returns the existing attempt for this checkout. */
    suspend fun startOrResumeCheckout(
        addressId: String,
        paymentMethod: PaymentMethod,
        couponCode: String?
    ): AppResult<CheckoutAttempt>
    /** Submits an order using a retry-safe attempt to prevent duplicates. */
    suspend fun submitIdempotentOrder(attempt: CheckoutAttempt): AppResult<Order>
    /** Finds an order created with this retry-safe key. */
    suspend fun lookupOrderByIdempotencyKey(idempotencyKey: String): AppResult<Order?>
    /** Requests cancellation of the selected order and records its reason. */
    suspend fun cancelOrder(orderId: String, reason: String): AppResult<Order>
    /** Saves a rating and review for a completed order. */
    suspend fun rateOrder(orderId: String, rating: Int, comment: String): AppResult<Unit>
    /** Adds the items from a past order to the current cart. */
    suspend fun reorder(orderId: String): AppResult<Unit>
    /** Removes the saved checkout attempt with this retry-safe key. */
    suspend fun clearActiveCheckoutAttempt(idempotencyKey: String)
}
