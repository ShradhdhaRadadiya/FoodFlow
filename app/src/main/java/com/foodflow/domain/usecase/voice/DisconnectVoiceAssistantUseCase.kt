package com.foodflow.domain.usecase.voice

import com.foodflow.domain.repository.VoiceAssistantRepository
import javax.inject.Inject

class DisconnectVoiceAssistantUseCase @Inject constructor(
    private val repository: VoiceAssistantRepository
) {
    /** Disconnects the assistant and records whether the user explicitly closed it. */
    suspend operator fun invoke(isExplicitClose: Boolean = true) {
        repository.disconnect(isExplicitClose = isExplicitClose)
    }
}
