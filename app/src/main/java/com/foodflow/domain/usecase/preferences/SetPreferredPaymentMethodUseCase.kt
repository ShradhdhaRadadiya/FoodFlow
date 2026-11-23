package com.foodflow.domain.usecase.preferences

import com.foodflow.domain.model.PaymentMethod
import com.foodflow.domain.repository.UserPreferencesRepository
import javax.inject.Inject

class SetPreferredPaymentMethodUseCase @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository
) {
    /** Saves the payment method to preselect during future checkouts. */
    suspend operator fun invoke(method: PaymentMethod) =
        userPreferencesRepository.setPreferredPayment(method)
}
