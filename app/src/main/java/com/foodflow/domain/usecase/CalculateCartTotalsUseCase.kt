package com.foodflow.domain.usecase

import com.foodflow.core.common.Money
import com.foodflow.core.common.roundTo2Decimals
import com.foodflow.domain.model.CartItem
import com.foodflow.domain.model.CartPriceBreakdown
import com.foodflow.domain.model.Coupon
import com.foodflow.domain.model.DiscountType
import javax.inject.Inject

/**
 * Pure domain business logic for cart totals, GST (5% Indian restaurant GST),
 * packaging charges, delivery fees, and coupon discounts.
 */
class CalculateCartTotalsUseCase @Inject constructor() {

    /**
     * Builds the cart breakdown in stages: subtotal from each item's unit price plus per-unit add-ons,
     * capped packaging, delivery eligibility, then an eligible coupon. Tax is calculated after the
     * applicable item discount; free-delivery coupons instead waive delivery without reducing the
     * taxable item subtotal. Money values are rounded at the calculation boundaries before returning
     * the complete amount due. Empty carts return a zero breakdown.
     */
    operator fun invoke(
        items: List<CartItem>,
        baseDeliveryFee: Double = DEFAULT_DELIVERY_FEE,
        coupon: Coupon? = null,
        nowMs: Long = System.currentTimeMillis()
    ): CartPriceBreakdown {
        if (items.isEmpty()) return CartPriceBreakdown()

        val subtotalMoney = items.fold(Money.ZERO) { acc, item ->
            val unitWithAddons = Money(item.unitPrice + item.addonsTotalPerUnit)
            acc + (unitWithAddons * item.quantity)
        }
        val subtotal = subtotalMoney.amount
        val packaging = (items.sumOf { it.quantity } * PACKAGING_PER_ITEM).coerceAtMost(MAX_PACKAGING)
        var effectiveDelivery = if (subtotal >= FREE_DELIVERY_THRESHOLD) 0.0 else baseDeliveryFee

        var discount = 0.0
        var appliedCode: String? = null

        if (coupon != null && !coupon.isExpired(nowMs) && subtotal >= coupon.minOrderAmount) {
            appliedCode = coupon.code
            discount = when (coupon.discountType) {
                DiscountType.PERCENTAGE -> {
                    ((subtotal * coupon.discountValue) / 100.0)
                        .coerceAtMost(coupon.maxDiscount)
                        .roundTo2Decimals()
                }
                DiscountType.FLAT -> {
                    coupon.discountValue
                        .coerceAtMost(coupon.maxDiscount)
                        .coerceAtMost(subtotal)
                        .roundTo2Decimals()
                }
                DiscountType.FREE_DELIVERY -> {
                    val waived = effectiveDelivery
                    effectiveDelivery = 0.0
                    waived
                }
            }
        }

        val taxableBase = (subtotal - if (coupon?.discountType == DiscountType.FREE_DELIVERY) 0.0 else discount)
            .coerceAtLeast(0.0)
        val taxes = (taxableBase * GST_RATE).roundTo2Decimals()
        val rawTotal = subtotal + effectiveDelivery + packaging + taxes -
            if (coupon?.discountType == DiscountType.FREE_DELIVERY) 0.0 else discount
        val totalPayable = rawTotal.coerceAtLeast(0.0).roundTo2Decimals()

        return CartPriceBreakdown(
            subtotal = subtotal,
            deliveryFee = effectiveDelivery.roundTo2Decimals(),
            packagingCharge = packaging.roundTo2Decimals(),
            taxes = taxes,
            discount = discount,
            totalPayable = totalPayable,
            appliedCouponCode = appliedCode
        )
    }

    companion object {
        const val DEFAULT_DELIVERY_FEE = 35.0
        const val PACKAGING_PER_ITEM = 12.0
        const val MAX_PACKAGING = 48.0
        const val FREE_DELIVERY_THRESHOLD = 499.0
        const val GST_RATE = 0.05
    }
}
