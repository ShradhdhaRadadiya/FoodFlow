package com.foodflow.data.voice

import android.util.Log
import com.foodflow.core.common.DispatcherProvider
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.core.connectivity.ConnectivityStatus
import com.foodflow.domain.model.AssistantInteractionMode
import com.foodflow.domain.model.VoiceActionRequest
import com.foodflow.domain.model.VoiceActionResult
import com.foodflow.domain.model.VoiceAssistantError
import com.foodflow.domain.model.VoiceAssistantPhase
import com.foodflow.domain.model.VoiceAssistantSnapshot
import com.foodflow.domain.model.VoiceAudioChunk
import com.foodflow.domain.model.VoiceConnectionState
import com.foodflow.domain.model.VoiceConversationContext
import com.foodflow.domain.model.VoiceConversationMessage
import com.foodflow.domain.model.VoiceErrorCategory
import com.foodflow.domain.model.VoiceMessageRole
import com.foodflow.domain.model.VoiceNavigationTarget
import com.foodflow.domain.model.VoicePlaybackState
import com.foodflow.domain.model.VoiceRecordingState
import com.foodflow.domain.model.VoiceResponseType
import com.foodflow.domain.model.VoiceServerEvent
import com.foodflow.domain.model.VoiceSheetDetent
import com.foodflow.domain.model.VoiceStructuredResponse
import com.foodflow.domain.model.VoiceToolCallInvocation
import com.foodflow.domain.repository.VoiceAssistantController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.random.Random

private const val TAG = "VoiceSessionMgr"
private const val MAX_RECONNECT_ATTEMPTS = 3
private const val BASE_BACKOFF_MS = 1_200L
private const val MAX_BACKOFF_MS = 10_000L
private const val BARGE_IN_RMS_THRESHOLD = 0.28f
private const val BARGE_IN_CONSECUTIVE_FRAMES = 4

/**
 * Single application-scoped coordinator for the Voice Assistant session and its audio resources.
 *
 * Text flow: the ViewModel submits a prompt; this class adds it to shared history, calls the
 * text-chat source, runs any requested FoodFlow actions, and stores the final streamed response.
 * Voice flow: it opens/resumes Gemini Live, starts microphone capture, forwards PCM chunks over
 * the WebSocket, collects transcript/audio/tool events, routes audio to playback, and sends local
 * tool results back to Gemini. Connectivity or session errors may route a turn to the explicit
 * local catalog fallback. Lifecycle, audio focus, interruption, and reconnect handling all pass
 * through this coordinator so screens share one session and conversation.
 */
