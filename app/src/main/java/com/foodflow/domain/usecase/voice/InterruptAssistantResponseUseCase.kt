package com.foodflow.domain.usecase.voice

import com.foodflow.domain.repository.VoiceAssistantRepository
import javax.inject.Inject

class InterruptAssistantResponseUseCase @Inject constructor(
    private val repository: VoiceAssistantRepository
) {
    /** Interrupts the assistant's current response with the supplied reason. */
    suspend operator fun invoke(reason: String = "User interrupted response") {
        repository.interruptResponse(reason = reason)
    }
}
