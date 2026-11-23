package com.foodflow.core

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.foodflow.core.common.AppResult
import com.foodflow.core.invoice.AndroidInvoicePdfGenerator
import com.foodflow.core.invoice.InvoiceIntentHelper
import com.foodflow.domain.model.Order
import com.foodflow.domain.model.OrderItem
import com.foodflow.domain.model.OrderStatus
import com.foodflow.domain.model.PaymentMethod
import com.foodflow.domain.model.PaymentStatus
import com.foodflow.testutil.TestDispatcherProvider
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class InvoicePdfGeneratorTest {

    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun `generates valid PDF invoice document and persists non-empty file`() = runTest(testDispatcher) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val generator = AndroidInvoicePdfGenerator(context, TestDispatcherProvider(testDispatcher))

        val order = Order(
            id = "FF-2026-9001",
            userId = "user_1",
            idempotencyKey = "idem_9001",
            restaurantId = "rest_spice_garden",
            restaurantName = "Spice Garden Kitchen",
            restaurantAddress = "SG Highway, Ahmedabad",
            restaurantLat = 23.0225,
            restaurantLng = 72.5714,
            deliveryAddress = "14 Satellite Road, Ahmedabad",
            deliveryLat = 23.0301,
            deliveryLng = 72.5178,
            deliveryInstructions = "Leave at security desk",
            items = listOf(
                OrderItem(
                    foodId = "food_101",
                    foodName = "Paneer Butter Masala",
                    quantity = 2,
                    unitPrice = 280.0,
                    isVeg = true,
                    addonsSummary = "Extra Butter"
                )
            ),
            subtotal = 560.0,
            deliveryFee = 0.0,
            packagingCharge = 20.0,
            taxes = 28.0,
            discount = 100.0,
            totalPayable = 508.0,
            couponCode = "FLOW50",
            orderStatus = OrderStatus.DELIVERED,
            paymentStatus = PaymentStatus.SUCCESS,
            paymentMethod = PaymentMethod.UPI,
            estimatedDeliveryMinutes = 25,
            createdAtMs = 1_735_000_000_000L,
            updatedAtMs = 1_735_001_500_000L
        )

        val rawBytes = generator.renderInvoiceBytes(order, "Aarav Sharma", "+91 98765 43210")
        assertTrue("Expected generated PDF bytes to be non-empty", rawBytes.isNotEmpty())
        val header = String(rawBytes.take(5).toByteArray())
        assertTrue("Expected PDF header (%PDF-), got: $header", header.startsWith("%PDF-"))

        val result = generator.generateAndSaveInvoice(order, "Aarav Sharma", "+91 98765 43210")
        assertTrue(result is AppResult.Success)
        val doc = (result as AppResult.Success).data
        assertEquals("FoodFlow_Invoice_FF-2026-9001.pdf", doc.fileName)
        assertTrue(doc.uriString.isNotBlank())

        val internalCopy = File(File(context.filesDir, "invoices"), doc.fileName)
        assertTrue(internalCopy.exists() && internalCopy.length() > 100L)

        val openIntent = InvoiceIntentHelper.buildOpenPdfIntent(doc.uriString)
        assertEquals(Intent.ACTION_VIEW, openIntent.action)
        assertEquals("application/pdf", openIntent.type)

        val shareIntent = InvoiceIntentHelper.buildSharePdfIntent(doc.uriString, order.id)
        assertEquals(Intent.ACTION_CHOOSER, shareIntent.action)
    }
}
