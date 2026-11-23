package com.foodflow.data.remote.api

import com.foodflow.core.common.AppResult
import com.foodflow.data.remote.dto.ExecuteScheduledOccurrenceRequestDto
import com.foodflow.data.remote.dto.ExecuteScheduledOccurrenceResponseDto
import com.foodflow.data.remote.dto.FoodItemAvailabilityItemDto
import com.foodflow.data.remote.dto.LatestPricingQuoteDto
import com.foodflow.data.remote.dto.RestaurantAvailabilityResponseDto
import com.foodflow.data.remote.dto.RestaurantRatingAggregateDto
import com.foodflow.data.remote.dto.RestaurantReviewsResponseDto
import com.foodflow.data.remote.dto.ScheduledOrderApiDto
import com.foodflow.data.remote.dto.ScheduledPaymentValidationDto
import com.foodflow.data.remote.dto.SubmitReviewRequestDto
import com.foodflow.data.remote.dto.SubmitReviewResponseDto
import com.foodflow.domain.model.OrderItem
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Production Retrofit API blueprint for Dynamic Restaurant Reviews & Scheduled Ordering.
 *
 * In a production FoodFlow backend, an authoritative server-side scheduler (e.g., cron/event-bridge +
 * distributed lock + idempotent execution worker) invokes due scheduled occurrences independently of
 * mobile device state. Locally for this Android assessment, these contracts are backed by separate
 * mock data source implementations behind the interfaces below so swapping to real Retrofit requires
 * zero Domain or Presentation changes.
 */
interface ReviewAndScheduledOrderRetrofitService {

    // --- Restaurant Reviews & Ratings ---
    /** Submits a restaurant review with duplicate-request protection. */
    @POST("v1/restaurants/{restaurantId}/reviews")
    suspend fun submitReview(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Path("restaurantId") restaurantId: String,
        @Body request: SubmitReviewRequestDto
    ): SubmitReviewResponseDto

    /** Updates the review previously submitted for an order. */
    @PUT("v1/restaurants/{restaurantId}/reviews/by-order/{orderId}")
    suspend fun updateReview(
        @Path("restaurantId") restaurantId: String,
        @Path("orderId") orderId: String,
        @Body request: SubmitReviewRequestDto
    ): SubmitReviewResponseDto

    /** Fetches reviews for the selected restaurant. */
    @GET("v1/restaurants/{restaurantId}/reviews")
    suspend fun getRestaurantReviews(
        @Path("restaurantId") restaurantId: String
    ): RestaurantReviewsResponseDto

    /** Fetches the restaurant's aggregate rating. */
    @GET("v1/restaurants/{restaurantId}/rating")
    suspend fun getRestaurantRating(
        @Path("restaurantId") restaurantId: String
    ): RestaurantRatingAggregateDto

    // --- Scheduled Orders CRUD & Lifecycle ---
    /** Creates a scheduled order from the supplied schedule details. */
    @POST("v1/scheduled-orders")
    suspend fun createScheduledOrder(@Body request: ScheduledOrderApiDto): ScheduledOrderApiDto

    /** Lists scheduled orders belonging to the customer. */
    @GET("v1/scheduled-orders")
    suspend fun getScheduledOrders(@Query("customerId") customerId: String): List<ScheduledOrderApiDto>

    /** Fetches one scheduled order by identifier. */
    @GET("v1/scheduled-orders/{scheduleId}")
    suspend fun getScheduledOrder(@Path("scheduleId") scheduleId: String): ScheduledOrderApiDto

    /** Replaces the saved details for a scheduled order. */
    @PUT("v1/scheduled-orders/{scheduleId}")
    suspend fun updateScheduledOrder(
        @Path("scheduleId") scheduleId: String,
        @Body request: ScheduledOrderApiDto
    ): ScheduledOrderApiDto

    /** Cancels a scheduled order. */
    @POST("v1/scheduled-orders/{scheduleId}/cancel")
    suspend fun cancelScheduledOrder(@Path("scheduleId") scheduleId: String): ScheduledOrderApiDto

