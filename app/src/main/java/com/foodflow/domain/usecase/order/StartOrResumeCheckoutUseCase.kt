package com.foodflow.domain.usecase.order

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.CheckoutAttempt
import com.foodflow.domain.model.PaymentMethod
import com.foodflow.domain.repository.OrderRepository
import javax.inject.Inject

class StartOrResumeCheckoutUseCase @Inject constructor(
    private val orderRepository: OrderRepository
) {
    /** Creates a checkout attempt or resumes one using the supplied address and payment details. */
    suspend operator fun invoke(
        addressId: String,
        paymentMethod: PaymentMethod,
        couponCode: String?
    ): AppResult<CheckoutAttempt> = orderRepository.startOrResumeCheckout(
        addressId = addressId,
        paymentMethod = paymentMethod,
        couponCode = couponCode
    )
}
