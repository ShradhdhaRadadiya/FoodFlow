package com.foodflow.domain.usecase.voice

import com.foodflow.domain.repository.VoiceAssistantRepository
import javax.inject.Inject

class ResumeVoiceAssistantUseCase @Inject constructor(
    private val repository: VoiceAssistantRepository
) {
    /** Resumes the assistant after a temporary pause. */
    suspend operator fun invoke() {
        repository.resume()
    }
}
