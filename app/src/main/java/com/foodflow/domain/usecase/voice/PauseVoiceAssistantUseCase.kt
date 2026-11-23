package com.foodflow.domain.usecase.voice

import com.foodflow.domain.repository.VoiceAssistantRepository
import javax.inject.Inject

class PauseVoiceAssistantUseCase @Inject constructor(
    private val repository: VoiceAssistantRepository
) {
    /** Pauses the assistant while the app temporarily leaves the foreground. */
    suspend operator fun invoke() {
        repository.pause()
    }
}
