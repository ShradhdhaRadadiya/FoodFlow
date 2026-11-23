package com.foodflow.feature.support

import androidx.lifecycle.viewModelScope
import com.foodflow.core.common.AppResult
import com.foodflow.core.mvi.MviViewModel
import com.foodflow.domain.usecase.support.CreateSupportTicketUseCase
import com.foodflow.domain.usecase.support.ObserveSupportTicketsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SupportViewModel @Inject constructor(
    private val observeSupportTicketsUseCase: ObserveSupportTicketsUseCase,
    private val createSupportTicketUseCase: CreateSupportTicketUseCase
) : MviViewModel<SupportUiState, SupportIntent, SupportEffect>(SupportUiState()) {

    init {
        viewModelScope.launch {
            observeSupportTicketsUseCase().collect { list ->
                updateState { copy(tickets = list) }
            }
        }
    }

    /** Applies support-form edits and handles ticket submission. */
    override fun onIntent(intent: SupportIntent) {
        when (intent) {
            is SupportIntent.InitializeOrder -> updateState { copy(orderId = intent.orderId) }
            is SupportIntent.SelectIssueType -> updateState { copy(selectedIssueType = intent.type) }
            is SupportIntent.DescriptionChanged -> updateState { copy(description = intent.text) }
            SupportIntent.SubmitTicket -> submit()
        }
    }

    /** Validates and submits the support form, then reports its result. */
    private fun submit() {
        viewModelScope.launch {
            updateState { copy(isSubmitting = true) }
            when (
                val res = createSupportTicketUseCase(
                    orderId = currentState.orderId,
                    issueType = currentState.selectedIssueType,
                    description = currentState.description.ifBlank { currentState.selectedIssueType.subtitle }
                )
            ) {
                is AppResult.Success -> {
                    updateState { copy(description = "", isSubmitting = false) }
                    emitEffect(SupportEffect.ShowMessage("Ticket ${res.data.id} logged: ${res.data.resolutionNote}"))
                }
                is AppResult.Error -> {
                    updateState { copy(isSubmitting = false) }
                    emitEffect(SupportEffect.ShowMessage(res.error.userMessage))
                }
            }
        }
    }
}
