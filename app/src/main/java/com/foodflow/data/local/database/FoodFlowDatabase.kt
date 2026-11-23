package com.foodflow.data.local.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.foodflow.core.common.AppResult
import com.foodflow.core.common.decodeSafe
import com.foodflow.core.datastore.UserPreferencesDataStore
import com.foodflow.data.local.dao.AddressDao
import com.foodflow.data.local.dao.CartDao
import com.foodflow.data.local.dao.CatalogDao
import com.foodflow.data.local.dao.FavoriteDao
import com.foodflow.data.local.dao.MockServerStateDao
import com.foodflow.data.local.dao.OrderDao
import com.foodflow.data.local.dao.PendingOperationDao
import com.foodflow.data.local.dao.ReviewDao
import com.foodflow.data.local.dao.ScheduledOrderDao
import com.foodflow.data.local.dao.SearchHistoryDao
import com.foodflow.data.local.dao.SupportTicketDao
import com.foodflow.data.local.entity.AddressEntity
import com.foodflow.data.local.entity.CartItemEntity
import com.foodflow.data.local.entity.CheckoutAttemptEntity
import com.foodflow.data.local.entity.CouponEntity
import com.foodflow.data.local.entity.DeliveryPartnerEntity
import com.foodflow.data.local.entity.FavoriteEntity
import com.foodflow.data.local.entity.FoodCategoryEntity
import com.foodflow.data.local.entity.FoodItemEntity
import com.foodflow.data.local.entity.MockServerStateEntity
import com.foodflow.data.local.entity.OrderEntity
import com.foodflow.data.local.entity.OrderTimelineEventEntity
import com.foodflow.data.local.entity.PendingOperationEntity
import com.foodflow.data.local.entity.RestaurantEntity
import com.foodflow.data.local.entity.RestaurantReviewEntity
import com.foodflow.data.local.entity.ScheduledOrderEntity
import com.foodflow.data.local.entity.ScheduledOrderExecutionEntity
import com.foodflow.data.local.entity.SearchHistoryEntity
import com.foodflow.data.local.entity.SupportTicketEntity
import com.foodflow.data.local.mapper.toEntity
import com.foodflow.data.remote.dto.SeedDatasetDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FoodFlow Room Database — the Single Source of Truth for all customer-facing screens.
 * Why: Observing Room Flows decouples UI rendering from network availability so menus,
 * cart edits, favourites, search history, active orders, reviews, and schedules stay resilient offline.
 */
