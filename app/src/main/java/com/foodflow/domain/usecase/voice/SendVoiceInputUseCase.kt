package com.foodflow.domain.usecase.voice

import com.foodflow.domain.repository.VoiceAssistantRepository
import javax.inject.Inject

class SendVoiceInputUseCase @Inject constructor(
    private val repository: VoiceAssistantRepository
) {
    /** Sends a recorded audio chunk to the assistant at the supplied sample rate. */
    suspend operator fun invoke(pcmBytes: ByteArray, sampleRateHz: Int = 16_000) {
        repository.sendVoiceChunk(pcmBytes = pcmBytes, sampleRateHz = sampleRateHz)
    }
}
