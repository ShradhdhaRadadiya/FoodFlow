package com.foodflow.domain.usecase.cart

import com.foodflow.core.common.AppResult
import com.foodflow.domain.model.CartReconciliationNotice
import com.foodflow.domain.repository.CartRepository
import javax.inject.Inject

class SyncPendingCartOperationsUseCase @Inject constructor(
    private val cartRepository: CartRepository
) {
    /** Sends queued cart changes for sync and returns any reconciliation notices. */
    suspend operator fun invoke(): AppResult<List<CartReconciliationNotice>> =
        cartRepository.syncPendingOperations()
}