@Database(
    entities = [
        FoodCategoryEntity::class,
        RestaurantEntity::class,
        FoodItemEntity::class,
        CouponEntity::class,
        CartItemEntity::class,
        PendingOperationEntity::class,
        AddressEntity::class,
        DeliveryPartnerEntity::class,
        OrderEntity::class,
        OrderTimelineEventEntity::class,
        CheckoutAttemptEntity::class,
        FavoriteEntity::class,
        SearchHistoryEntity::class,
        SupportTicketEntity::class,
        MockServerStateEntity::class,
        RestaurantReviewEntity::class,
        ScheduledOrderEntity::class,
        ScheduledOrderExecutionEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class FoodFlowDatabase : RoomDatabase() {
    /** Provides access to catalog and menu queries in this database. */
    abstract fun catalogDao(): CatalogDao
    /** Provides access to saved cart items and their queued changes. */
    abstract fun cartDao(): CartDao
    /** Provides access to pending offline operations awaiting sync. */
    abstract fun pendingOperationDao(): PendingOperationDao
    /** Provides access to orders, checkout attempts, and order timelines. */
    abstract fun orderDao(): OrderDao
    /** Provides access to saved delivery addresses. */
    abstract fun addressDao(): AddressDao
    /** Provides access to saved restaurant favorites. */
    abstract fun favoriteDao(): FavoriteDao
    /** Provides access to the user's recent searches. */
    abstract fun searchHistoryDao(): SearchHistoryDao
    /** Provides access to saved support tickets. */
    abstract fun supportTicketDao(): SupportTicketDao
    /** Provides access to persisted state for the local mock backend. */
    abstract fun mockServerStateDao(): MockServerStateDao
    /** Provides access to restaurant reviews and rating updates. */
    abstract fun reviewDao(): ReviewDao
    /** Provides access to scheduled orders and their execution history. */
    abstract fun scheduledOrderDao(): ScheduledOrderDao

    companion object {
        const val DB_NAME = "foodflow.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            /** Adds cart item insertion time and backfills existing rows during the 1-to-2 upgrade. */
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE cart_items ADD COLUMN addedAtMs INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "UPDATE cart_items SET addedAtMs = updatedAtMs WHERE addedAtMs = 0"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            /** Creates review tables and indexes during the 2-to-3 database upgrade. */
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `restaurant_reviews` (
                        `id` TEXT NOT NULL,
                        `restaurantId` TEXT NOT NULL,
                        `orderId` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `userName` TEXT NOT NULL,
                        `rating` INTEGER NOT NULL,
                        `comment` TEXT NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_restaurant_reviews_orderId` ON `restaurant_reviews` (`orderId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_restaurant_reviews_restaurantId_createdAtMs` ON `restaurant_reviews` (`restaurantId`, `createdAtMs`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scheduled_orders` (
                        `id` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `restaurantId` TEXT NOT NULL,
                        `restaurantName` TEXT NOT NULL,
                        `restaurantAddress` TEXT NOT NULL,
                        `restaurantLat` REAL NOT NULL,
                        `restaurantLng` REAL NOT NULL,
                        `addressId` TEXT NOT NULL,
                        `deliveryAddress` TEXT NOT NULL,
                        `deliveryLat` REAL NOT NULL,
                        `deliveryLng` REAL NOT NULL,
                        `deliveryInstructions` TEXT NOT NULL,
                        `itemsJson` TEXT NOT NULL,
                        `authorizedSubtotal` REAL NOT NULL,
                        `authorizedDeliveryFee` REAL NOT NULL,
                        `authorizedPackagingCharge` REAL NOT NULL,
                        `authorizedTaxes` REAL NOT NULL,
                        `authorizedDiscount` REAL NOT NULL,
                        `authorizedTotalPayable` REAL NOT NULL,
                        `couponCode` TEXT,
                        `paymentMethod` TEXT NOT NULL,
                        `priceConsentPolicy` TEXT NOT NULL,
                        `recurrenceRuleJson` TEXT NOT NULL,
                        `nextExecutionAtUtcMs` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `completedOccurrences` INTEGER NOT NULL,
                        `lastExecutedAtUtcMs` INTEGER,
                        `lastGeneratedOrderId` TEXT,
                        `lastFailureCategory` TEXT NOT NULL,
                        `lastFailureReason` TEXT,
                        `retryCount` INTEGER NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_orders_userId_status` ON `scheduled_orders` (`userId`, `status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_orders_nextExecutionAtUtcMs` ON `scheduled_orders` (`nextExecutionAtUtcMs`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scheduled_order_executions` (
                        `id` TEXT NOT NULL,
                        `scheduleId` TEXT NOT NULL,
                        `occurrenceNumber` INTEGER NOT NULL,
                        `idempotencyKey` TEXT NOT NULL,
                        `scheduledForUtcMs` INTEGER NOT NULL,
                        `executedAtUtcMs` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `failureCategory` TEXT NOT NULL,
                        `failureReason` TEXT,
                        `generatedOrderId` TEXT,
                        `quotedTotalPayable` REAL NOT NULL,
                        `authorizedTotalPayable` REAL NOT NULL,
                        `paymentStatus` TEXT NOT NULL,
                        `retryCount` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_scheduled_order_executions_idempotencyKey` ON `scheduled_order_executions` (`idempotencyKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_order_executions_scheduleId_executedAtUtcMs` ON `scheduled_order_executions` (`scheduleId`, `executedAtUtcMs`)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            /** Adds scheduled-order failure and creation-key fields during the 3-to-4 upgrade. */
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE scheduled_orders ADD COLUMN failureAcknowledged INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE scheduled_orders ADD COLUMN creationIdempotencyKey TEXT"
                )
            }
        }
    }
}

/**
 * Loads `assets/foodflow_seed.json` via Kotlinx Serialization once on initial launch,
 * without ever overwriting subsequent user cart/order/address modifications.
 * Also provides an explicit demo-data reset hook for the Developer Scenario Lab.
 */
@Singleton
class DatabaseSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: FoodFlowDatabase,
    private val json: Json,
    private val preferencesDataStore: UserPreferencesDataStore
) {
    private val mutex = Mutex()

    /** Reads and parses the bundled JSON catalog, returning null if it is invalid or missing. */
    suspend fun loadSeedDataset(): SeedDatasetDto? {
        val raw = runCatching {
            context.assets.open(SEED_ASSET_FILE).bufferedReader().use { it.readText() }
        }.getOrElse { error ->
            Log.e(TAG, "Failed to read seed JSON asset", error)
            return null
        }
        return when (val parsed = json.decodeSafe<SeedDatasetDto>(raw)) {
            is AppResult.Success -> parsed.data
            is AppResult.Error -> {
                Log.e(TAG, "Failed to parse seed JSON: ${parsed.error.technicalDetail}")
                null
            }
        }
    }

    /** Seeds the catalog only when the database or initialization flag shows it is needed. */
    suspend fun seedIfEmpty(userId: String = DEFAULT_USER_ID): Boolean = mutex.withLock {
        val existingCount = database.catalogDao().countRestaurants()
        val alreadySeeded = preferencesDataStore.getSnapshot().seedInitialized
        if (existingCount > 0 && alreadySeeded) {
            return false
        }
        return seedInternal(userId = userId, clearUserTables = false)
    }

    /** Replaces local demo data and clears user data within one database transaction. */
    suspend fun resetLocalDemoData(userId: String = DEFAULT_USER_ID): Boolean = mutex.withLock {
        return seedInternal(userId = userId, clearUserTables = true)
    }

    /** Writes seed data and optionally clears user tables before inserting it. */
    private suspend fun seedInternal(userId: String, clearUserTables: Boolean): Boolean {
        val dataset = loadSeedDataset() ?: return false
        val now = System.currentTimeMillis()

        database.withTransaction {
            if (clearUserTables) {
                database.cartDao().clearUserCart(userId)
                database.pendingOperationDao().clearForUser(userId)
                database.orderDao().clearOrdersForUser(userId)
                database.orderDao().clearCheckoutAttemptsForUser(userId)
                database.orderDao().clearAllTimelineEvents()
                database.favoriteDao().clearForUser(userId)
                database.searchHistoryDao().clearForUser(userId)
                database.supportTicketDao().clearForUser(userId)
                database.reviewDao().clearAllReviews()
                database.scheduledOrderDao().clearSchedulesForUser(userId)
                database.scheduledOrderDao().clearAllExecutions()
            }

            database.catalogDao().upsertCategories(dataset.categories.map { it.toEntity() })
            database.catalogDao().upsertRestaurants(dataset.restaurants.map { it.toEntity(now) })
            database.catalogDao().upsertFoodItems(dataset.foodItems.map { it.toEntity(json) })
            database.catalogDao().upsertCoupons(dataset.coupons.map { it.toEntity(now) })
            database.catalogDao().upsertDeliveryPartners(dataset.deliveryPartners.map { it.toEntity() })
            database.addressDao().upsertAddresses(dataset.addresses.map { it.toEntity(userId) })

            val seededOrders = dataset.sampleOrders.map { it.toEntity(json, now, userId) }
            database.orderDao().upsertOrders(seededOrders)

            // Seed initial timeline events for the active sample order so Tracking looks rich immediately
            seededOrders.filter { it.orderStatus != "DELIVERED" && it.orderStatus != "CANCELLED" }
                .forEach { order ->
                    database.orderDao().insertTimelineEvent(
                        OrderTimelineEventEntity(
                            id = "evt_seed_${order.id}_1",
                            orderId = order.id,
                            sequenceNumber = 1L,
                            eventType = "ORDER_PLACED",
                            title = "Order Placed",
                            description = "Order received by ${order.restaurantName}.",
                            timestampMs = order.createdAtMs
                        )
                    )
                    database.orderDao().insertTimelineEvent(
                        OrderTimelineEventEntity(
                            id = "evt_seed_${order.id}_2",
                            orderId = order.id,
                            sequenceNumber = 2L,
                            eventType = "ORDER_ACCEPTED",
                            title = "Restaurant Accepted",
                            description = "Kitchen started preparing your dishes fresh.",
                            timestampMs = order.createdAtMs + 180_000L
                        )
                    )
                }

            // Seed 3 initial favourites so the Home Favourites strip is populated on first run
            listOf("rest_1", "rest_3", "rest_5").forEach { restId ->
                database.favoriteDao().upsertFavorite(
                    FavoriteEntity(
                        userId = userId,
                        restaurantId = restId,
                        syncStatus = "SYNCED",
                        updatedAtMs = now
                    )
                )
            }
        }

        preferencesDataStore.setSeedInitialized(true)
        preferencesDataStore.updateLastCatalogSync(now)
        return true
    }

    companion object {
        const val SEED_ASSET_FILE = "foodflow_seed.json"
        const val DEFAULT_USER_ID = "user_demo_01"
        private const val TAG = "DatabaseSeeder"
    }
}
