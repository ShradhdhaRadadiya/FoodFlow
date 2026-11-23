package com.foodflow.domain.usecase.voice

import com.foodflow.domain.model.VoiceAssistantSnapshot
import com.foodflow.domain.repository.VoiceAssistantRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class ObserveVoiceAssistantStateUseCase @Inject constructor(
    private val repository: VoiceAssistantRepository
) {
    /** Provides the assistant's current state and emits when that state changes. */
    operator fun invoke(): StateFlow<VoiceAssistantSnapshot> = repository.assistantState
}
