package com.foodflow.data.repository

import com.foodflow.domain.model.VoiceActionRequest
import com.foodflow.domain.model.VoiceActionResult
import com.foodflow.domain.model.VoiceAssistantSnapshot
import com.foodflow.domain.model.VoiceConversationContext
import com.foodflow.domain.model.VoiceNavigationTarget
import com.foodflow.domain.model.VoiceSheetDetent
import com.foodflow.domain.repository.VoiceAssistantController
import com.foodflow.domain.repository.VoiceAssistantRepository
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single VoiceAssistantRepository implementation delegating to the application-scoped
 * VoiceAssistantController so all domain use cases share one session, WebSocket, and conversation.
 */
@Singleton
class VoiceAssistantRepositoryImpl @Inject constructor(
    private val controller: VoiceAssistantController
) : VoiceAssistantRepository {

    override val assistantState: StateFlow<VoiceAssistantSnapshot>
        get() = controller.observeAssistantState()

    override val conversation: StateFlow<VoiceConversationContext>
        get() = controller.observeConversation()

    override val navigationRequests: SharedFlow<VoiceNavigationTarget>
        get() = controller.observeNavigationRequests()

    /** Opens the assistant through the shared application controller. */
    override suspend fun openAssistant() = controller.openAssistant()
    /** Hides the assistant while retaining the shared controller session. */
    override suspend fun hideAssistant() = controller.hideAssistant()
    /** Closes the assistant through the shared application controller. */
    override suspend fun closeAssistant() = controller.closeAssistant()
    /** Changes the visible height of the assistant sheet. */
    override suspend fun setSheetDetent(detent: VoiceSheetDetent) = controller.setSheetDetent(detent)
    /** Connects the assistant session, optionally as a manual retry. */
    override suspend fun connect(isManualRetry: Boolean) = controller.connectSession(isManualRetry)
    /** Disconnects the session and records whether the user explicitly closed it. */
    override suspend fun disconnect(isExplicitClose: Boolean) = controller.disconnectSession(isExplicitClose)
    /** Starts voice recording through the shared assistant controller. */
    override suspend fun startRecording() = controller.startListening()
    /** Stops voice recording through the shared assistant controller. */
    override suspend fun stopRecording() = controller.stopListening()
    /** Stops the current assistant interaction and audio output. */
    override suspend fun stopAssistant() = controller.stopCurrentInteraction()
    /** Sends recorded audio to the assistant at the supplied sample rate. */
    override suspend fun sendVoiceChunk(pcmBytes: ByteArray, sampleRateHz: Int) =
        controller.sendManualVoiceInput(pcmBytes, sampleRateHz)
    /** Sends typed text to the assistant. */
    override suspend fun sendTextMessage(text: String) = controller.sendTextMessage(text)
    /** Interrupts the current assistant response. */
    override suspend fun interruptResponse(reason: String) = controller.interruptAssistant(reason)
    /** Pauses assistant activity while the app is temporarily backgrounded. */
    override suspend fun pause() = controller.pauseAssistant()
    /** Resumes assistant activity after a temporary pause. */
    override suspend fun resume() = controller.resumeAssistant()
    /** Toggles assistant audio output between muted and audible. */
    override suspend fun toggleMute() = controller.toggleMute()
    /** Runs an assistant-requested app action and returns its result. */
    override suspend fun executeAction(action: VoiceActionRequest): VoiceActionResult =
        controller.executeAction(action)
    /** Confirms the assistant action awaiting user approval. */
    override suspend fun confirmPendingAction() = controller.confirmPendingAction()
    /** Rejects the assistant action awaiting user approval. */
    override suspend fun dismissPendingAction() = controller.dismissPendingAction()
    /** Restores the saved conversation through the shared controller. */
    override suspend fun restoreConversation(): VoiceConversationContext =
        controller.restoreConversation()
    /** Shares the current screen and restaurant context with the assistant. */
    override fun updateScreenContext(screenName: String, restaurantId: String?, restaurantName: String?) =
        controller.updateScreenContext(screenName, restaurantId, restaurantName)
    /** Forwards the microphone permission result to assistant session state. */
    override fun onMicrophonePermissionResult(granted: Boolean, permanentlyDenied: Boolean) =
        controller.onMicrophonePermissionResult(granted, permanentlyDenied)
    /** Notifies the shared assistant session that the app entered the background. */
    override fun onAppBackgrounded() = controller.onAppBackgrounded()
    /** Notifies the shared assistant session that the app returned to the foreground. */
    override fun onAppForegrounded() = controller.onAppForegrounded()
    /** Releases assistant audio and session resources through the shared controller. */
    override fun releaseResources() = controller.releaseResources()
}
