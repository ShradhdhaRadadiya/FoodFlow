package com.foodflow.domain.usecase.cart

import com.foodflow.domain.repository.CartRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObservePendingCartSyncCountUseCase @Inject constructor(
    private val cartRepository: CartRepository
) {
    /** Provides the live count of cart changes waiting to sync. */
    operator fun invoke(): Flow<Int> = cartRepository.observePendingOperationCount()
}
