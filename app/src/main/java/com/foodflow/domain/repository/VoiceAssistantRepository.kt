package com.foodflow.domain.repository

import com.foodflow.domain.model.VoiceActionRequest
import com.foodflow.domain.model.VoiceActionResult
import com.foodflow.domain.model.VoiceAssistantSnapshot
import com.foodflow.domain.model.VoiceConversationContext
import com.foodflow.domain.model.VoiceNavigationTarget
import com.foodflow.domain.model.VoiceSheetDetent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Domain repository abstraction for the shared Voice Assistant feature.
 * Backed by a single singleton session coordinator so navigation across screens never
 * creates duplicate WebSocket connections, recorders, playback engines, or conversations.
 */
interface VoiceAssistantRepository {
    val assistantState: StateFlow<VoiceAssistantSnapshot>
    val conversation: StateFlow<VoiceConversationContext>
    val navigationRequests: SharedFlow<VoiceNavigationTarget>

    /** Opens the assistant interface and prepares its shared session. */
    suspend fun openAssistant()
    /** Hides the assistant interface while retaining its session. */
    suspend fun hideAssistant()
    /** Closes the assistant interface and ends its session. */
    suspend fun closeAssistant()
    /** Changes how much of the assistant sheet is visible. */
    suspend fun setSheetDetent(detent: VoiceSheetDetent)
    /** Connects to the assistant service, optionally for a user-requested retry. */
    suspend fun connect(isManualRetry: Boolean = false)
    /** Disconnects the assistant and records whether the user explicitly closed it. */
    suspend fun disconnect(isExplicitClose: Boolean)
    /** Starts microphone capture for a voice request. */
    suspend fun startRecording()
    /** Stops microphone capture for the current voice request. */
    suspend fun stopRecording()
    /** Stops active assistant work and audio playback. */
    suspend fun stopAssistant()
    /** Sends a PCM audio chunk to the assistant at the supplied sample rate. */
    suspend fun sendVoiceChunk(pcmBytes: ByteArray, sampleRateHz: Int = 16_000)
    /** Sends a typed message to the assistant. */
    suspend fun sendTextMessage(text: String)
    /** Interrupts the current assistant response with the supplied reason. */
    suspend fun interruptResponse(reason: String = "User interrupted")
    /** Pauses assistant activity while the app is temporarily backgrounded. */
    suspend fun pause()
    /** Resumes assistant activity after a temporary pause. */
    suspend fun resume()
    /** Toggles assistant audio output between muted and audible. */
    suspend fun toggleMute()
    /** Runs an assistant-requested app action and returns its result. */
    suspend fun executeAction(action: VoiceActionRequest): VoiceActionResult
    /** Confirms the action currently waiting for user approval. */
    suspend fun confirmPendingAction()
    /** Rejects the action currently waiting for user approval. */
    suspend fun dismissPendingAction()
    /** Restores saved conversation history for the assistant. */
    suspend fun restoreConversation(): VoiceConversationContext
    /** Updates which app screen and restaurant the assistant should know about. */
    fun updateScreenContext(screenName: String, restaurantId: String? = null, restaurantName: String? = null)
    /** Updates assistant state after the microphone permission prompt completes. */
    fun onMicrophonePermissionResult(granted: Boolean, permanentlyDenied: Boolean = false)
    /** Notifies the assistant that the app has moved to the background. */
    fun onAppBackgrounded()
    /** Notifies the assistant that the app has returned to the foreground. */
    fun onAppForegrounded()
    /** Releases the audio and session resources used by the assistant. */
    fun releaseResources()
}
