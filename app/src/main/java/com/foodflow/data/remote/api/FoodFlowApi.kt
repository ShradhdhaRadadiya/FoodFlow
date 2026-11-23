package com.foodflow.data.remote.api

import com.foodflow.core.common.AppResult
import com.foodflow.data.remote.dto.CartOperationRequestDto
import com.foodflow.data.remote.dto.CartSyncAckDto
import com.foodflow.data.remote.dto.CouponDto
import com.foodflow.data.remote.dto.CreateOrderRequestDto
import com.foodflow.data.remote.dto.FoodItemDto
import com.foodflow.data.remote.dto.OrderDto
import com.foodflow.data.remote.dto.PaymentRequestDto
import com.foodflow.data.remote.dto.PaymentResponseDto
import com.foodflow.data.remote.dto.RestaurantDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit HTTP service blueprint matching a production FoodFlow REST API.
 * Kept behind clean `*RemoteDataSource` interfaces so the local mock backend can be
 * swapped out for real Retrofit + OkHttp networking without changing repositories or ViewModels.
 */
interface FoodFlowRetrofitService {
    /** Fetches the current restaurant catalog from the REST service. */
    @GET("v1/restaurants")
    suspend fun getRestaurants(): List<RestaurantDto>

    /** Fetches a restaurant's menu by its identifier. */
    @GET("v1/restaurants/{restaurantId}/menu")
    suspend fun getMenu(@Path("restaurantId") restaurantId: String): List<FoodItemDto>

    /** Checks a coupon code against the supplied subtotal. */
    @GET("v1/coupons/{code}")
    suspend fun validateCoupon(@Path("code") code: String, @Query("subtotal") subtotal: Double): CouponDto

    /** Sends one idempotent cart change and receives its server acknowledgement. */
    @POST("v1/cart/operations")
    suspend fun syncCartOperation(
        @Header("X-Operation-Id") operationId: String,
        @Body request: CartOperationRequestDto
    ): CartSyncAckDto

    /** Creates an order using an idempotency key to prevent duplicate submissions. */
    @POST("v1/orders")
    suspend fun createOrder(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateOrderRequestDto
    ): OrderDto

    /** Looks up an order previously submitted with the given idempotency key. */
    @GET("v1/orders/by-idempotency-key/{key}")
    suspend fun getOrderByIdempotencyKey(@Path("key") idempotencyKey: String): OrderDto?

    /** Fetches one order by its identifier. */
    @GET("v1/orders/{orderId}")
    suspend fun getOrder(@Path("orderId") orderId: String): OrderDto

    /** Submits an idempotent payment charge request. */
    @POST("v1/payments/charge")
    suspend fun processPayment(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: PaymentRequestDto
    ): PaymentResponseDto
}

/**
 * Replaceable remote data source contracts implemented locally by [com.foodflow.data.mock.LocalMockBackend].
 */
interface RestaurantRemoteDataSource {
    /** Fetches restaurants through the replaceable remote data source. */
    suspend fun fetchRestaurants(): AppResult<List<RestaurantDto>>
    /** Fetches menu items for one restaurant. */
    suspend fun fetchMenu(restaurantId: String): AppResult<List<FoodItemDto>>
    /** Validates a coupon against the current subtotal. */
    suspend fun validateCoupon(code: String, subtotal: Double): AppResult<CouponDto>
}

interface CartRemoteDataSource {
    /** Sends a queued cart operation to the remote source. */
    suspend fun syncOperation(request: CartOperationRequestDto): AppResult<CartSyncAckDto>
    /** Confirms that the selected cart items remain available. */
    suspend fun validateCartItems(restaurantId: String, itemIds: List<String>): AppResult<List<FoodItemDto>>
}

interface OrderRemoteDataSource {
    /** Creates an order from the prepared request. */
    suspend fun createOrder(request: CreateOrderRequestDto): AppResult<OrderDto>
    /** Checks whether an order already exists for a submission key. */
    suspend fun getOrderByIdempotencyKey(idempotencyKey: String): AppResult<OrderDto?>
    /** Retrieves one order by identifier. */
    suspend fun getOrder(orderId: String): AppResult<OrderDto>
    /** Requests cancellation of an order with the supplied reason. */
    suspend fun cancelOrder(orderId: String, reason: String): AppResult<OrderDto>
    /** Retrieves the order history for a user. */
    suspend fun fetchOrders(userId: String): AppResult<List<OrderDto>>
}

interface PaymentRemoteDataSource {
    /** Processes a payment request through the remote source. */
    suspend fun processPayment(request: PaymentRequestDto): AppResult<PaymentResponseDto>
}

interface UserRemoteDataSource {
    /** Requests a one-time passcode for the phone number. */
    suspend fun sendOtp(phoneNumber: String): AppResult<Int>
    /** Verifies the passcode and returns the authenticated user details. */
    suspend fun verifyOtp(phoneNumber: String, otp: String): AppResult<Pair<String, String>>
    /** Synchronizes one restaurant's favorite state for the user. */
    suspend fun syncFavorite(userId: String, restaurantId: String, isFavorite: Boolean): AppResult<Unit>
}
