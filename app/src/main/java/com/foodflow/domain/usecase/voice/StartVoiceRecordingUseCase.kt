package com.foodflow.domain.usecase.voice

import com.foodflow.domain.repository.VoiceAssistantRepository
import javax.inject.Inject

class StartVoiceRecordingUseCase @Inject constructor(
    private val repository: VoiceAssistantRepository
) {
    /** Starts capturing microphone audio for the assistant. */
    suspend operator fun invoke() {
        repository.startRecording()
    }
}
