package com.foodflow.domain.usecase.voice

import com.foodflow.domain.repository.VoiceAssistantRepository
import javax.inject.Inject

class StopVoiceRecordingUseCase @Inject constructor(
    private val repository: VoiceAssistantRepository
) {
    /** Stops capturing microphone audio. */
    suspend operator fun invoke() {
        repository.stopRecording()
    }
}