    /** Pauses future executions of a scheduled order. */
    @POST("v1/scheduled-orders/{scheduleId}/pause")
    suspend fun pauseScheduledOrder(@Path("scheduleId") scheduleId: String): ScheduledOrderApiDto

    /** Resumes a paused scheduled order. */
    @POST("v1/scheduled-orders/{scheduleId}/resume")
    suspend fun resumeScheduledOrder(@Path("scheduleId") scheduleId: String): ScheduledOrderApiDto

    /** Executes one due schedule occurrence with duplicate-request protection. */
    @POST("v1/scheduled-orders/{scheduleId}/execute")
    suspend fun executeDueOccurrence(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Path("scheduleId") scheduleId: String,
        @Body request: ExecuteScheduledOccurrenceRequestDto
    ): ExecuteScheduledOccurrenceResponseDto

    /** Checks restaurant availability for the requested address and time slot. */
    @GET("v1/restaurants/{restaurantId}/availability")
    suspend fun validateRestaurantAvailability(
        @Path("restaurantId") restaurantId: String,
        @Query("addressId") addressId: String,
        @Query("slotUtcMs") slotUtcMs: Long
    ): RestaurantAvailabilityResponseDto
}

/**
 * Replaceable remote data source for restaurant reviews & dynamic rating aggregates.
 */
interface ReviewRemoteDataSource {
    /** Submits a review or updates the user's existing review when allowed. */
    suspend fun submitReview(request: SubmitReviewRequestDto, allowUpdate: Boolean): AppResult<SubmitReviewResponseDto>
    /** Loads the reviews for one restaurant. */
    suspend fun fetchRestaurantReviews(restaurantId: String): AppResult<RestaurantReviewsResponseDto>
    /** Loads the current rating aggregate for one restaurant. */
    suspend fun fetchRestaurantRating(restaurantId: String): AppResult<RestaurantRatingAggregateDto>
}

/**
 * Separate replaceable data source for restaurant open/paused/operating-hours & delivery slot validation.
 */
interface RestaurantAvailabilityDataSource {
    /** Checks whether a restaurant can serve the address at the requested time. */
    suspend fun checkAvailability(
        restaurantId: String,
        addressId: String,
        targetSlotUtcMs: Long
    ): AppResult<RestaurantAvailabilityResponseDto>
}

/**
 * Separate replaceable data source for food item & customization availability validation.
 */
interface FoodItemAvailabilityDataSource {
    /** Checks that requested food items and their options are still available. */
    suspend fun checkItemsAvailability(
        restaurantId: String,
        items: List<OrderItem>
    ): AppResult<List<FoodItemAvailabilityItemDto>>
}

/**
 * Separate replaceable data source for fetching latest menu prices & recalculating payable totals.
 */
interface LatestPricingDataSource {
    /** Returns current item prices and totals for the requested order. */
    suspend fun fetchLatestPricingQuote(
        restaurantId: String,
        items: List<OrderItem>,
        couponCode: String?
    ): AppResult<LatestPricingQuoteDto>
}

/**
 * Separate replaceable data source for scheduled payment authorization / validation.
 */
interface ScheduledPaymentValidationDataSource {
    /** Validates payment details and authorizes payment for a schedule occurrence. */
    suspend fun validateAndAuthorizePayment(
        scheduleId: String,
        occurrenceIdempotencyKey: String,
        paymentMethod: String,
        amount: Double
    ): AppResult<ScheduledPaymentValidationDto>
}

/**
 * Separate replaceable data source for idempotent scheduled occurrence execution.
 */
interface ScheduledOrderExecutionDataSource {
    /** Executes one scheduled occurrence using the supplied idempotency data. */
    suspend fun executeOccurrence(
        request: ExecuteScheduledOccurrenceRequestDto
    ): AppResult<ExecuteScheduledOccurrenceResponseDto>
}
