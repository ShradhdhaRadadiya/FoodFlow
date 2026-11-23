package com.foodflow.domain

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.DiscountType
import com.foodflow.domain.model.FoodAddon
import com.foodflow.domain.usecase.CalculateCartTotalsUseCase
import com.foodflow.domain.usecase.ValidateCouponUseCase
import com.foodflow.testutil.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CartAndCouponUseCaseTest {

    private val calculateTotals = CalculateCartTotalsUseCase()
    private val validateCoupon = ValidateCouponUseCase()
    private val fixedNow = 1_750_000_000_000L

    @Test
    fun `empty cart produces zero breakdown`() {
        val result = calculateTotals(emptyList())
        assertEquals(0.0, result.subtotal, 0.001)
        assertEquals(0.0, result.totalPayable, 0.001)
        assertNull(result.appliedCouponCode)
    }

    @Test
    fun `calculates subtotal with addons packaging cap delivery fee and 5 percent GST`() {
        val items = listOf(
            TestFixtures.cartItem(
                itemId = "f1",
                unitPrice = 200.0,
                quantity = 2,
                addons = listOf(FoodAddon("a1", "Extra Cheese", 30.0))
            )
        )
        // Subtotal = (200 + 30) * 2 = 460.0 (< 499 free delivery threshold)
        // Packaging = 2 * 12 = 24.0
        // Delivery = 35.0
        // GST = 5% of 460 = 23.0
        // Total = 460 + 35 + 24 + 23 = 542.0
        val result = calculateTotals(items, baseDeliveryFee = 35.0, nowMs = fixedNow)
        assertEquals(460.0, result.subtotal, 0.001)
        assertEquals(35.0, result.deliveryFee, 0.001)
        assertEquals(24.0, result.packagingCharge, 0.001)
        assertEquals(23.0, result.taxes, 0.001)
        assertEquals(542.0, result.totalPayable, 0.001)
    }

    @Test
    fun `orders at or above free delivery threshold waive delivery fee and cap packaging at 48`() {
        val items = listOf(
            TestFixtures.cartItem(itemId = "f1", unitPrice = 100.0, quantity = 6)
        )
        // Subtotal = 600.0 >= 499 -> Delivery = 0.0
        // Packaging = min(6 * 12, 48) = 48.0
        // GST = 5% of 600 = 30.0
        // Total = 600 + 0 + 48 + 30 = 678.0
        val result = calculateTotals(items, baseDeliveryFee = 40.0, nowMs = fixedNow)
        assertEquals(600.0, result.subtotal, 0.001)
        assertEquals(0.0, result.deliveryFee, 0.001)
        assertEquals(48.0, result.packagingCharge, 0.001)
        assertEquals(30.0, result.taxes, 0.001)
        assertEquals(678.0, result.totalPayable, 0.001)
    }

    @Test
    fun `percentage coupon respects maxDiscount cap`() {
        val items = listOf(TestFixtures.cartItem(unitPrice = 400.0, quantity = 1))
        val coupon = TestFixtures.coupon(
            code = "FLOW50",
            type = DiscountType.PERCENTAGE,
            value = 50.0,
            minOrder = 199.0,
            maxDiscount = 100.0,
            expiresAtMs = fixedNow + 10_000L
        )
        // 50% of 400 = 200 -> capped at 100
        // Taxable = 400 - 100 = 300 -> GST = 15.0
        // Delivery = 35.0, Packaging = 12.0 -> Total = 400 + 35 + 12 + 15 - 100 = 362.0
        val result = calculateTotals(items, baseDeliveryFee = 35.0, coupon = coupon, nowMs = fixedNow)
        assertEquals("FLOW50", result.appliedCouponCode)
        assertEquals(100.0, result.discount, 0.001)
        assertEquals(15.0, result.taxes, 0.001)
        assertEquals(362.0, result.totalPayable, 0.001)
    }

    @Test
    fun `flat coupon is applied accurately and free delivery coupon waives delivery charge`() {
        val items = listOf(TestFixtures.cartItem(unitPrice = 300.0, quantity = 1))
        val flatCoupon = TestFixtures.coupon(
            code = "FLAT75",
            type = DiscountType.FLAT,
            value = 75.0,
            minOrder = 250.0,
            maxDiscount = 75.0,
            expiresAtMs = fixedNow + 10_000L
        )
        val flatResult = calculateTotals(items, baseDeliveryFee = 35.0, coupon = flatCoupon, nowMs = fixedNow)
        assertEquals(75.0, flatResult.discount, 0.001)
        assertEquals("FLAT75", flatResult.appliedCouponCode)

        val freeDelCoupon = TestFixtures.coupon(
            code = "FREEDEL",
            type = DiscountType.FREE_DELIVERY,
            value = 0.0,
            minOrder = 149.0,
            maxDiscount = 60.0,
            expiresAtMs = fixedNow + 10_000L
        )
        val freeDelResult = calculateTotals(items, baseDeliveryFee = 35.0, coupon = freeDelCoupon, nowMs = fixedNow)
        assertEquals(0.0, freeDelResult.deliveryFee, 0.001)
        assertEquals(35.0, freeDelResult.discount, 0.001)
        assertEquals("FREEDEL", freeDelResult.appliedCouponCode)
    }

    @Test
    fun `validateCoupon rejects missing expired and below-minimum coupons`() {
        val missing = validateCoupon(null, subtotal = 300.0, nowMs = fixedNow)
        assertTrue(missing is AppResult.Error)

        val expiredCoupon = TestFixtures.coupon(
            code = "EXPIRED2024",
            expiresAtMs = fixedNow - 10_000L,
            isActive = false
        )
        val expiredRes = validateCoupon(expiredCoupon, subtotal = 500.0, nowMs = fixedNow)
        assertTrue(expiredRes is AppResult.Error)
        assertTrue((expiredRes as AppResult.Error).error.userMessage.contains("expired", ignoreCase = true))

        val highMinCoupon = TestFixtures.coupon(
            code = "PARTY500",
            minOrder = 500.0,
            expiresAtMs = fixedNow + 10_000L
        )
        val belowMinRes = validateCoupon(highMinCoupon, subtotal = 300.0, nowMs = fixedNow)
        assertTrue(belowMinRes is AppResult.Error)
        assertTrue((belowMinRes as AppResult.Error).error.userMessage.contains("200"))

        val validRes = validateCoupon(highMinCoupon, subtotal = 550.0, nowMs = fixedNow)
        assertTrue(validRes is AppResult.Success)
    }
}
