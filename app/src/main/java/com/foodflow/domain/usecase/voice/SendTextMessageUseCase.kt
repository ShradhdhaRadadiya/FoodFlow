package com.foodflow.domain.usecase.voice

import com.foodflow.domain.repository.VoiceAssistantRepository
import javax.inject.Inject

class SendTextMessageUseCase @Inject constructor(
    private val repository: VoiceAssistantRepository
) {
    /** Sends non-blank typed text to the assistant after trimming extra spaces. */
    suspend operator fun invoke(text: String) {
        if (text.isNotBlank()) {
            repository.sendTextMessage(text.trim())
        }
    }
}
