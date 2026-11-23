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
 * Global reusable Voice Assistant session controller interface.
 * Screens and ViewModels interact with the single shared assistant exclusively through
 * this interface rather than accessing WebSocket, AudioRecord, or AudioTrack directly.
 */
interface VoiceAssistantController {
    /** Makes the shared assistant interface visible and prepares its session. */
    suspend fun openAssistant()
    /** Hides the assistant interface while keeping the shared session available. */
    suspend fun hideAssistant()
    /** Closes the assistant interface and ends its active session. */
    suspend fun closeAssistant()
    /** Changes how much of the assistant sheet is visible. */
    suspend fun setSheetDetent(detent: VoiceSheetDetent)
    /** Connects to the assistant service, optionally for a user-requested retry. */
    suspend fun connectSession(isManualRetry: Boolean = false)
    /** Disconnects the assistant and records whether the user explicitly closed it. */
    suspend fun disconnectSession(isExplicitClose: Boolean)
    /** Starts microphone capture for a voice request. */
    suspend fun startListening()
    /** Stops microphone capture for the current voice request. */
    suspend fun stopListening()
    /** Stops the current assistant response or audio interaction. */
    suspend fun stopCurrentInteraction()
    /** Pauses assistant activity while the app is temporarily backgrounded. */
    suspend fun pauseAssistant()
    /** Resumes assistant activity after a temporary pause. */
    suspend fun resumeAssistant()
    /** Interrupts the current assistant response with the supplied reason. */
    suspend fun interruptAssistant(reason: String = "User interrupted")
    /** Toggles assistant audio output between muted and audible. */
    suspend fun toggleMute()
    /** Sends a typed message to the assistant. */
    suspend fun sendTextMessage(text: String)
    /** Sends recorded PCM audio to the assistant at the supplied sample rate. */
    suspend fun sendManualVoiceInput(pcmBytes: ByteArray, sampleRateHz: Int = 16_000)
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
    /** Provides the current assistant state and emits when it changes. */
    fun observeAssistantState(): StateFlow<VoiceAssistantSnapshot>
    /** Provides the conversation and emits when its messages or context change. */
    fun observeConversation(): StateFlow<VoiceConversationContext>
    /** Emits navigation destinations requested by the assistant. */
    fun observeNavigationRequests(): SharedFlow<VoiceNavigationTarget>
    /** Releases audio and session resources owned by the shared assistant. */
    fun releaseResources()
}
