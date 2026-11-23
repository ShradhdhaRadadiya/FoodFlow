package com.foodflow.data.voice

import com.foodflow.domain.model.VoiceActionRequest
import com.foodflow.domain.model.VoiceActionResult
import com.foodflow.domain.model.VoiceAssistantError
import com.foodflow.domain.model.VoiceAudioChunk
import com.foodflow.domain.model.VoiceAudioRoute
import com.foodflow.domain.model.VoiceConversationContext
import com.foodflow.domain.model.VoiceConversationMessage
import com.foodflow.domain.model.VoicePendingConfirmation
import com.foodflow.domain.model.VoiceRecommendationItem
import com.foodflow.domain.model.VoiceServerEvent
import com.foodflow.domain.model.VoiceStructuredResponse
import com.foodflow.domain.model.VoiceToolCallInvocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Replaceable bidirectional real-time WebSocket data source for Gemini Live API.
 * Keeps protocol framing, session resumption, and tool response marshaling out of ViewModels/UI.
 */
interface VoiceWebSocketDataSource {
    val activeModelName: String
    val isConnected: Boolean
    val serverEvents: SharedFlow<VoiceServerEvent>

    /** Opens the live assistant session and optionally resumes prior server context. */
    suspend fun connect(
        systemPrompt: String,
        resumptionHandle: String? = null
    ): Result<Unit>

    /** Sends one captured PCM audio chunk to the live session. */
    suspend fun sendAudioChunk(chunk: VoiceAudioChunk): Result<Unit>
    /** Sends a text turn through the live session. */
    suspend fun sendTextTurn(text: String, endOfTurn: Boolean = true): Result<Unit>
    /** Signals that the user has started an audio activity. */
    suspend fun sendActivityStart(): Result<Unit>
    /** Signals that the current audio activity has ended. */
    suspend fun sendActivityEnd(): Result<Unit>
    /** Returns local action results to the corresponding server tool calls. */
    suspend fun sendToolResponses(responses: List<Pair<VoiceToolCallInvocation, VoiceActionResult>>): Result<Unit>
    /** Closes the session and records whether the user explicitly closed it. */
    suspend fun close(isExplicitClose: Boolean)
}

/**
 * Result returned by the dedicated text-only Gemini chat data source.
 */
data class AssistantTextChatResponse(
    val replyText: String,
    val invokedTools: Boolean = false
)

/**
 * Dedicated text-only Gemini AI chat data source (no audio output, no TTS, no microphone, no Live WebSocket).
 * Used strictly when the user submits typed text or quick prompts in TEXT mode.
 */
interface VoiceTextChatDataSource {
    val activeModelName: String

    /**
     * Asynchronously pre-warms Firebase AI, tool declarations, and Firebase App Check attestation
     * when the assistant sheet opens so the first user message avoids cold-start latency.
     */
    fun warmUp() {}

    /** Sends a text message and streams partial text and tool calls to the caller. */
    suspend fun sendTextMessage(
        userText: String,
        turnId: Long,
        context: VoiceConversationContext,
        onToolCalls: suspend (List<VoiceToolCallInvocation>) -> List<Pair<VoiceToolCallInvocation, VoiceActionResult>>,
        onPartialText: (String) -> Unit = {}
    ): Result<AssistantTextChatResponse>
}

/**
 * Replaceable microphone streaming capture data source.
 * Produces bounded 16 kHz 16-bit mono PCM chunks via a backpressure-safe channel.
 */
interface VoiceAudioRecordingDataSource {
    val isRecording: StateFlow<Boolean>
    val inputAmplitude: StateFlow<Float>
    val activeAudioEffectsSummary: StateFlow<String>

    /** Reports whether Android currently grants microphone recording permission. */
    fun hasRecordPermission(): Boolean
    /** Starts microphone capture and emits PCM chunks tagged with their active turn. */
    fun startRecording(turnIdProvider: () -> Long): Result<Flow<VoiceAudioChunk>>
    /** Stops microphone capture and releases its active recording session. */
    fun stopRecording()
    /** Releases resources held by the recording source. */
    fun release()
}

/**
 * Replaceable streaming PCM audio playback data source (24 kHz 16-bit mono).
 * Supports incremental chunk playback, bounded buffering, and immediate barge-in flush.
 */
interface VoiceAudioPlaybackDataSource {
    val isPlaying: StateFlow<Boolean>
    val outputAmplitude: StateFlow<Float>