@Singleton
class VoiceAssistantSessionManager @Inject constructor(
    private val webSocketDataSource: VoiceWebSocketDataSource,
    private val textChatDataSource: VoiceTextChatDataSource,
    private val audioRecordingDataSource: VoiceAudioRecordingDataSource,
    private val audioPlaybackDataSource: VoiceAudioPlaybackDataSource,
    private val conversationStore: VoiceConversationDataSource,
    private val actionExecutor: VoiceAssistantActionDataSource,
    private val audioFocusCoordinator: VoiceAudioFocusCoordinator,
    private val localFallbackEngine: VoiceLocalFallbackEngine,
    private val connectivityObserver: ConnectivityObserver,
    private val errorMapper: VoiceErrorMapper,
    private val dispatchers: DispatcherProvider
) : VoiceAssistantController {

    private val sessionScope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val lifecycleMutex = Mutex()
    private val interruptionMutex = Mutex()
    private val playbackSequence = AtomicLong(0L)

    private val _state = MutableStateFlow(
        VoiceAssistantSnapshot(
            interactionMode = AssistantInteractionMode.TEXT,
            hasMicPermission = audioRecordingDataSource.hasRecordPermission(),
            activeModelName = textChatDataSource.activeModelName,
            maxReconnectAttempts = MAX_RECONNECT_ATTEMPTS
        )
    )

    private val _navigationRequests = MutableSharedFlow<VoiceNavigationTarget>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var recordingCollectJob: Job? = null
    private var reconnectJob: Job? = null
    private var activeToolJob: Job? = null
    private var activeTextJob: Job? = null
    private var backgroundCloseJob: Job? = null
    private val sessionCloseEpoch = AtomicLong(0L)

    @Volatile
    private var activeVoiceTurnId: Long = 0L

    @Volatile
    private var explicitlyClosed: Boolean = false

    @Volatile
    private var isAppInBackground: Boolean = false

    @Volatile
    private var wasOpenBeforeBackground: Boolean = false

    private val liveUserTranscriptBuffer = StringBuilder()
    private val liveAssistantTranscriptBuffer = StringBuilder()
    private var consecutiveSpeechFramesWhileSpeaking = 0
    private var pendingUserTurnId: Long = 0L

    init {
        observeWebSocketEvents()
        observeAudioStreamsTelemetry()
        observeAudioFocusEvents()
        observeConnectivityChanges()
    }

    /** Exposes assistant connection, recording, playback, and UI phase as observable state. */
    override fun observeAssistantState(): StateFlow<VoiceAssistantSnapshot> = _state.asStateFlow()

    /** Exposes the shared conversation history and context held by the conversation store. */
    override fun observeConversation(): StateFlow<VoiceConversationContext> = conversationStore.conversation

    /** Exposes one-shot navigation requests produced by assistant actions. */
    override fun observeNavigationRequests(): SharedFlow<VoiceNavigationTarget> = _navigationRequests.asSharedFlow()

    override suspend fun openAssistant() {
        explicitlyClosed = false
        // Pre-warm Firebase AI, tool schema, and App Check token asynchronously so the first
        // text message responds immediately without cold initialization delay.
        sessionScope.launch {
            runCatching { textChatDataSource.warmUp() }
        }
        val hasPermission = audioRecordingDataSource.hasRecordPermission()
        // Opening the chat sheet stays silently in TEXT mode without eagerly connecting
        // the live voice WebSocket, activating the microphone, or initializing audio playback.
        _state.update { current ->
            val nextDetent = when (current.sheetDetent) {
                VoiceSheetDetent.HIDDEN, VoiceSheetDetent.COLLAPSED -> VoiceSheetDetent.HALF_EXPANDED
                else -> current.sheetDetent
            }
            val keepActiveVoice = current.interactionMode == AssistantInteractionMode.VOICE &&
                (current.recordingState == VoiceRecordingState.LISTENING ||
                    current.playbackState == VoicePlaybackState.SPEAKING)
            current.copy(
                isOpen = true,
                isMinimized = false,
                sheetDetent = nextDetent,
                interactionMode = if (keepActiveVoice) AssistantInteractionMode.VOICE else AssistantInteractionMode.TEXT,
                hasMicPermission = hasPermission,
                activeError = null,
                phase = if (keepActiveVoice) current.phase else VoiceAssistantPhase.IDLE,
                statusMessage = if (keepActiveVoice) current.statusMessage else VoiceAssistantPhase.IDLE.userVisibleStatus
            )
        }
    }

    /**
     * Minimizes the conversational sheet while keeping the shared session & conversation alive
     * as the user navigates across screens.
     */
    override suspend fun hideAssistant() {
        _state.update {
            it.copy(
                isOpen = false,
                isMinimized = true,
                sheetDetent = VoiceSheetDetent.HIDDEN
            )
        }
    }

    /** Updates sheet visibility and minimization state without closing the live conversation. */
    override suspend fun setSheetDetent(detent: VoiceSheetDetent) {
        _state.update { current ->
            when (detent) {
                VoiceSheetDetent.HIDDEN -> current.copy(
                    isOpen = false,
                    isMinimized = true,
                    sheetDetent = VoiceSheetDetent.HIDDEN
                )
                else -> current.copy(
                    isOpen = true,
                    isMinimized = detent == VoiceSheetDetent.COLLAPSED,
                    sheetDetent = detent
                )
            }
        }
    }

    /**
     * Explicitly terminates the voice session, stops mic & playback, cancels active jobs,
     * closes WebSocket without auto-reconnecting, and releases audio resources while retaining
     * conversation history for the next open.
     */
    override suspend fun closeAssistant() {
        explicitlyClosed = true
        wasOpenBeforeBackground = false
        sessionCloseEpoch.incrementAndGet()
        reconnectJob?.cancel()
        reconnectJob = null
        activeToolJob?.cancel()
        activeToolJob = null
        activeTextJob?.cancel()
        activeTextJob = null
        backgroundCloseJob?.cancel()
        backgroundCloseJob = null
        activeVoiceTurnId = 0L
        pendingUserTurnId = 0L

        lifecycleMutex.withLock {
            _state.update {
                it.copy(
                    phase = VoiceAssistantPhase.CLOSING,
                    connectionState = VoiceConnectionState.CLOSING,
                    statusMessage = VoiceAssistantPhase.CLOSING.userVisibleStatus
                )
            }

            stopRecordingInternal()
            audioRecordingDataSource.release()
            val closedTurn = conversationStore.advanceTurnId()
            audioPlaybackDataSource.stopImmediatelyAndFlush(closedTurn)
            audioPlaybackDataSource.release()
            audioFocusCoordinator.release()
            webSocketDataSource.close(isExplicitClose = true)
            clearLiveTranscriptBuffers()

            _state.update {
                it.copy(
                    isOpen = false,
                    isMinimized = false,
                    sheetDetent = VoiceSheetDetent.HIDDEN,
                    interactionMode = AssistantInteractionMode.TEXT,
                    phase = VoiceAssistantPhase.CLOSED,
                    connectionState = VoiceConnectionState.CLOSED,
                    recordingState = VoiceRecordingState.IDLE,
                    playbackState = VoicePlaybackState.IDLE,
                    inputAmplitude = 0f,
                    outputAmplitude = 0f,
                    liveUserTranscript = "",
                    liveAssistantTranscript = "",
                    activeError = null,
                    reconnectAttempt = 0,
                    hasAudioFocus = false,
                    statusMessage = VoiceAssistantPhase.CLOSED.userVisibleStatus
                )
            }
        }
    }

    /** Connects Gemini Live, restores saved context, then starts event collection and updates connection state. */
    override suspend fun connectSession(isManualRetry: Boolean) {
        // Ensure any queued background/release WebSocket close finishes before reconnecting
        backgroundCloseJob?.join()
        lifecycleMutex.withLock {
            if (isAppInBackground) return
            if (webSocketDataSource.isConnected) {
                val turnId = conversationStore.currentTurnId().coerceAtLeast(1L)
                activeVoiceTurnId = turnId
                _state.update {
                    it.copy(
                        interactionMode = AssistantInteractionMode.VOICE,
                        activeModelName = webSocketDataSource.activeModelName,
                        connectionState = VoiceConnectionState.CONNECTED,
                        phase = if (it.recordingState == VoiceRecordingState.LISTENING) {
                            VoiceAssistantPhase.LISTENING
                        } else {
                            VoiceAssistantPhase.CONNECTED
                        },
                        isFallbackMode = false,
                        activeError = null,
                        statusMessage = VoiceAssistantPhase.CONNECTED.userVisibleStatus
                    )
                }
                return
            }

            explicitlyClosed = false
            if (isManualRetry) {
                reconnectJob?.cancel()
                reconnectJob = null
                _state.update { it.copy(reconnectAttempt = 0, activeError = null) }
            }

            _state.update {
                it.copy(
                    interactionMode = AssistantInteractionMode.VOICE,
                    activeModelName = webSocketDataSource.activeModelName,
                    connectionState = if (it.reconnectAttempt > 0) VoiceConnectionState.RECONNECTING else VoiceConnectionState.CONNECTING,
                    phase = if (it.reconnectAttempt > 0) VoiceAssistantPhase.RECONNECTING else VoiceAssistantPhase.CONNECTING,
                    statusMessage = if (it.reconnectAttempt > 0) {
                        "Connection lost. Trying to reconnect..."
                    } else {
                        "Connecting to your food assistant..."
                    }
                )
            }

            val contextSummary = conversationStore.buildContextSummaryForReconnect()
            val systemPrompt = GeminiFoodFlowToolSchema.buildSystemInstruction(contextSummary)
            val resumptionHandle = conversationStore.conversation.value.resumptionHandle

            val result = webSocketDataSource.connect(
                systemPrompt = systemPrompt,
                resumptionHandle = resumptionHandle
            )

            // Guard against close or app backgrounding that happened while the handshake was suspended
            if (isAppInBackground || explicitlyClosed) {
                runCatching { webSocketDataSource.close(isExplicitClose = true) }
                return@withLock
            }

            if (result.isSuccess) {
                val turnId = conversationStore.currentTurnId().coerceAtLeast(1L)
                activeVoiceTurnId = turnId
                _state.update {
                    it.copy(
                        interactionMode = AssistantInteractionMode.VOICE,
                        activeModelName = webSocketDataSource.activeModelName,
                        connectionState = VoiceConnectionState.CONNECTED,
                        phase = VoiceAssistantPhase.CONNECTED,
                        isFallbackMode = false,
                        activeError = null,
                        reconnectAttempt = 0,
                        statusMessage = VoiceAssistantPhase.CONNECTED.userVisibleStatus
                    )
                }
            } else {
                val err = result.exceptionOrNull()
                if (err is CancellationException && err !is kotlinx.coroutines.TimeoutCancellationException) {
                    throw err
                }
                val existingError = _state.value.activeError ?: err?.let {
                    errorMapper.mapThrowable(it, connectivityObserver.isCurrentlyOnline(), TAG).error
                } ?: VoiceAssistantError(
                    category = VoiceErrorCategory.SERVER_ERROR,
                    userMessage = "The AI assistant is temporarily unavailable."
                )
                handleConnectionFailure(existingError)
            }
        }
    }

    /** Closes the Live session; explicit closes suppress reconnect while transient disconnects may recover. */
    override suspend fun disconnectSession(isExplicitClose: Boolean) {
        if (isExplicitClose) {
            closeAssistant()
        } else {
            lifecycleMutex.withLock {
                reconnectJob?.cancel()
                reconnectJob = null
                activeTextJob?.cancel()
                activeTextJob = null
                activeVoiceTurnId = 0L
                stopRecordingInternal()
                audioPlaybackDataSource.stopImmediatelyAndFlush(conversationStore.advanceTurnId())
                audioFocusCoordinator.abandonAudioFocus()
                webSocketDataSource.close(isExplicitClose = false)
                _state.update {
                    it.copy(
                        connectionState = VoiceConnectionState.DISCONNECTED,
                        phase = VoiceAssistantPhase.PAUSED,
                        recordingState = VoiceRecordingState.PAUSED,
                        playbackState = VoicePlaybackState.PAUSED,
                        statusMessage = VoiceAssistantPhase.PAUSED.userVisibleStatus
                    )
                }
            }
        }
    }

    /** Requests audio focus, connects voice mode, captures PCM, and streams chunks to Gemini Live. */
    override suspend fun startListening() {
        if (isAppInBackground) return
        if (!audioRecordingDataSource.hasRecordPermission()) {
            _state.update {
                it.copy(
                    hasMicPermission = false,
                    recordingState = VoiceRecordingState.PERMISSION_REQUIRED,
                    phase = VoiceAssistantPhase.REQUESTING_PERMISSION,
                    activeError = VoiceAssistantError(
                        category = if (it.isPermissionPermanentlyDenied) {
                            VoiceErrorCategory.PERMISSION_PERMANENTLY_DENIED
                        } else {
                            VoiceErrorCategory.PERMISSION_DENIED
                        },
                        userMessage = "Microphone permission is required to speak.",
                        recoveryHint = "Allow microphone access or type your request below.",
                        canRetry = false,
                        fallbackAvailable = true
                    ),
                    statusMessage = "Microphone permission is required to speak."
                )
            }
            return
        }

        // Cancel any in-flight text chat request before switching cleanly into VOICE mode
        activeTextJob?.cancel()
        activeTextJob = null

        // If AI is currently speaking, starting user speech immediately performs barge-in interruption!
        if (_state.value.playbackState == VoicePlaybackState.SPEAKING ||
            _state.value.phase == VoiceAssistantPhase.SPEAKING ||
            _state.value.phase == VoiceAssistantPhase.RESPONDING
        ) {
            interruptAssistant("User started speaking")
        }

        _state.update {
            it.copy(
                interactionMode = AssistantInteractionMode.VOICE,
                activeModelName = webSocketDataSource.activeModelName
            )
        }

        if (!webSocketDataSource.isConnected) {
            connectSession(isManualRetry = false)
        }
        if (isAppInBackground || explicitlyClosed || !webSocketDataSource.isConnected) {
            // Stay safely closed / in fallback state instead of starting mic after close or background
            return
        }

        val focusGranted = audioFocusCoordinator.requestAudioFocus()
        if (isAppInBackground || explicitlyClosed) {
            audioFocusCoordinator.release()
            return
        }
        if (!focusGranted) {
            _state.update {
                it.copy(
                    hasAudioFocus = false,
                    activeError = VoiceAssistantError(
                        category = VoiceErrorCategory.AUDIO_FOCUS_LOST,
                        userMessage = "Another app is currently using the device audio.",
                        recoveryHint = "Pause other audio and tap the microphone again.",
                        canRetry = true,
                        fallbackAvailable = true
                    ),
                    phase = VoiceAssistantPhase.PAUSED,
                    statusMessage = "Audio focus unavailable"
                )
            }
            return
        }

        val turnId = conversationStore.advanceTurnId()
        activeVoiceTurnId = turnId
        pendingUserTurnId = turnId
        audioPlaybackDataSource.startSession(turnId)
        clearLiveTranscriptBuffers()
        consecutiveSpeechFramesWhileSpeaking = 0

        val recordResult = audioRecordingDataSource.startRecording(turnIdProvider = { conversationStore.currentTurnId() })
        if (recordResult.isFailure) {
            val mapped = errorMapper.mapThrowable(
                recordResult.exceptionOrNull() ?: IllegalStateException("Microphone unavailable"),
                connectivityObserver.isCurrentlyOnline(),
                TAG
            )
            activeVoiceTurnId = 0L
            audioFocusCoordinator.abandonAudioFocus()
            _state.update {
                it.copy(
                    recordingState = VoiceRecordingState.ERROR,
                    phase = VoiceAssistantPhase.ERROR,
                    activeError = mapped.error,
                    statusMessage = mapped.error.userMessage
                )
            }
            return
        }

        val chunkFlow = recordResult.getOrThrow()
        _state.update {
            it.copy(
                interactionMode = AssistantInteractionMode.VOICE,
                activeModelName = webSocketDataSource.activeModelName,
                hasMicPermission = true,
                hasAudioFocus = true,
                recordingState = VoiceRecordingState.LISTENING,
                phase = VoiceAssistantPhase.LISTENING,
                activeTurnId = turnId,
                activeError = null,
                liveUserTranscript = "",
                statusMessage = VoiceAssistantPhase.LISTENING.userVisibleStatus
            )
        }

        recordingCollectJob?.cancel()
        recordingCollectJob = sessionScope.launch {
            try {
                webSocketDataSource.sendActivityStart()
                chunkFlow.collect { chunk ->
                    if (!isActive || isAppInBackground) return@collect
                    if (!audioRecordingDataSource.hasRecordPermission()) {
                        handlePermissionRevokedMidSession()
                        return@collect
                    }

                    // Client-side barge-in detection while AI audio is playing
                    if (_state.value.playbackState == VoicePlaybackState.SPEAKING) {
                        if (chunk.rmsAmplitude >= BARGE_IN_RMS_THRESHOLD) {
                            consecutiveSpeechFramesWhileSpeaking++
                            if (consecutiveSpeechFramesWhileSpeaking >= BARGE_IN_CONSECUTIVE_FRAMES) {
                                consecutiveSpeechFramesWhileSpeaking = 0
                                interruptAssistant("Voice barge-in detected")
                            }
                        } else {
                            consecutiveSpeechFramesWhileSpeaking = 0
                        }
                    }

                    val sendResult = webSocketDataSource.sendAudioChunk(chunk)
                    if (sendResult.isFailure && !explicitlyClosed) {
                        runCatching { Log.w(TAG, "Chunk send failed while streaming; stopping mic") }
                        stopRecordingInternal()
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (se: SecurityException) {
                handlePermissionRevokedMidSession()
            } catch (e: Exception) {
                val mapped = errorMapper.mapThrowable(e, connectivityObserver.isCurrentlyOnline(), TAG)
                _state.update {
                    it.copy(
                        recordingState = VoiceRecordingState.ERROR,
                        phase = VoiceAssistantPhase.ERROR,
                        activeError = mapped.error,
                        statusMessage = mapped.error.userMessage
                    )
                }
            }
        }
    }

    /** Ends microphone capture and signals the completed input turn to the Live session. */
    override suspend fun stopListening() {
        val wasListening = _state.value.recordingState == VoiceRecordingState.LISTENING
        stopRecordingInternal()
        if (wasListening && webSocketDataSource.isConnected) {
            webSocketDataSource.sendActivityEnd()
            _state.update {
                it.copy(
                    recordingState = VoiceRecordingState.IDLE,
                    phase = VoiceAssistantPhase.PROCESSING,
                    statusMessage = VoiceAssistantPhase.PROCESSING.userVisibleStatus
                )
            }
        } else {
            _state.update {
                it.copy(
                    recordingState = VoiceRecordingState.IDLE,
                    phase = VoiceAssistantPhase.IDLE,
                    statusMessage = VoiceAssistantPhase.IDLE.userVisibleStatus
                )
            }
        }
    }

    /**
     * Immediately stops the active voice interaction:
     * - Stops microphone recording and halts audio chunk streaming
     * - Cancels active tool / response generation jobs
     * - Advances turnId & flushes AudioTrack so queued/stale audio never plays
     * - Abandons audio focus and clears transient live transcript buffers
     * - Transitions cleanly back to IDLE ("Ready when you are")
     * - Preserves the shared WebSocket connection, bottom-sheet detent, and conversation history
     */
    override suspend fun stopCurrentInteraction() {
        interruptionMutex.withLock {
            val wasListening = _state.value.recordingState == VoiceRecordingState.LISTENING
            val wasSpeakingOrResponding = _state.value.playbackState == VoicePlaybackState.SPEAKING ||
                _state.value.phase == VoiceAssistantPhase.SPEAKING ||
                _state.value.phase == VoiceAssistantPhase.RESPONDING ||
                _state.value.phase == VoiceAssistantPhase.PROCESSING ||
                _state.value.phase == VoiceAssistantPhase.THINKING

            stopRecordingInternal()
            activeTextJob?.cancel()
            activeTextJob = null
            activeToolJob?.cancel()
            activeToolJob = null
            activeVoiceTurnId = 0L

            if (wasListening && webSocketDataSource.isConnected) {
                runCatching { webSocketDataSource.sendActivityEnd() }
            }

            if (wasSpeakingOrResponding) {
                conversationStore.markTurnInterrupted(conversationStore.currentTurnId())
            }

            val nextTurnId = conversationStore.advanceTurnId()
            audioPlaybackDataSource.stopImmediatelyAndFlush(nextTurnId)
            audioFocusCoordinator.abandonAudioFocus()
            clearLiveTranscriptBuffers()

            _state.update { current ->
                current.copy(
                    phase = VoiceAssistantPhase.IDLE,
                    recordingState = VoiceRecordingState.IDLE,
                    playbackState = VoicePlaybackState.IDLE,
                    activeTurnId = nextTurnId,
                    inputAmplitude = 0f,
                    outputAmplitude = 0f,
                    hasAudioFocus = false,
                    liveUserTranscript = "",
                    liveAssistantTranscript = "",
                    statusMessage = VoiceAssistantPhase.IDLE.userVisibleStatus
                )
            }
        }
    }

    /**
     * Pauses microphone & playback immediately, cancels active response/tool jobs,
     * releases audio focus, and preserves conversation history + session context without
     * unexpectedly restarting recording.
     */
    override suspend fun pauseAssistant() {
        lifecycleMutex.withLock {
            stopRecordingInternal()
            activeTextJob?.cancel()
            activeTextJob = null
            activeToolJob?.cancel()
            activeToolJob = null
            activeVoiceTurnId = 0L
            val pausedTurn = conversationStore.advanceTurnId()
            audioPlaybackDataSource.stopImmediatelyAndFlush(pausedTurn)
            audioFocusCoordinator.abandonAudioFocus()

            _state.update {
                it.copy(
                    phase = VoiceAssistantPhase.PAUSED,
                    recordingState = VoiceRecordingState.PAUSED,
                    playbackState = VoicePlaybackState.PAUSED,
                    inputAmplitude = 0f,
                    outputAmplitude = 0f,
                    hasAudioFocus = false,
                    statusMessage = VoiceAssistantPhase.PAUSED.userVisibleStatus
                )
            }
        }
    }

    /**
     * Resumes session readiness and reconnects if the session expired, preserving conversation
     * context. Does NOT automatically start microphone recording until user explicitly taps Mic.
     */
    override suspend fun resumeAssistant() {
        if (isAppInBackground) return
        explicitlyClosed = false
        if (_state.value.interactionMode == AssistantInteractionMode.VOICE && !webSocketDataSource.isConnected) {
            connectSession(isManualRetry = true)
        } else {
            _state.update {
                it.copy(
                    phase = if (it.interactionMode == AssistantInteractionMode.VOICE && webSocketDataSource.isConnected) {
                        VoiceAssistantPhase.CONNECTED
                    } else {
                        VoiceAssistantPhase.IDLE
                    },
                    connectionState = if (webSocketDataSource.isConnected) {
                        VoiceConnectionState.CONNECTED
                    } else {
                        it.connectionState
                    },
                    recordingState = VoiceRecordingState.IDLE,
                    playbackState = VoicePlaybackState.IDLE,
                    activeError = null,
                    statusMessage = VoiceAssistantPhase.IDLE.userVisibleStatus
                )
            }
        }
    }

    /**
     * Centralized barge-in interruption coordinator.
     * Immediately stops & flushes AudioTrack, advances activeTurnId to discard stale chunks,
     * cancels in-flight tool jobs, marks partial AI turn interrupted, preserves conversation
     * history, and only updates state AFTER the playback pipeline is actually flushed.
     */
    override suspend fun interruptAssistant(reason: String) {
        interruptionMutex.withLock {
            val previousTurnId = conversationStore.currentTurnId()
            activeTextJob?.cancel()
            activeTextJob = null
            activeToolJob?.cancel()
            activeToolJob = null

            val newTurnId = conversationStore.advanceTurnId()
            activeVoiceTurnId = if (_state.value.interactionMode == AssistantInteractionMode.VOICE) {
                newTurnId
            } else {
                0L
            }
            audioPlaybackDataSource.stopImmediatelyAndFlush(newTurnId)

            if (liveAssistantTranscriptBuffer.isNotEmpty()) {
                conversationStore.updateStreamingMessage(
                    turnId = previousTurnId,
                    role = VoiceMessageRole.ASSISTANT,
                    fullText = liveAssistantTranscriptBuffer.toString(),
                    isStreaming = false,
                    isInterrupted = true
                )
                liveAssistantTranscriptBuffer.clear()
            } else {
                conversationStore.markTurnInterrupted(previousTurnId)
            }

            if (_state.value.interactionMode == AssistantInteractionMode.VOICE && webSocketDataSource.isConnected) {
                // Signal new user activity to Gemini Live so server cancels ongoing generation
                runCatching { webSocketDataSource.sendActivityStart() }
            }

            _state.update { current ->
                current.copy(
                    activeTurnId = newTurnId,
                    playbackState = VoicePlaybackState.INTERRUPTED,
                    outputAmplitude = 0f,
                    liveAssistantTranscript = "",
                    phase = if (current.recordingState == VoiceRecordingState.LISTENING) {
                        VoiceAssistantPhase.LISTENING
                    } else {
                        VoiceAssistantPhase.INTERRUPTED
                    },
                    statusMessage = if (current.recordingState == VoiceRecordingState.LISTENING) {
                        VoiceAssistantPhase.LISTENING.userVisibleStatus
                    } else {
                        VoiceAssistantPhase.INTERRUPTED.userVisibleStatus
                    }
                )
            }
        }
    }

    /** Toggles playback mute and applies the new state to the audio output source. */
    override suspend fun toggleMute() {
        val nextMuted = !_state.value.isMuted
        audioPlaybackDataSource.setMuted(nextMuted)
        _state.update { it.copy(isMuted = nextMuted) }
    }

    /**
     * Sends a typed text message strictly in TEXT interaction mode.
     * - Produces a text / structured-card response ONLY (zero voice playback, zero TTS, zero mic, zero Live WebSocket).
     * - If a voice session or audio playback was active, immediately stops recording, flushes playback,
     *   abandons audio focus, and drops any delayed voice audio chunks.
     */
    override suspend fun sendTextMessage(text: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return

        activeTextJob?.cancel()
        activeTextJob = null
        activeToolJob?.cancel()
        activeToolJob = null

        val snapshotBefore = _state.value
        val wasListening = snapshotBefore.recordingState == VoiceRecordingState.LISTENING
        val wasVoiceActive = snapshotBefore.interactionMode == AssistantInteractionMode.VOICE ||
            wasListening ||
            snapshotBefore.playbackState == VoicePlaybackState.SPEAKING ||
            snapshotBefore.phase == VoiceAssistantPhase.SPEAKING ||
            snapshotBefore.phase == VoiceAssistantPhase.RESPONDING ||
            snapshotBefore.hasAudioFocus

        if (wasVoiceActive) {
            val previousTurnId = conversationStore.currentTurnId()
            stopRecordingInternal()
            if (wasListening && webSocketDataSource.isConnected) {
                runCatching { webSocketDataSource.sendActivityEnd() }
            }
            if (snapshotBefore.playbackState == VoicePlaybackState.SPEAKING ||
                snapshotBefore.phase == VoiceAssistantPhase.SPEAKING ||
                snapshotBefore.phase == VoiceAssistantPhase.RESPONDING
            ) {
                if (liveAssistantTranscriptBuffer.isNotEmpty()) {
                    conversationStore.updateStreamingMessage(
                        turnId = previousTurnId,
                        role = VoiceMessageRole.ASSISTANT,
                        fullText = liveAssistantTranscriptBuffer.toString(),
                        isStreaming = false,
                        isInterrupted = true
                    )
                } else {
                    conversationStore.markTurnInterrupted(previousTurnId)
                }
            }
        }

        val turnId = conversationStore.advanceTurnId()
        activeVoiceTurnId = 0L
        pendingUserTurnId = turnId
        clearLiveTranscriptBuffers()

        if (wasVoiceActive) {
            // Immediately stop & flush any queued audio so delayed chunks from prior voice turn never play
            audioPlaybackDataSource.stopImmediatelyAndFlush(turnId)
            audioFocusCoordinator.abandonAudioFocus()
        }

        conversationStore.appendMessage(
            VoiceConversationMessage(
                id = "user_${turnId}_${UUID.randomUUID().toString().take(6)}",
                turnId = turnId,
                role = VoiceMessageRole.USER,
                text = cleaned,
                timestampMs = System.currentTimeMillis()
            )
        )

        _state.update {
            it.copy(
                interactionMode = AssistantInteractionMode.TEXT,
                activeModelName = textChatDataSource.activeModelName,
                activeTurnId = turnId,
                phase = VoiceAssistantPhase.PROCESSING,
                recordingState = VoiceRecordingState.IDLE,
                playbackState = VoicePlaybackState.IDLE,
                inputAmplitude = 0f,
                outputAmplitude = 0f,
                hasAudioFocus = false,
                liveUserTranscript = "",
                liveAssistantTranscript = "",
                activeError = null,
                statusMessage = VoiceAssistantPhase.PROCESSING.userVisibleStatus
            )
        }

        if (connectivityObserver.isCurrentlyOnline() && !_state.value.isFallbackMode) {
            val job = sessionScope.launch {
                executeTextChatTurn(cleaned, turnId)
            }
            activeTextJob = job
            job.join()
        } else {
            executeLocalFallbackTurn(cleaned, turnId)
        }
    }

    /** Wraps supplied PCM as a voice chunk and sends it through the existing Live session. */
    override suspend fun sendManualVoiceInput(pcmBytes: ByteArray, sampleRateHz: Int) {
        if (pcmBytes.isEmpty()) return
        val chunk = VoiceAudioChunk(
            sequenceNumber = playbackSequence.incrementAndGet(),
            timestampMs = System.currentTimeMillis(),
            turnId = conversationStore.currentTurnId(),
            pcmBytes = pcmBytes,
            sampleRateHz = sampleRateHz
        )
        webSocketDataSource.sendAudioChunk(chunk)
    }

    /** Routes a typed assistant action through the controlled FoodFlow action executor. */
    override suspend fun executeAction(action: VoiceActionRequest): VoiceActionResult {
        val result = actionExecutor.execute(action, conversationStore.conversation.value)
        if (result.recommendations.isNotEmpty()) {
            conversationStore.updateRecommendations(result.recommendations)
        }
        if (result.selectedRestaurantId != null) {
            conversationStore.updateScreenContext(
                screenName = conversationStore.conversation.value.currentScreenName,
                restaurantId = result.selectedRestaurantId,
                restaurantName = result.selectedRestaurantName
            )
        }
        if (result.requiresConfirmation != null) {
            conversationStore.setPendingConfirmation(result.requiresConfirmation)
        }
        result.navigationTarget?.let { target ->
            _navigationRequests.tryEmit(target)
        }
        return result
    }

    /** Executes the action awaiting confirmation, then clears its pending confirmation state. */
    override suspend fun confirmPendingAction() {
        val pending = conversationStore.conversation.value.pendingConfirmation ?: return
        conversationStore.setPendingConfirmation(null)
        val result = executeAction(pending.action)
        val turnId = conversationStore.advanceTurnId()
        conversationStore.appendMessage(
            VoiceConversationMessage(
                id = "confirm_${turnId}_${UUID.randomUUID().toString().take(6)}",
                turnId = turnId,
                role = VoiceMessageRole.TOOL_ACTION,
                text = result.summaryForVoice,
                timestampMs = System.currentTimeMillis(),
                recommendations = result.recommendations
            )
        )
    }

    /** Clears a pending action without executing it. */
    override suspend fun dismissPendingAction() {
        conversationStore.setPendingConfirmation(null)
    }

    /** Restores the current conversation snapshot from its shared store. */
    override suspend fun restoreConversation(): VoiceConversationContext =
        conversationStore.restoreSnapshot()

    /** Updates context shared with Gemini so replies reflect the screen and restaurant being viewed. */
    override fun updateScreenContext(
        screenName: String,
        restaurantId: String?,
        restaurantName: String?
    ) {
        conversationStore.updateScreenContext(screenName, restaurantId, restaurantName)
    }

    /** Updates permission state and chooses a user-facing recovery path after the permission prompt. */
    override fun onMicrophonePermissionResult(granted: Boolean, permanentlyDenied: Boolean) {
        _state.update { current ->
            if (granted) {
                current.copy(
                    hasMicPermission = true,
                    isPermissionPermanentlyDenied = false,
                    recordingState = VoiceRecordingState.IDLE,
                    activeError = if (current.activeError?.category == VoiceErrorCategory.PERMISSION_DENIED ||
                        current.activeError?.category == VoiceErrorCategory.PERMISSION_PERMANENTLY_DENIED ||
                        current.activeError?.category == VoiceErrorCategory.PERMISSION_REVOKED
                    ) null else current.activeError,
                    phase = if (current.connectionState == VoiceConnectionState.CONNECTED) {
                        VoiceAssistantPhase.CONNECTED
                    } else {
                        current.phase
                    },
                    statusMessage = if (current.connectionState == VoiceConnectionState.CONNECTED) {
                        VoiceAssistantPhase.CONNECTED.userVisibleStatus
                    } else {
                        current.statusMessage
                    }
                )
            } else {
                val category = if (permanentlyDenied) {
                    VoiceErrorCategory.PERMISSION_PERMANENTLY_DENIED
                } else {
                    VoiceErrorCategory.PERMISSION_DENIED
                }
                current.copy(
                    hasMicPermission = false,
                    isPermissionPermanentlyDenied = permanentlyDenied,
                    recordingState = VoiceRecordingState.PERMISSION_REQUIRED,
                    phase = VoiceAssistantPhase.ERROR,
                    activeError = VoiceAssistantError(
                        category = category,
                        userMessage = "Microphone permission is required to speak.",
                        recoveryHint = if (permanentlyDenied) {
                            "Enable Microphone in Android App Settings, or use text input below."
                        } else {
                            "Tap Allow on the microphone prompt, or type your request below."
                        },
                        canRetry = false,
                        fallbackAvailable = true
                    ),
                    statusMessage = "Microphone permission is required to speak."
                )
            }
        }
    }

    /**
     * Application entered background: closes the active voice session completely (WebSocket,
     * microphone, AudioTrack, audio focus, route receivers, and in-flight jobs) without
     * auto-reconnecting, while preserving conversation history for when the user returns.
     */
    override fun onAppBackgrounded() {
        isAppInBackground = true
        wasOpenBeforeBackground = _state.value.isOpen && !explicitlyClosed
        val closeEpoch = sessionCloseEpoch.incrementAndGet()
        reconnectJob?.cancel()
        reconnectJob = null
        activeTextJob?.cancel()
        activeTextJob = null
        activeToolJob?.cancel()
        activeToolJob = null
        activeVoiceTurnId = 0L
        pendingUserTurnId = 0L

        stopRecordingInternal()
        audioRecordingDataSource.release()
        val bgTurn = conversationStore.advanceTurnId()
        audioPlaybackDataSource.stopImmediatelyAndFlush(bgTurn)
        audioPlaybackDataSource.release()
        audioFocusCoordinator.release()
        clearLiveTranscriptBuffers()

        _state.update { current ->
            val wasActive = current.isOpen ||
                current.isMinimized ||
                current.isSessionActive ||
                current.connectionState != VoiceConnectionState.CLOSED
            current.copy(
                interactionMode = AssistantInteractionMode.TEXT,
                connectionState = VoiceConnectionState.CLOSED,
                phase = if (wasActive) VoiceAssistantPhase.CLOSED else current.phase,
                recordingState = VoiceRecordingState.IDLE,
                playbackState = VoicePlaybackState.IDLE,
                inputAmplitude = 0f,
                outputAmplitude = 0f,
                hasAudioFocus = false,
                liveUserTranscript = "",
                liveAssistantTranscript = "",
                reconnectAttempt = 0,
                statusMessage = if (wasActive) {
                    VoiceAssistantPhase.CLOSED.userVisibleStatus
                } else {
                    current.statusMessage
                }
            )
        }

        backgroundCloseJob?.cancel()
        backgroundCloseJob = sessionScope.launch {
            lifecycleMutex.withLock {
                if (sessionCloseEpoch.get() == closeEpoch || isAppInBackground || explicitlyClosed) {
                    runCatching { webSocketDataSource.close(isExplicitClose = true) }
                }
            }
        }
    }

    /**
     * Application returned to foreground: restores sheet visibility, detent & conversation history
     * in silent TEXT-ready state. NEVER auto-reconnects WebSocket or auto-starts microphone
     * recording until the user explicitly taps Speak.
     */
    override fun onAppForegrounded() {
        isAppInBackground = false
        val hasPerm = audioRecordingDataSource.hasRecordPermission()
        _state.update { current ->
            val restoredOpen = if (wasOpenBeforeBackground && !explicitlyClosed) true else current.isOpen
            val restoredDetent = when {
                explicitlyClosed -> VoiceSheetDetent.HIDDEN
                restoredOpen && current.sheetDetent == VoiceSheetDetent.HIDDEN -> VoiceSheetDetent.HALF_EXPANDED
                else -> current.sheetDetent
            }
            val connected = webSocketDataSource.isConnected
            current.copy(
                isOpen = restoredOpen,
                sheetDetent = restoredDetent,
                interactionMode = AssistantInteractionMode.TEXT,
                hasMicPermission = hasPerm,
                connectionState = if (connected) VoiceConnectionState.CONNECTED else VoiceConnectionState.CLOSED,
                phase = when {
                    explicitlyClosed -> VoiceAssistantPhase.CLOSED
                    restoredOpen -> VoiceAssistantPhase.IDLE
                    else -> current.phase
                },
                recordingState = VoiceRecordingState.IDLE,
                playbackState = VoicePlaybackState.IDLE,
                statusMessage = when {
                    explicitlyClosed -> VoiceAssistantPhase.CLOSED.userVisibleStatus
                    restoredOpen -> VoiceAssistantPhase.IDLE.userVisibleStatus
                    else -> current.statusMessage
                }
            )
        }
        wasOpenBeforeBackground = false
    }

    /**
     * Synchronous, idempotent full resource teardown invoked from ViewModel.onCleared() and
     * teardown hooks. Releases microphone, AudioTrack, LoudnessEnhancer, AudioFocus,
     * BroadcastReceiver/AudioDeviceCallback, cancels all active session jobs, closes WebSocket,
     * and resets UI state to CLOSED/HIDDEN — WITHOUT cancelling the singleton sessionScope so
     * recreating MainActivity in the same process stays fully operational.
     */
    override fun releaseResources() {
        explicitlyClosed = true
        wasOpenBeforeBackground = false
        val closeEpoch = sessionCloseEpoch.incrementAndGet()
        reconnectJob?.cancel()
        reconnectJob = null
        activeTextJob?.cancel()
        activeTextJob = null
        activeToolJob?.cancel()
        activeToolJob = null
        activeVoiceTurnId = 0L
        pendingUserTurnId = 0L

        stopRecordingInternal()
        audioRecordingDataSource.release()
        val closedTurn = conversationStore.advanceTurnId()
        audioPlaybackDataSource.stopImmediatelyAndFlush(closedTurn)
        audioPlaybackDataSource.release()
        audioFocusCoordinator.release()
        clearLiveTranscriptBuffers()

        _state.update {
            it.copy(
                isOpen = false,
                isMinimized = false,
                sheetDetent = VoiceSheetDetent.HIDDEN,
                interactionMode = AssistantInteractionMode.TEXT,
                phase = VoiceAssistantPhase.CLOSED,
                connectionState = VoiceConnectionState.CLOSED,
                recordingState = VoiceRecordingState.IDLE,
                playbackState = VoicePlaybackState.IDLE,
                inputAmplitude = 0f,
                outputAmplitude = 0f,
                liveUserTranscript = "",
                liveAssistantTranscript = "",
                activeError = null,
                reconnectAttempt = 0,
                hasAudioFocus = false,
                statusMessage = VoiceAssistantPhase.CLOSED.userVisibleStatus
            )
        }

        backgroundCloseJob?.cancel()
        backgroundCloseJob = sessionScope.launch {
            lifecycleMutex.withLock {
                if (sessionCloseEpoch.get() == closeEpoch || explicitlyClosed) {
                    runCatching { webSocketDataSource.close(isExplicitClose = true) }
                }
            }
        }
    }

    // --- Internal Reactive Observers ---

    /** Collects Live server events and forwards transcripts, audio, tools, and errors into session state. */
    private fun observeWebSocketEvents() {
        sessionScope.launch {
            webSocketDataSource.serverEvents.collect { event ->
                when (event) {
                    VoiceServerEvent.Connected, VoiceServerEvent.SetupComplete -> {
                        if (isAppInBackground || explicitlyClosed) {
                            return@collect
                        }
                        if (_state.value.interactionMode != AssistantInteractionMode.VOICE) {
                            _state.update { it.copy(connectionState = VoiceConnectionState.CONNECTED) }
                            return@collect
                        }
                        _state.update {
                            it.copy(
                                connectionState = VoiceConnectionState.CONNECTED,
                                phase = if (it.recordingState == VoiceRecordingState.LISTENING) {
                                    VoiceAssistantPhase.LISTENING
                                } else {
                                    VoiceAssistantPhase.CONNECTED
                                },
                                isFallbackMode = false,
                                activeError = null,
                                reconnectAttempt = 0,
                                statusMessage = VoiceAssistantPhase.CONNECTED.userVisibleStatus
                            )
                        }
                    }

                    is VoiceServerEvent.InputTranscript -> {
                        if (isAppInBackground || _state.value.interactionMode != AssistantInteractionMode.VOICE) {
                            return@collect
                        }
                        if (liveUserTranscriptBuffer.isNotEmpty() && !liveUserTranscriptBuffer.endsWith(" ")) {
                            liveUserTranscriptBuffer.append(' ')
                        }
                        liveUserTranscriptBuffer.append(event.text.trim())
                        val combined = liveUserTranscriptBuffer.toString().trim()
                        val turnId = pendingUserTurnId.takeIf { it > 0L } ?: conversationStore.currentTurnId()
                        conversationStore.updateStreamingMessage(
                            turnId = turnId,
                            role = VoiceMessageRole.USER,
                            fullText = combined,
                            isStreaming = !event.isFinal
                        )
                        _state.update { it.copy(liveUserTranscript = combined) }
                    }

                    is VoiceServerEvent.OutputTranscript -> {
                        val activeTurn = conversationStore.currentTurnId()
                        if (isAppInBackground ||
                            _state.value.interactionMode != AssistantInteractionMode.VOICE ||
                            activeVoiceTurnId <= 0L ||
                            activeTurn != activeVoiceTurnId
                        ) {
                            return@collect
                        }
                        if (liveAssistantTranscriptBuffer.isNotEmpty() && !liveAssistantTranscriptBuffer.endsWith(" ")) {
                            liveAssistantTranscriptBuffer.append(' ')
                        }
                        liveAssistantTranscriptBuffer.append(event.text.trim())
                        val combined = liveAssistantTranscriptBuffer.toString().trim()
                        // Do NOT create duplicate text paragraphs when this turn already renders structured
                        // restaurant or food cards (keep spoken transcript live in status/audio only).
                        if (!conversationStore.hasStructuredCardsForTurn(activeTurn)) {
                            conversationStore.updateStreamingMessage(
                                turnId = activeTurn,
                                role = VoiceMessageRole.ASSISTANT,
                                fullText = combined,
                                isStreaming = !event.isFinal,
                                structuredResponse = VoiceStructuredResponse(
                                    type = VoiceResponseType.CONVERSATIONAL_TEXT,
                                    bodyText = combined,
                                    spokenResponse = combined
                                )
                            )
                        }
                        _state.update {
                            it.copy(
                                liveAssistantTranscript = combined,
                                phase = if (it.playbackState == VoicePlaybackState.SPEAKING) {
                                    VoiceAssistantPhase.SPEAKING
                                } else {
                                    VoiceAssistantPhase.RESPONDING
                                },
                                statusMessage = if (it.playbackState == VoicePlaybackState.SPEAKING) {
                                    VoiceAssistantPhase.SPEAKING.userVisibleStatus
                                } else {
                                    VoiceAssistantPhase.RESPONDING.userVisibleStatus
                                }
                            )
                        }
                    }

                    is VoiceServerEvent.AudioOutput -> {
                        val currentState = _state.value
                        val activeTurn = conversationStore.currentTurnId()
                        // Defense-in-depth: strictly reject ALL audio output outside an active VOICE turn
                        if (isAppInBackground ||
                            explicitlyClosed ||
                            currentState.interactionMode != AssistantInteractionMode.VOICE ||
                            currentState.phase == VoiceAssistantPhase.PAUSED ||
                            activeVoiceTurnId <= 0L ||
                            activeTurn != activeVoiceTurnId
                        ) {
                            return@collect
                        }
                        if (!audioFocusCoordinator.hasFocus.value) {
                            audioFocusCoordinator.requestAudioFocus()
                        }
                        audioPlaybackDataSource.startSession(activeTurn)
                        val chunk = VoiceAudioChunk(
                            sequenceNumber = playbackSequence.incrementAndGet(),
                            timestampMs = System.currentTimeMillis(),
                            turnId = activeTurn,
                            pcmBytes = event.pcmBytes,
                            sampleRateHz = event.sampleRateHz
                        )
                        if (audioPlaybackDataSource.enqueueChunk(chunk)) {
                            _state.update {
                                it.copy(
                                    hasAudioFocus = audioFocusCoordinator.hasFocus.value,
                                    playbackState = VoicePlaybackState.SPEAKING,
                                    phase = VoiceAssistantPhase.SPEAKING,
                                    statusMessage = VoiceAssistantPhase.SPEAKING.userVisibleStatus
                                )
                            }
                        }
                    }

                    VoiceServerEvent.InterruptedByServer -> {
                        if (_state.value.interactionMode != AssistantInteractionMode.VOICE || activeVoiceTurnId <= 0L) {
                            return@collect
                        }
                        interruptAssistant("Interrupted by server VAD")
                    }

                    is VoiceServerEvent.ToolCallRequested -> {
                        if (_state.value.interactionMode != AssistantInteractionMode.VOICE || activeVoiceTurnId <= 0L) {
                            return@collect
                        }
                        handleToolCallsFromGemini(event.calls)
                    }

                    is VoiceServerEvent.ToolCallCancelled -> {
                        activeToolJob?.cancel()
                        activeToolJob = null
                    }

                    VoiceServerEvent.TurnComplete -> {
                        if (_state.value.interactionMode != AssistantInteractionMode.VOICE) {
                            return@collect
                        }
                        val activeTurn = conversationStore.currentTurnId()
                        if (liveAssistantTranscriptBuffer.isNotEmpty() &&
                            !conversationStore.hasStructuredCardsForTurn(activeTurn)
                        ) {
                            val finalText = liveAssistantTranscriptBuffer.toString()
                            conversationStore.updateStreamingMessage(
                                turnId = activeTurn,
                                role = VoiceMessageRole.ASSISTANT,
                                fullText = finalText,
                                isStreaming = false,
                                structuredResponse = VoiceStructuredResponse(
                                    type = VoiceResponseType.CONVERSATIONAL_TEXT,
                                    bodyText = finalText,
                                    spokenResponse = finalText
                                )
                            )
                        }
                        liveAssistantTranscriptBuffer.clear()
                        if (liveUserTranscriptBuffer.isNotEmpty()) {
                            val finalUserText = liveUserTranscriptBuffer.toString().trim()
                            val userTurnId = pendingUserTurnId.takeIf { it > 0L } ?: activeTurn
                            if (finalUserText.isNotEmpty()) {
                                conversationStore.updateStreamingMessage(
                                    turnId = userTurnId,
                                    role = VoiceMessageRole.USER,
                                    fullText = finalUserText,
                                    isStreaming = false
                                )
                            }
                            liveUserTranscriptBuffer.clear()
                        }
                        pendingUserTurnId = 0L
                        _state.update { current ->
                            current.copy(
                                liveAssistantTranscript = "",
                                liveUserTranscript = "",
                                playbackState = VoicePlaybackState.IDLE,
                                phase = when {
                                    current.recordingState == VoiceRecordingState.LISTENING -> VoiceAssistantPhase.LISTENING
                                    current.connectionState == VoiceConnectionState.CONNECTED -> VoiceAssistantPhase.IDLE
                                    else -> current.phase
                                },
                                statusMessage = when {
                                    current.recordingState == VoiceRecordingState.LISTENING -> VoiceAssistantPhase.LISTENING.userVisibleStatus
                                    current.connectionState == VoiceConnectionState.CONNECTED -> VoiceAssistantPhase.IDLE.userVisibleStatus
                                    else -> current.statusMessage
                                }
                            )
                        }
                    }

                    is VoiceServerEvent.SessionResumptionUpdated -> {
                        if (event.resumable && !event.newHandle.isNullOrBlank()) {
                            conversationStore.updateResumptionHandle(event.newHandle)
                        }
                    }

                    is VoiceServerEvent.GoingAway -> {
                        if (!explicitlyClosed &&
                            !isAppInBackground &&
                            _state.value.interactionMode == AssistantInteractionMode.VOICE
                        ) {
                            webSocketDataSource.close(isExplicitClose = false)
                            scheduleBoundedReconnect("Session refreshing before server rotation")
                        }
                    }

                    is VoiceServerEvent.Disconnected -> {
                        if (!event.wasGraceful &&
                            !explicitlyClosed &&
                            !isAppInBackground &&
                            _state.value.interactionMode == AssistantInteractionMode.VOICE
                        ) {
                            scheduleBoundedReconnect(event.reason)
                        }
                    }

                    is VoiceServerEvent.Error -> {
                        if (_state.value.interactionMode == AssistantInteractionMode.VOICE) {
                            handleConnectionFailure(event.error)
                        }
                    }
                }
            }
        }
    }

    /** Runs a text-mode Gemini turn, streams partial replies, and dispatches requested FoodFlow tools. */
    private suspend fun executeTextChatTurn(userText: String, turnId: Long) {
        val context = conversationStore.conversation.value
        val result = textChatDataSource.sendTextMessage(
            userText = userText,
            turnId = turnId,
            context = context,
            onToolCalls = { calls -> executeToolCallsForTextTurn(calls, turnId) },
            onPartialText = { partialText ->
                if (conversationStore.currentTurnId() == turnId &&
                    _state.value.interactionMode == AssistantInteractionMode.TEXT &&
                    partialText.isNotBlank() &&
                    !conversationStore.hasStructuredCardsForTurn(turnId)
                ) {
                    conversationStore.updateStreamingMessage(
                        turnId = turnId,
                        role = VoiceMessageRole.ASSISTANT,
                        fullText = partialText,
                        isStreaming = true,
                        structuredResponse = VoiceStructuredResponse(
                            type = VoiceResponseType.CONVERSATIONAL_TEXT,
                            bodyText = partialText,
                            spokenResponse = ""
                        )
                    )
                    _state.update { current ->
                        if (current.interactionMode != AssistantInteractionMode.TEXT || current.activeTurnId != turnId) {
                            current
                        } else {
                            current.copy(
                                phase = VoiceAssistantPhase.RESPONDING,
                                statusMessage = VoiceAssistantPhase.RESPONDING.userVisibleStatus
                            )
                        }
                    }
                }
            }
        )

        if (conversationStore.currentTurnId() != turnId ||
            _state.value.interactionMode != AssistantInteractionMode.TEXT
        ) {
            return
        }

        if (result.isSuccess) {
            val response = result.getOrThrow()
            val replyText = response.replyText.trim()
            if (replyText.isNotBlank() && !conversationStore.hasStructuredCardsForTurn(turnId)) {
                conversationStore.updateStreamingMessage(
                    turnId = turnId,
                    role = VoiceMessageRole.ASSISTANT,
                    fullText = replyText,
                    isStreaming = false,
                    structuredResponse = VoiceStructuredResponse(
                        type = VoiceResponseType.CONVERSATIONAL_TEXT,
                        bodyText = replyText,
                        spokenResponse = ""
                    )
                )
            }
            _state.update { current ->
                if (current.interactionMode != AssistantInteractionMode.TEXT || current.activeTurnId != turnId) {
                    current
                } else {
                    current.copy(
                        phase = VoiceAssistantPhase.IDLE,
                        playbackState = VoicePlaybackState.IDLE,
                        liveAssistantTranscript = "",
                        statusMessage = VoiceAssistantPhase.IDLE.userVisibleStatus
                    )
                }
            }
        } else {
            val err = result.exceptionOrNull()
            if (err is CancellationException && err !is kotlinx.coroutines.TimeoutCancellationException) {
                throw err
            }
            executeLocalFallbackTurn(userText, turnId)
        }
    }

    /** Executes text-chat tool calls locally and returns their results to Gemini for the final reply. */
    private suspend fun executeToolCallsForTextTurn(
        calls: List<VoiceToolCallInvocation>,
        capturedTurnId: Long
    ): List<Pair<VoiceToolCallInvocation, VoiceActionResult>> {
        if (conversationStore.currentTurnId() != capturedTurnId ||
            _state.value.interactionMode != AssistantInteractionMode.TEXT
        ) {
            return emptyList()
        }
        _state.update {
            it.copy(
                phase = VoiceAssistantPhase.THINKING,
                statusMessage = VoiceAssistantPhase.THINKING.userVisibleStatus
            )
        }
        val responses = mutableListOf<Pair<VoiceToolCallInvocation, VoiceActionResult>>()
        for (call in calls) {
            if (conversationStore.currentTurnId() != capturedTurnId ||
                _state.value.interactionMode != AssistantInteractionMode.TEXT
            ) {
                break
            }
            val domainAction = GeminiFoodFlowToolSchema.toDomainActionRequest(call)
            val result = if (domainAction != null) {
                executeAction(domainAction)
            } else {
                VoiceActionResult(
                    success = false,
                    summaryForVoice = "That action is not supported in FoodFlow."
                )
            }
            val structured = result.structuredResponse
            if (structured != null) {
                val displayHeadline = structured.headline.ifBlank { result.summaryForVoice }
                conversationStore.updateStreamingMessage(
                    turnId = capturedTurnId,
                    role = VoiceMessageRole.TOOL_ACTION,
                    fullText = displayHeadline,
                    isStreaming = false,
                    recommendations = result.recommendations,
                    structuredResponse = structured.copy(spokenResponse = "")
                )
            } else if (result.recommendations.isNotEmpty()) {
                conversationStore.updateStreamingMessage(
                    turnId = capturedTurnId,
                    role = VoiceMessageRole.TOOL_ACTION,
                    fullText = "Recommended options",
                    isStreaming = false,
                    recommendations = result.recommendations
                )
            }
            responses += call to result
        }
        return responses
    }

    /** Executes Live tool calls asynchronously and sends each result back over the WebSocket. */
    private fun handleToolCallsFromGemini(calls: List<VoiceToolCallInvocation>) {
        val capturedTurnId = conversationStore.currentTurnId()
        activeToolJob?.cancel()
        activeToolJob = sessionScope.launch {
            _state.update {
                it.copy(
                    phase = VoiceAssistantPhase.THINKING,
                    statusMessage = VoiceAssistantPhase.THINKING.userVisibleStatus
                )
            }
            val responses = mutableListOf<Pair<VoiceToolCallInvocation, VoiceActionResult>>()
            for (call in calls) {
                if (!isActive || conversationStore.currentTurnId() != capturedTurnId) return@launch
                val domainAction = GeminiFoodFlowToolSchema.toDomainActionRequest(call)
                val result = if (domainAction != null) {
                    executeAction(domainAction)
                } else {
                    VoiceActionResult(
                        success = false,
                        summaryForVoice = "That action is not supported in FoodFlow."
                    )
                }
                val structured = result.structuredResponse
                if (structured != null) {
                    val displayHeadline = structured.headline.ifBlank { result.summaryForVoice }
                    conversationStore.updateStreamingMessage(
                        turnId = capturedTurnId,
                        role = VoiceMessageRole.TOOL_ACTION,
                        fullText = displayHeadline,
                        isStreaming = false,
                        recommendations = result.recommendations,
                        structuredResponse = structured
                    )
                } else if (result.recommendations.isNotEmpty()) {
                    conversationStore.updateStreamingMessage(
                        turnId = capturedTurnId,
                        role = VoiceMessageRole.TOOL_ACTION,
                        fullText = "Recommended options",
                        isStreaming = false,
                        recommendations = result.recommendations
                    )
                }
                responses += call to result
            }
            if (isActive && conversationStore.currentTurnId() == capturedTurnId) {
                _state.update {
                    it.copy(
                        phase = VoiceAssistantPhase.RESPONDING,
                        statusMessage = VoiceAssistantPhase.RESPONDING.userVisibleStatus
                    )
                }
                webSocketDataSource.sendToolResponses(responses)
            }
        }
    }

    /** Answers with local catalog data when Gemini is unavailable and marks the response as fallback. */
    private suspend fun executeLocalFallbackTurn(userText: String, turnId: Long) {
        _state.update {
            it.copy(
                isFallbackMode = true,
                phase = VoiceAssistantPhase.THINKING,
                statusMessage = VoiceAssistantPhase.THINKING.userVisibleStatus
            )
        }
        val (actionResult, labeledReply) = localFallbackEngine.handleFallbackQuery(
            userQuery = userText,
            context = conversationStore.conversation.value
        )
        if (actionResult.recommendations.isNotEmpty()) {
            conversationStore.updateRecommendations(actionResult.recommendations)
        }
        if (actionResult.requiresConfirmation != null) {
            conversationStore.setPendingConfirmation(actionResult.requiresConfirmation)
        }
        actionResult.navigationTarget?.let { _navigationRequests.tryEmit(it) }

        val structured = actionResult.structuredResponse ?: VoiceStructuredResponse(
            type = VoiceResponseType.CONVERSATIONAL_TEXT,
            bodyText = labeledReply
        )
        // When structured cards exist, keep message text concise (never duplicate restaurant/food names)
        val displayText = when (structured.type) {
            VoiceResponseType.RESTAURANT_RECOMMENDATIONS,
            VoiceResponseType.FOOD_RECOMMENDATIONS,
            VoiceResponseType.CART_SUMMARY -> structured.headline.ifBlank { labeledReply }
            else -> labeledReply
        }

        conversationStore.appendMessage(
            VoiceConversationMessage(
                id = "fallback_${turnId}_${UUID.randomUUID().toString().take(6)}",
                turnId = turnId,
                role = VoiceMessageRole.FALLBACK_LOCAL,
                text = displayText,
                timestampMs = System.currentTimeMillis(),
                isFallback = true,
                recommendations = actionResult.recommendations,
                structuredResponse = structured
            )
        )
        _state.update {
            it.copy(
                isFallbackMode = true,
                phase = if (it.activeError != null) VoiceAssistantPhase.ERROR else VoiceAssistantPhase.IDLE,
                statusMessage = if (it.activeError != null) it.activeError.userMessage else VoiceAssistantPhase.IDLE.userVisibleStatus
            )
        }
    }

    /** Stops active capture, publishes the failure, and starts local fallback when supported. */
    private fun handleConnectionFailure(error: VoiceAssistantError) {
        stopRecordingInternal()
        val nonRetryable = !error.canRetry ||
            error.category == VoiceErrorCategory.QUOTA_EXHAUSTED ||
            error.category == VoiceErrorCategory.AUTH_FAILURE ||
            error.category == VoiceErrorCategory.INVALID_MODEL ||
            error.category == VoiceErrorCategory.PERMISSION_DENIED ||
            error.category == VoiceErrorCategory.PERMISSION_PERMANENTLY_DENIED

        _state.update {
            it.copy(
                connectionState = VoiceConnectionState.ERROR,
                phase = VoiceAssistantPhase.ERROR,
                activeError = error,
                isFallbackMode = error.fallbackAvailable,
                statusMessage = error.userMessage
            )
        }

        if (!nonRetryable && !explicitlyClosed && !isAppInBackground && connectivityObserver.isCurrentlyOnline()) {
            scheduleBoundedReconnect(error.userMessage)
        }
    }

    /** Retries transient Live connection failures with capped backoff while the session remains active. */
    private fun scheduleBoundedReconnect(reason: String) {
        if (explicitlyClosed || isAppInBackground) return
        if (reconnectJob?.isActive == true) return
        val nextAttempt = _state.value.reconnectAttempt + 1
        if (nextAttempt > MAX_RECONNECT_ATTEMPTS) {
            _state.update {
                it.copy(
                    connectionState = VoiceConnectionState.ERROR,
                    phase = VoiceAssistantPhase.ERROR,
                    isFallbackMode = true,
                    activeError = VoiceAssistantError(
                        category = VoiceErrorCategory.SERVER_ERROR,
                        userMessage = "Unable to reconnect to Gemini voice. Local FoodFlow search is ready.",
                        recoveryHint = "Tap Retry to reconnect when ready.",
                        canRetry = true,
                        fallbackAvailable = true
                    ),
                    statusMessage = "Local FoodFlow Search Ready"
                )
            }
            return
        }

        _state.update {
            it.copy(
                reconnectAttempt = nextAttempt,
                connectionState = VoiceConnectionState.RECONNECTING,
                phase = VoiceAssistantPhase.RECONNECTING,
                statusMessage = "Connection lost. Trying to reconnect... ($nextAttempt/$MAX_RECONNECT_ATTEMPTS)"
            )
        }

        reconnectJob = sessionScope.launch {
            val expDelay = min(BASE_BACKOFF_MS * (1L shl (nextAttempt - 1)), MAX_BACKOFF_MS)
            val jitter = Random.nextLong(150L, 650L)
            runCatching { Log.i(TAG, "Scheduling reconnect attempt $nextAttempt in ${expDelay + jitter}ms ($reason)") }
            delay(expDelay + jitter)
            if (!explicitlyClosed && !isAppInBackground && !webSocketDataSource.isConnected) {
                connectSession(isManualRetry = false)
            }
        }
    }

    /** Watches capture/playback levels and state to update assistant UI telemetry. */
    private fun observeAudioStreamsTelemetry() {
        sessionScope.launch {
            audioRecordingDataSource.inputAmplitude.collect { amp ->
                _state.update {
                    it.copy(
                        inputAmplitude = if (it.interactionMode == AssistantInteractionMode.VOICE) amp else 0f
                    )
                }
            }
        }
        sessionScope.launch {
            audioPlaybackDataSource.outputAmplitude.collect { amp ->
                _state.update {
                    it.copy(
                        outputAmplitude = if (it.interactionMode == AssistantInteractionMode.VOICE) amp else 0f
                    )
                }
            }
        }
        sessionScope.launch {
            audioRecordingDataSource.activeAudioEffectsSummary.collect { summary ->
                _state.update { it.copy(audioEffectsSummary = summary) }
            }
        }
        sessionScope.launch {
            audioFocusCoordinator.activeRoute.collect { route ->
                _state.update { it.copy(activeAudioRoute = route) }
            }
        }
        sessionScope.launch {
            audioFocusCoordinator.hasFocus.collect { focus ->
                _state.update { it.copy(hasAudioFocus = focus) }
            }
        }
    }

    /** Reacts to Android audio focus and route changes by pausing or restoring assistant audio. */
    private fun observeAudioFocusEvents() {
        sessionScope.launch {
            audioFocusCoordinator.focusEvents.collect { event ->
                val current = _state.value
                val isVoiceOrAudioActive = current.interactionMode == AssistantInteractionMode.VOICE ||
                    current.recordingState == VoiceRecordingState.LISTENING ||
                    current.playbackState == VoicePlaybackState.SPEAKING
                if (!isVoiceOrAudioActive) return@collect

                when (event) {
                    VoiceAudioFocusEvent.LOST_PERMANENT,
                    VoiceAudioFocusEvent.LOST_TRANSIENT -> {
                        stopCurrentInteraction()
                        _state.update {
                            it.copy(
                                activeError = VoiceAssistantError(
                                    category = VoiceErrorCategory.AUDIO_FOCUS_LOST,
                                    userMessage = "Audio playback was interrupted by another app or call.",
                                    recoveryHint = "Tap Speak when ready.",
                                    canRetry = true,
                                    fallbackAvailable = true
                                ),
                                statusMessage = "Audio playback was interrupted."
                            )
                        }
                    }

                    VoiceAudioFocusEvent.BECOMING_NOISY -> {
                        stopCurrentInteraction()
                        _state.update {
                            it.copy(
                                activeError = VoiceAssistantError(
                                    category = VoiceErrorCategory.DEVICE_DISCONNECTED,
                                    userMessage = "Audio device disconnected.",
                                    recoveryHint = "Tap Speak to continue on speaker.",
                                    canRetry = true,
                                    fallbackAvailable = true
                                ),
                                statusMessage = "Audio device disconnected"
                            )
                        }
                    }

                    VoiceAudioFocusEvent.LOST_TRANSIENT_CAN_DUCK -> {
                        audioPlaybackDataSource.setMuted(true)
                    }

                    VoiceAudioFocusEvent.GAINED -> {
                        if (!_state.value.isMuted) {
                            audioPlaybackDataSource.setMuted(false)
                        }
                    }

                    VoiceAudioFocusEvent.ROUTE_CHANGED -> Unit
                }
            }
        }
    }

    /** Switches between Live connectivity and local fallback as network availability changes. */
    private fun observeConnectivityChanges() {
        sessionScope.launch {
            connectivityObserver.status.collect { status ->
                when (status) {
                    ConnectivityStatus.OFFLINE -> {
                        val snapshot = _state.value
                        val hasActiveOrOpenVoice = snapshot.isOpen ||
                            snapshot.isMinimized ||
                            snapshot.isSessionActive ||
                            webSocketDataSource.isConnected ||
                            snapshot.connectionState != VoiceConnectionState.CLOSED

                        if (hasActiveOrOpenVoice) {
                            val closeEpoch = sessionCloseEpoch.incrementAndGet()
                            reconnectJob?.cancel()
                            reconnectJob = null
                            activeTextJob?.cancel()
                            activeTextJob = null
                            activeToolJob?.cancel()
                            activeToolJob = null
                            activeVoiceTurnId = 0L
                            pendingUserTurnId = 0L

                            stopRecordingInternal()
                            audioRecordingDataSource.release()
                            val offlineTurn = conversationStore.advanceTurnId()
                            audioPlaybackDataSource.stopImmediatelyAndFlush(offlineTurn)
                            audioPlaybackDataSource.release()
                            audioFocusCoordinator.release()
                            clearLiveTranscriptBuffers()

                            _state.update { current ->
                                current.copy(
                                    interactionMode = AssistantInteractionMode.TEXT,
                                    connectionState = VoiceConnectionState.CLOSED,
                                    phase = VoiceAssistantPhase.CLOSED,
                                    recordingState = VoiceRecordingState.IDLE,
                                    playbackState = VoicePlaybackState.IDLE,
                                    inputAmplitude = 0f,
                                    outputAmplitude = 0f,
                                    hasAudioFocus = false,
                                    liveUserTranscript = "",
                                    liveAssistantTranscript = "",
                                    reconnectAttempt = 0,
                                    isFallbackMode = true,
                                    activeError = if (current.isOpen) {
                                        VoiceAssistantError(
                                            category = VoiceErrorCategory.NO_INTERNET,
                                            userMessage = "You're offline. Voice session closed. Local FoodFlow search is still available.",
                                            recoveryHint = "Reconnect to start a new voice session.",
                                            canRetry = true,
                                            fallbackAvailable = true
                                        )
                                    } else {
                                        null
                                    },
                                    statusMessage = if (current.isOpen) {
                                        "Offline — Voice session closed"
                                    } else {
                                        VoiceAssistantPhase.CLOSED.userVisibleStatus
                                    }
                                )
                            }

                            backgroundCloseJob?.cancel()
                            backgroundCloseJob = sessionScope.launch {
                                lifecycleMutex.withLock {
                                    if (sessionCloseEpoch.get() == closeEpoch || !connectivityObserver.isCurrentlyOnline()) {
                                        runCatching { webSocketDataSource.close(isExplicitClose = true) }
                                    }
                                }
                            }
                        }
                    }

                    ConnectivityStatus.RESTORED, ConnectivityStatus.ONLINE -> {
                        // Session was closed on offline; never auto-reconnect WebSocket or restart mic.
                        // Simply clear offline error banner so user can type or tap Speak fresh.
                        if (_state.value.isOpen &&
                            !explicitlyClosed &&
                            !isAppInBackground &&
                            _state.value.activeError?.category == VoiceErrorCategory.NO_INTERNET
                        ) {
                            _state.update {
                                it.copy(
                                    isFallbackMode = false,
                                    activeError = null,
                                    phase = VoiceAssistantPhase.IDLE,
                                    statusMessage = VoiceAssistantPhase.IDLE.userVisibleStatus
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** Stops the active microphone turn and exposes recovery when permission is revoked. */
    private fun handlePermissionRevokedMidSession() {
        stopRecordingInternal()
        audioFocusCoordinator.abandonAudioFocus()
        _state.update {
            it.copy(
                hasMicPermission = false,
                recordingState = VoiceRecordingState.PERMISSION_REQUIRED,
                phase = VoiceAssistantPhase.ERROR,
                activeError = VoiceAssistantError(
                    category = VoiceErrorCategory.PERMISSION_REVOKED,
                    userMessage = "Microphone permission was revoked while listening.",
                    recoveryHint = "Re-enable Microphone permission or use text input.",
                    canRetry = false,
                    fallbackAvailable = true
                ),
                statusMessage = "Microphone permission is required to speak."
            )
        }
    }

    /** Cancels the recorder collector and resets recording state shared by session lifecycle paths. */
    private fun stopRecordingInternal() {
        recordingCollectJob?.cancel()
        recordingCollectJob = null
        audioRecordingDataSource.stopRecording()
        consecutiveSpeechFramesWhileSpeaking = 0
    }

    /** Clears partial transcripts so content from one turn cannot leak into the next. */
    private fun clearLiveTranscriptBuffers() {
        liveUserTranscriptBuffer.clear()
        liveAssistantTranscriptBuffer.clear()
    }
}
