package com.foodflow.domain.usecase.voice

import com.foodflow.domain.model.VoiceConversationContext
import com.foodflow.domain.repository.VoiceAssistantRepository
import javax.inject.Inject

class RestoreConversationUseCase @Inject constructor(
    private val repository: VoiceAssistantRepository
) {
    /** Restores the saved conversation when the assistant is reopened. */
    suspend operator fun invoke(): VoiceConversationContext = repository.restoreConversation()
}
