package com.foodflow.domain.usecase

import com.foodflow.core.common.AppError
import com.foodflow.core.common.AppResult
import com.foodflow.core.common.roundTo2Decimals
import com.foodflow.domain.model.Coupon
import javax.inject.Inject

class ValidateCouponUseCase @Inject constructor() {
    /**
     * Validates a coupon in order: existence, expiry at [nowMs], then minimum subtotal. A failed
     * check returns a user-readable validation error (including the rounded amount still needed);
     * only a coupon that passes every check is returned as success.
     */
    operator fun invoke(
        coupon: Coupon?,
        subtotal: Double,
        nowMs: Long = System.currentTimeMillis()
    ): AppResult<Coupon> {
        if (coupon == null) {
            return AppResult.Error(AppError.Validation("Coupon code not found. Try FLOW50 or FEAST20."))
        }
        if (coupon.isExpired(nowMs)) {
            return AppResult.Error(
                AppError.Validation("Coupon ${coupon.code} has expired. Choose an active offer.")
            )
        }
        if (subtotal < coupon.minOrderAmount) {
            val remaining = (coupon.minOrderAmount - subtotal).roundTo2Decimals()
            return AppResult.Error(
                AppError.Validation("Add ₹${remaining.toInt()} more to unlock ${coupon.code}.")
            )
        }
        return AppResult.Success(coupon)
    }
}
