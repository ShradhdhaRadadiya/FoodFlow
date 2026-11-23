package com.foodflow.domain.usecase.voice

import com.foodflow.domain.model.VoiceConversationContext
import com.foodflow.domain.repository.VoiceAssistantRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class ObserveConversationUseCase @Inject constructor(
    private val repository: VoiceAssistantRepository
) {
    /** Provides the current conversation and emits whenever it changes. */
    operator fun invoke(): StateFlow<VoiceConversationContext> = repository.conversation
}
