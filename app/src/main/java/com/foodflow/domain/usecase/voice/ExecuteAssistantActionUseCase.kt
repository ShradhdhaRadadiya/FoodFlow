package com.foodflow.domain.usecase.voice

import com.foodflow.domain.model.VoiceActionRequest
import com.foodflow.domain.model.VoiceActionResult
import com.foodflow.domain.repository.VoiceAssistantRepository
import javax.inject.Inject

class ExecuteAssistantActionUseCase @Inject constructor(
    private val repository: VoiceAssistantRepository
) {
    /** Runs an assistant-requested app action and returns its result. */
    suspend operator fun invoke(action: VoiceActionRequest): VoiceActionResult =
        repository.executeAction(action)
}