    /** Starts playback for the given assistant response turn. */
    fun startSession(turnId: Long): Result<Unit>
    /** Queues one assistant audio chunk for playback. */
    fun enqueueChunk(chunk: VoiceAudioChunk): Boolean
    /** Stops current output and drops queued chunks before the next response turn. */
    fun stopImmediatelyAndFlush(newTurnId: Long)
    /** Enables or disables assistant audio output without ending the session. */
    fun setMuted(muted: Boolean)
    /** Sets a bounded gain multiplier for assistant playback. */
    fun setPlaybackGain(gainMultiplier: Float) {}
    /** Releases resources held by the playback source. */
    fun release()
}

/**
 * Replaceable bounded conversation state store with older-turn summarization
 * and cross-screen context persistence.
 */
interface VoiceConversationDataSource {
    val conversation: StateFlow<VoiceConversationContext>

    /** Adds a completed or newly started message to conversation history. */
    fun appendMessage(message: VoiceConversationMessage)
    /** Updates the in-progress message and its streamed response metadata. */
    fun updateStreamingMessage(
        turnId: Long,
        role: com.foodflow.domain.model.VoiceMessageRole,
        fullText: String,
        isStreaming: Boolean,
        isInterrupted: Boolean = false,
        isFallback: Boolean = false,
        recommendations: List<VoiceRecommendationItem> = emptyList(),
        structuredResponse: VoiceStructuredResponse? = null
    )
    /** Checks whether a turn already contains structured recommendation cards. */
    fun hasStructuredCardsForTurn(turnId: Long): Boolean
    /** Marks the current assistant response as interrupted. */
    fun markTurnInterrupted(turnId: Long)
    /** Replaces the current conversation's recommended food items. */
    fun updateRecommendations(items: List<VoiceRecommendationItem>)
    /** Saves the app screen context supplied to later assistant turns. */
    fun updateScreenContext(screenName: String, restaurantId: String?, restaurantName: String?)
    /** Persists the server token used to resume a live conversation. */
    fun updateResumptionHandle(handle: String?)
    /** Stores or clears a user confirmation awaiting a response. */
    fun setPendingConfirmation(confirmation: VoicePendingConfirmation?)
    /** Allocates and returns the next conversation turn identifier. */
    fun advanceTurnId(): Long
    /** Returns the identifier of the current conversation turn. */
    fun currentTurnId(): Long
    /** Resolves a spoken one-based item number to its recommendation. */
    fun resolveRecommendationByOrdinal(ordinal1Based: Int): VoiceRecommendationItem?
    /** Builds a compact conversation summary for reconnecting to the live session. */
    fun buildContextSummaryForReconnect(): String
    /** Restores the persisted conversation snapshot. */
    fun restoreSnapshot(): VoiceConversationContext
    /** Starts a new session while retaining saved conversation history. */
    fun resetSessionKeepingHistory(newSessionId: String)
}

/**
 * Controlled application action execution layer that validates all requests
 * and delegates strictly to existing FoodFlow domain use cases.
 */
interface VoiceAssistantActionDataSource {
    /** Validates and executes an assistant action using the current conversation context. */
    suspend fun execute(
        action: VoiceActionRequest,
        context: VoiceConversationContext
    ): VoiceActionResult
}

enum class VoiceAudioFocusEvent {
    GAINED,
    LOST_TRANSIENT,
    LOST_TRANSIENT_CAN_DUCK,
    LOST_PERMANENT,
    BECOMING_NOISY,
    ROUTE_CHANGED
}

/**
 * Manages Android AudioManager audio focus, communication mode, and headset/Bluetooth route events.
 */
interface VoiceAudioFocusCoordinator {
    val hasFocus: StateFlow<Boolean>
    val activeRoute: StateFlow<VoiceAudioRoute>
    val focusEvents: SharedFlow<VoiceAudioFocusEvent>

    /** Requests temporary Android audio focus for assistant speech. */
    fun requestAudioFocus(): Boolean
    /** Gives up any Android audio focus held by the assistant. */
    fun abandonAudioFocus()
    /** Unregisters audio routing listeners and releases coordinator resources. */
    fun release()
}

/**
 * Local, transparent rule-based fallback using actual FoodFlow Room data when Gemini Live is
 * unavailable (quota exceeded, offline, or transient outage). Never pretends to be Gemini.
 */
interface VoiceLocalFallbackEngine {
    /** Answers a query with local FoodFlow data when the remote assistant is unavailable. */
    suspend fun handleFallbackQuery(
        userQuery: String,
        context: VoiceConversationContext
    ): Pair<VoiceActionResult, String>
}

data class VoiceErrorMappingResult(
    val error: VoiceAssistantError,
    val shouldTriggerFallback: Boolean
)
