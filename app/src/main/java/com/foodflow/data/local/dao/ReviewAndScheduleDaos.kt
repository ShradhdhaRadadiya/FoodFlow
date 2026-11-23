package com.foodflow.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.foodflow.data.local.entity.RestaurantReviewEntity
import com.foodflow.data.local.entity.ScheduledOrderEntity
import com.foodflow.data.local.entity.ScheduledOrderExecutionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReviewDao {
    @Query("SELECT * FROM restaurant_reviews WHERE restaurantId = :restaurantId ORDER BY updatedAtMs DESC")
    /** Streams saved reviews for a restaurant, newest update first. */
    fun observeReviewsForRestaurant(restaurantId: String): Flow<List<RestaurantReviewEntity>>

    @Query("SELECT * FROM restaurant_reviews WHERE orderId = :orderId LIMIT 1")
    /** Streams the review linked to an order, if one exists. */
    fun observeReviewForOrder(orderId: String): Flow<RestaurantReviewEntity?>

    @Query("SELECT * FROM restaurant_reviews WHERE orderId = :orderId LIMIT 1")
    /** Loads the saved review linked to an order once. */
    suspend fun getReviewByOrderId(orderId: String): RestaurantReviewEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /** Saves one review, replacing any row with the same key. */
    suspend fun upsertReview(review: RestaurantReviewEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /** Saves multiple reviews, replacing rows with matching keys. */
    suspend fun upsertReviews(reviews: List<RestaurantReviewEntity>)

    @Query("UPDATE restaurants SET rating = :rating, ratingCount = :ratingCount, lastUpdatedMs = :updatedAtMs WHERE id = :restaurantId")
    /** Updates the restaurant's cached average rating and review count. */
    suspend fun updateRestaurantRatingAggregate(
        restaurantId: String,
        rating: Double,
        ratingCount: Int,
        updatedAtMs: Long
    )

    @Query("UPDATE orders SET ratingGiven = :rating, reviewComment = :comment, updatedAtMs = :updatedAtMs WHERE id = :orderId")
    /** Stores the customer's rating and comment on the matching order. */
    suspend fun updateOrderRating(
        orderId: String,
        rating: Int,
        comment: String,
        updatedAtMs: Long
    )

    /**
     * Atomic synchronization across reviews, restaurants, and orders tables so Home,
     * Restaurant Listing, Search, Favorites, Orders, and Restaurant Detail stay 100% consistent.
     */
    @Transaction
    suspend fun saveReviewAndSyncAggregate(
        review: RestaurantReviewEntity,
        newAverageRating: Double,
        newTotalReviewCount: Int,
        updatedAtMs: Long
    ) {
        upsertReview(review)
        updateRestaurantRatingAggregate(
            restaurantId = review.restaurantId,
            rating = newAverageRating,
            ratingCount = newTotalReviewCount,
            updatedAtMs = updatedAtMs
        )
        updateOrderRating(
            orderId = review.orderId,
            rating = review.rating,
            comment = review.comment,
            updatedAtMs = updatedAtMs
        )
    }

    @Query("DELETE FROM restaurant_reviews")
    /** Removes all saved restaurant reviews. */
    suspend fun clearAllReviews()
}

@Dao
interface ScheduledOrderDao {
    @Query("SELECT * FROM scheduled_orders WHERE userId = :userId ORDER BY CASE status WHEN 'PLACING_ORDER' THEN 0 WHEN 'UPCOMING' THEN 1 WHEN 'PAUSED' THEN 2 WHEN 'FAILED' THEN 3 WHEN 'COMPLETED' THEN 4 ELSE 5 END, nextExecutionAtUtcMs ASC")
    /** Streams this user's schedules ordered by status and next placement time. */
    fun observeScheduledOrders(userId: String): Flow<List<ScheduledOrderEntity>>

    @Query("SELECT * FROM scheduled_orders WHERE userId = :userId AND status = 'FAILED' AND failureAcknowledged = 0 ORDER BY updatedAtMs DESC")
    /** Streams failed schedules whose alert has not yet been acknowledged. */
    fun observeUnacknowledgedFailedSchedules(userId: String): Flow<List<ScheduledOrderEntity>>

    @Query("SELECT * FROM scheduled_orders WHERE id = :scheduleId")
    /** Streams one scheduled order as its stored state changes. */
    fun observeScheduledOrder(scheduleId: String): Flow<ScheduledOrderEntity?>

    @Query("SELECT * FROM scheduled_orders WHERE id = :scheduleId")
    /** Loads one scheduled order without observing future changes. */
    suspend fun getScheduledOrder(scheduleId: String): ScheduledOrderEntity?

    @Query("SELECT * FROM scheduled_orders WHERE userId = :userId AND creationIdempotencyKey = :creationKey AND status IN ('UPCOMING', 'PLACING_ORDER') LIMIT 1")
    /** Finds an active schedule created with this retry-safe creation key. */
    suspend fun getActiveScheduleByCreationKey(userId: String, creationKey: String): ScheduledOrderEntity?

    @Query("UPDATE scheduled_orders SET status = :status, updatedAtMs = :updatedAtMs WHERE id = :scheduleId")
    /** Updates a schedule's status and last-updated time. */
    suspend fun updateStatus(scheduleId: String, status: String, updatedAtMs: Long)

    @Query("UPDATE scheduled_orders SET failureAcknowledged = 1, updatedAtMs = :updatedAtMs WHERE id = :scheduleId")
    /** Marks a failed schedule's alert as seen. */
    suspend fun markFailureAcknowledged(scheduleId: String, updatedAtMs: Long)

    @Query("SELECT * FROM scheduled_orders WHERE status = 'UPCOMING' AND nextExecutionAtUtcMs <= :nowUtcMs ORDER BY nextExecutionAtUtcMs ASC")
    /** Loads upcoming schedules whose placement time has arrived. */
    suspend fun getDueUpcomingSchedules(nowUtcMs: Long): List<ScheduledOrderEntity>

    @Query("SELECT * FROM scheduled_orders WHERE status = 'UPCOMING' ORDER BY nextExecutionAtUtcMs ASC")
    /** Loads all upcoming schedules ordered by their next placement time. */
    suspend fun getAllUpcomingSchedules(): List<ScheduledOrderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /** Saves a scheduled order, replacing any row with the same ID. */
    suspend fun upsertScheduledOrder(entity: ScheduledOrderEntity)

    @Query("SELECT * FROM scheduled_order_executions WHERE scheduleId = :scheduleId ORDER BY executedAtUtcMs DESC")
    /** Streams execution attempts for a schedule, newest first. */
    fun observeExecutionsForSchedule(scheduleId: String): Flow<List<ScheduledOrderExecutionEntity>>

    @Query("SELECT * FROM scheduled_order_executions WHERE idempotencyKey = :idempotencyKey LIMIT 1")
    /** Finds an execution attempt by its retry-safe key. */
    suspend fun getExecutionByIdempotencyKey(idempotencyKey: String): ScheduledOrderExecutionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /** Saves an execution attempt, replacing any row with the same key. */
    suspend fun upsertExecution(entity: ScheduledOrderExecutionEntity)

    @Transaction
    /** Saves an execution and its schedule state together in one database transaction. */
    suspend fun recordExecutionAndUpdateSchedule(
        schedule: ScheduledOrderEntity,
        execution: ScheduledOrderExecutionEntity
    ) {
        upsertScheduledOrder(schedule)
        upsertExecution(execution)
    }

    @Query("DELETE FROM scheduled_orders WHERE userId = :userId")
    /** Removes all scheduled orders belonging to this user. */
    suspend fun clearSchedulesForUser(userId: String)

    @Query("DELETE FROM scheduled_order_executions")
    /** Removes every saved scheduled-order execution record. */
    suspend fun clearAllExecutions()
}
