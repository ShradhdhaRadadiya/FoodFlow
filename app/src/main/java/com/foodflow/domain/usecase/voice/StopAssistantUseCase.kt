package com.foodflow.domain.usecase.voice

import com.foodflow.domain.repository.VoiceAssistantRepository
import javax.inject.Inject

/**
 * Single-responsibility use case for the primary Stop control.
 * Immediately stops active microphone capture, halts audio streaming, cancels active AI
 * generation/tool work, flushes playback buffers, prevents stale audio from playing,
 * and returns the UI to Ready/Idle while keeping the shared session alive.
 */
class StopAssistantUseCase @Inject constructor(
    private val repository: VoiceAssistantRepository
) {
    /** Stops the active assistant response and audio processing. */
    suspend operator fun invoke() = repository.stopAssistant()
}
