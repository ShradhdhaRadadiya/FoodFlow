package com.foodflow.feature.support

import com.foodflow.core.mvi.MviEffect
import com.foodflow.core.mvi.MviIntent
import com.foodflow.core.mvi.MviState
import com.foodflow.domain.model.SupportIssueType
import com.foodflow.domain.model.SupportTicket

data class SupportUiState(
    val orderId: String? = null,
    val selectedIssueType: SupportIssueType = SupportIssueType.MISSING_ITEM,
    val description: String = "",
    val isSubmitting: Boolean = false,
    val tickets: List<SupportTicket> = emptyList()
) : MviState

sealed interface SupportIntent : MviIntent {
    data class InitializeOrder(val orderId: String?) : SupportIntent
    data class SelectIssueType(val type: SupportIssueType) : SupportIntent
    data class DescriptionChanged(val text: String) : SupportIntent
    data object SubmitTicket : SupportIntent
}

sealed interface SupportEffect : MviEffect {
    data class ShowMessage(val message: String) : SupportEffect
}
