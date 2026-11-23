package com.foodflow.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foodflow.data.local.database.FoodFlowDatabase
import com.foodflow.data.local.entity.CartItemEntity
import com.foodflow.data.local.entity.FoodItemEntity
import com.foodflow.data.local.entity.MockServerStateEntity
import com.foodflow.data.local.entity.OrderEntity
import com.foodflow.data.local.entity.OrderTimelineEventEntity
import com.foodflow.data.local.entity.PendingOperationEntity
import com.foodflow.data.local.entity.RestaurantEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class FoodFlowDaoRobolectricTest {

    private lateinit var db: FoodFlowDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, FoodFlowDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `catalogDao search matches restaurants by name or cuisine and food items by name`() = runTest {
        db.catalogDao().upsertRestaurants(
            listOf(
                RestaurantEntity(
                    id = "r1", name = "Royal Biryani Darbar", cuisinesCsv = "Biryani, Mughlai",
                    rating = 4.7, ratingCount = 500, deliveryTimeMin = 25, deliveryFee = 25.0,
                    minOrderAmount = 149.0, offerText = null, isPureVeg = false, isOpen = true,
                    isPopular = true, isRecommended = true, imageKey = "indian",
                    address = "SG Highway", distanceKm = 2.0, latitude = 23.02, longitude = 72.57,
                    lastUpdatedMs = 1L
                ),
                RestaurantEntity(
                    id = "r2", name = "Dosa Plaza Express", cuisinesCsv = "South Indian",
                    rating = 4.5, ratingCount = 400, deliveryTimeMin = 20, deliveryFee = 20.0,
                    minOrderAmount = 99.0, offerText = null, isPureVeg = true, isOpen = true,
                    isPopular = false, isRecommended = true, imageKey = "south",
                    address = "Navrangpura", distanceKm = 1.5, latitude = 23.03, longitude = 72.56,
                    lastUpdatedMs = 1L
                )
            )
        )
        db.catalogDao().upsertFoodItems(
            listOf(
                FoodItemEntity(
                    id = "f1", restaurantId = "r1", name = "Paneer Dum Biryani",
                    description = "Saffron basmati rice", categoryName = "Biryani", price = 290.0,
                    originalPrice = null, isVeg = true, isAvailable = true, isRecommended = true,
                    isBestseller = true, rating = 4.6, imageKey = "indian", addonsJson = "[]",
                    spiceLevel = "Medium"
                )
            )
        )

        val byCuisine = db.catalogDao().searchRestaurants("Mughlai").first()
        assertEquals(1, byCuisine.size)
        assertEquals("r1", byCuisine.first().id)

        val dishes = db.catalogDao().searchFoodItems("Paneer").first()
        assertEquals(1, dishes.size)
        assertEquals("f1", dishes.first().id)
    }

    @Test
    fun `cartDao atomic outbox transaction writes cart item and pending operation together`() = runTest {
        val cartItem = CartItemEntity(
            itemId = "f1", userId = "u1", restaurantId = "r1", restaurantName = "Kitchen 1",
            foodName = "Masala Dosa", unitPrice = 150.0, quantity = 2, isVeg = true,
            imageKey = "south", selectedAddonsJson = "[]", cookingInstructions = "Extra crisp",
            syncStatus = "PENDING", updatedAtMs = 100L
        )
        val outboxOp = PendingOperationEntity(
            operationId = "op_1", userId = "u1", entityType = "CART", operationType = "UPSERT",
            itemId = "f1", restaurantId = "r1", quantity = 2, expectedUnitPrice = 150.0,
            payloadJson = "Extra crisp", status = "PENDING", createdAtMs = 100L, updatedAtMs = 100L
        )

        db.cartDao().upsertCartWithOutbox(cartItem, outboxOp, clearOtherRestaurant = false)

        val items = db.cartDao().getCartItems("u1")
        val pendingOps = db.pendingOperationDao().getPendingOperations("u1")
        assertEquals(1, items.size)
        assertEquals(2, items.first().quantity)
        assertEquals(1, pendingOps.size)
        assertEquals("op_1", pendingOps.first().operationId)
    }

    @Test
    fun `orderDao queries by idempotencyKey and keeps timeline sorted by sequenceNumber`() = runTest {
        val order = OrderEntity(
            id = "FF-1001", userId = "u1", idempotencyKey = "idem_unique_1",
            restaurantId = "r1", restaurantName = "Spice Route", restaurantAddress = "SG Hwy",
            restaurantLat = 23.02, restaurantLng = 72.57, deliveryAddress = "Satellite",
            deliveryLat = 23.04, deliveryLng = 72.53, deliveryInstructions = "", itemsJson = "[]",
            subtotal = 300.0, deliveryFee = 35.0, packagingCharge = 12.0, taxes = 15.0,
            discount = 0.0, totalPayable = 362.0, couponCode = null, orderStatus = "PLACED",
            paymentStatus = "SUCCESS", paymentMethod = "UPI", estimatedDeliveryMinutes = 25,
            isDelayed = false, delayReason = null, deliveryPartnerId = null, riderLat = null,
            riderLng = null, riderProgress = 0f, riderUpdatedAtMs = 0L, lastEventSequence = 1L,
            ratingGiven = null, reviewComment = null, createdAtMs = 100L, updatedAtMs = 100L
        )
        db.orderDao().upsertOrder(order)

        val fetched = db.orderDao().getOrderByIdempotencyKey("idem_unique_1")
        assertNotNull(fetched)
        assertEquals("FF-1001", fetched?.id)

        // Insert out-of-order sequence events and verify query returns them sorted ascending
        db.orderDao().insertTimelineEvent(
            OrderTimelineEventEntity("e3", "FF-1001", 3L, "FOOD_PREPARING", "Preparing", "Cooking", 300L)
        )
        db.orderDao().insertTimelineEvent(
            OrderTimelineEventEntity("e1", "FF-1001", 1L, "ORDER_PLACED", "Placed", "Created", 100L)
        )
        db.orderDao().insertTimelineEvent(
            OrderTimelineEventEntity("e2", "FF-1001", 2L, "ORDER_ACCEPTED", "Accepted", "Kitchen OK", 200L)
        )

        val timeline = db.orderDao().observeTimeline("FF-1001").first()
        assertEquals(listOf(1L, 2L, 3L), timeline.map { it.sequenceNumber })
    }

    @Test
    fun `mockServerStateDao persists and resets independently from client catalog`() = runTest {
        db.mockServerStateDao().putValue(MockServerStateEntity("snap", "{\"v\":1}", 100L))
        assertEquals("{\"v\":1}", db.mockServerStateDao().getValue("snap")?.valueJson)

        db.mockServerStateDao().clearAll()
        assertNull(db.mockServerStateDao().getValue("snap"))
    }
}
