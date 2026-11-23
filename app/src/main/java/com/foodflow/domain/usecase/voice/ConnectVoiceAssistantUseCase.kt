package com.foodflow.domain.usecase.voice

import com.foodflow.domain.repository.VoiceAssistantRepository
import javax.inject.Inject

class ConnectVoiceAssistantUseCase @Inject constructor(
    private val repository: VoiceAssistantRepository
) {
    /** Connects the assistant, optionally treating this as a user-requested retry. */
    suspend operator fun invoke(isManualRetry: Boolean = false) {
        repository.connect(isManualRetry = isManualRetry)
    }
}
