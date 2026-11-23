package com.foodflow.data.voice

import android.util.Log
import com.foodflow.core.common.DispatcherProvider
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.domain.model.VoiceActionResult
import com.foodflow.domain.model.VoiceAssistantError
import com.foodflow.domain.model.VoiceAudioChunk
import com.foodflow.domain.model.VoiceErrorCategory
import com.foodflow.domain.model.VoiceServerEvent
import com.foodflow.domain.model.VoiceToolCallInvocation
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.InlineData
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.LiveGenerationConfig
import com.google.firebase.ai.type.LiveServerContent
import com.google.firebase.ai.type.LiveServerGoAway
import com.google.firebase.ai.type.LiveServerSetupComplete
import com.google.firebase.ai.type.LiveServerToolCall
import com.google.firebase.ai.type.LiveServerToolCallCancellation
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.LiveSessionResumptionUpdate
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SessionResumptionConfig
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GeminiLiveWS"
private const val LIVE_MODEL_ID = "gemini-3.1-flash-live-preview"
private const val CONNECT_TIMEOUT_MS = 30_000L
private const val AUDIO_INPUT_MIME = "audio/pcm;rate=16000"

/**
 * Dedicated bidirectional WebSocket manager for Gemini Live API via Firebase AI Logic.
 * - Authenticated through Firebase App Check (zero hardcoded API keys in client code).
 * - Guarantees at most one active WebSocket session via connectionMutex.
 * - Uses bounded SharedFlow buffers so incoming events never exhaust memory.
 * - Runs all WebSocket I/O and frame parsing strictly on dispatchers.io.
 */
@OptIn(PublicPreviewAPI::class)
@Singleton
class FirebaseGeminiLiveWebSocketDataSourceImpl @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val connectivityObserver: ConnectivityObserver,
    private val errorMapper: VoiceErrorMapper
) : VoiceWebSocketDataSource {

    override val activeModelName: String = LIVE_MODEL_ID

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val connectionMutex = Mutex()

    @Volatile
    private var activeSession: LiveSession? = null
    private var receiveJob: Job? = null

    @Volatile
    private var pendingConnectJob: Job? = null

    @Volatile
    private var explicitCloseRequested: Boolean = false

    private val _serverEvents = MutableSharedFlow<VoiceServerEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val serverEvents: SharedFlow<VoiceServerEvent> = _serverEvents.asSharedFlow()

    override val isConnected: Boolean
        get() = activeSession?.let { !it.isClosed() } == true

    private val tools by lazy { GeminiFoodFlowToolSchema.buildTools() }

    /** Opens one Gemini Live session, optionally resumes it, and starts receiving server events. */
    override suspend fun connect(
        systemPrompt: String,
        resumptionHandle: String?
    ): Result<Unit> = connectionMutex.withLock {
        if (isConnected) {
            return@withLock Result.success(Unit)
        }
        if (!connectivityObserver.isCurrentlyOnline()) {
            val offlineErr = VoiceAssistantError(
                category = VoiceErrorCategory.NO_INTERNET,
                userMessage = "You're offline. Switched to local FoodFlow menu search.",
                recoveryHint = "Reconnect to the internet to resume live Gemini voice.",
                canRetry = true,
                fallbackAvailable = true
            )
            _serverEvents.tryEmit(VoiceServerEvent.Error(offlineErr))
            return@withLock Result.failure(IllegalStateException(offlineErr.userMessage))
        }

        explicitCloseRequested = false

        return@withLock withContext(dispatchers.io) {
            pendingConnectJob = coroutineContext[Job]
            try {
                // Lazy model creation inside connect() so Firebase.ai is never evaluated eagerly at startup
                val liveConfig = LiveGenerationConfig.Builder()
                    .setResponseModality(ResponseModality.AUDIO)
                    .setSpeechConfig(SpeechConfig(Voice("Aoede")))
                    .setInputAudioTranscription(AudioTranscriptionConfig())
                    .setOutputAudioTranscription(AudioTranscriptionConfig())
                    .build()

                val liveModel = Firebase.ai.liveModel(
                    modelName = LIVE_MODEL_ID,
                    generationConfig = liveConfig,
                    tools = tools,
                    systemInstruction = content { text(systemPrompt) }
                )

                val session = withTimeout(CONNECT_TIMEOUT_MS) {
                    if (!resumptionHandle.isNullOrBlank()) {
                        liveModel.connect(SessionResumptionConfig(resumptionHandle))
                    } else {
                        liveModel.connect()
                    }
                }

                // If close() or backgrounding occurred while the handshake was in flight,
                // immediately close the newly opened session so no zombie socket survives.
                if (explicitCloseRequested) {
                    runCatching { session.stopReceiving() }
                    runCatching { session.close() }
                    return@withContext Result.failure(CancellationException("Session closed while connecting"))
                }

                activeSession = session
                _serverEvents.tryEmit(VoiceServerEvent.Connected)
                startReceiveLoop(session)
                Result.success(Unit)
            } catch (ce: CancellationException) {
                if (ce is kotlinx.coroutines.TimeoutCancellationException) {
                    val mapped = errorMapper.mapThrowable(ce, connectivityObserver.isCurrentlyOnline(), TAG)
                    _serverEvents.tryEmit(VoiceServerEvent.Error(mapped.error))
                    Result.failure(ce)
                } else {
                    throw ce
                }
            } catch (e: Exception) {
                if (explicitCloseRequested) {
                    return@withContext Result.failure(CancellationException("Session closed while connecting"))
                }
                val mapped = errorMapper.mapThrowable(e, connectivityObserver.isCurrentlyOnline(), TAG)
                _serverEvents.tryEmit(VoiceServerEvent.Error(mapped.error))
                Result.failure(e)
            } finally {
                pendingConnectJob = null
            }
        }
    }

    /** Converts incoming Gemini messages into app events for transcripts, audio, tool calls, and completion. */
    private fun startReceiveLoop(session: LiveSession) {
        receiveJob?.cancel()
        receiveJob = scope.launch {
            try {
                session.receive().collect { message ->
                    when (message) {
                        is LiveServerSetupComplete -> {
                            _serverEvents.tryEmit(VoiceServerEvent.SetupComplete)
                        }

                        is LiveServerContent -> {
                            if (message.interrupted) {
                                _serverEvents.tryEmit(VoiceServerEvent.InterruptedByServer)
                            }
                            message.inputTranscription?.text?.takeIf { it.isNotBlank() }?.let { inputText ->
                                _serverEvents.tryEmit(VoiceServerEvent.InputTranscript(inputText, isFinal = false))
                            }
                            message.outputTranscription?.text?.takeIf { it.isNotBlank() }?.let { outText ->
                                _serverEvents.tryEmit(VoiceServerEvent.OutputTranscript(outText, isFinal = false))
                            }
                            message.content?.parts?.forEach { part ->
                                if (part is InlineDataPart && part.mimeType.startsWith("audio/")) {
                                    val bytes = part.inlineData
                                    if (bytes.isNotEmpty()) {
                                        val sampleRate = parseSampleRate(part.mimeType)
                                        _serverEvents.tryEmit(VoiceServerEvent.AudioOutput(bytes, sampleRate))
                                    }
                                }
                            }
                            if (message.turnComplete || message.generationComplete) {
                                _serverEvents.tryEmit(VoiceServerEvent.TurnComplete)
                            }
                        }

                        is LiveServerToolCall -> {
                            val calls = message.functionCalls.map(GeminiFoodFlowToolSchema::toDomainInvocation)
                            if (calls.isNotEmpty()) {
                                _serverEvents.tryEmit(VoiceServerEvent.ToolCallRequested(calls))
                            }
                        }

                        is LiveServerToolCallCancellation -> {
                            _serverEvents.tryEmit(VoiceServerEvent.ToolCallCancelled(message.functionIds))
                        }

                        is LiveSessionResumptionUpdate -> {
                            _serverEvents.tryEmit(
                                VoiceServerEvent.SessionResumptionUpdated(
                                    newHandle = message.newHandle,
                                    resumable = message.resumable == true
                                )
                            )
                        }

                        is LiveServerGoAway -> {
                            val seconds = runCatching { message.timeLeft?.inWholeSeconds ?: 0L }.getOrDefault(0L)
                            _serverEvents.tryEmit(VoiceServerEvent.GoingAway(seconds))
                        }

                        else -> {
                            Log.d(TAG, "Ignored unknown LiveServerMessage: ${message.javaClass.simpleName}")
                        }
                    }
                }
                if (activeSession === session) {
                    activeSession = null
                    _serverEvents.tryEmit(
                        VoiceServerEvent.Disconnected(
                            reason = if (explicitCloseRequested) "Closed by user" else "Stream ended",
                            wasGraceful = explicitCloseRequested
                        )
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (activeSession === session) {
                    activeSession = null
                }
                if (!explicitCloseRequested) {
                    val mapped = errorMapper.mapThrowable(e, connectivityObserver.isCurrentlyOnline(), TAG)
                    _serverEvents.tryEmit(VoiceServerEvent.Error(mapped.error))
                    _serverEvents.tryEmit(
                        VoiceServerEvent.Disconnected(
                            reason = mapped.error.userMessage,
                            wasGraceful = false
                        )
                    )
                }
            }
        }
    }

    /** Sends captured PCM bytes to the active Gemini Live session on the I/O dispatcher. */
    override suspend fun sendAudioChunk(chunk: VoiceAudioChunk): Result<Unit> =
        withContext(dispatchers.io) {
            val session = activeSession ?: return@withContext Result.failure(IllegalStateException("WebSocket not connected"))
            if (session.isClosed()) return@withContext Result.failure(IllegalStateException("WebSocket closed"))
            try {
                session.sendAudioRealtime(
                    InlineData(
                        data = chunk.pcmBytes,
                        mimeType = AUDIO_INPUT_MIME
                    )
                )
                Result.success(Unit)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "Failed sending audio chunk #${chunk.sequenceNumber}", e)
                Result.failure(e)
            }
        }

    /** Sends a text turn over the Live socket and optionally marks the turn complete. */
    override suspend fun sendTextTurn(text: String, endOfTurn: Boolean): Result<Unit> =
        withContext(dispatchers.io) {
            val session = activeSession ?: return@withContext Result.failure(IllegalStateException("WebSocket not connected"))
            if (session.isClosed()) return@withContext Result.failure(IllegalStateException("WebSocket closed"))
            try {
                session.send(text = text, turnComplete = endOfTurn)
                Result.success(Unit)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                val mapped = errorMapper.mapThrowable(e, connectivityObserver.isCurrentlyOnline(), TAG)
                _serverEvents.tryEmit(VoiceServerEvent.Error(mapped.error))
                Result.failure(e)
            }
        }

    /** Marks the start of streamed audio input for the active Live turn. */
    override suspend fun sendActivityStart(): Result<Unit> = withContext(dispatchers.io) {
        val session = activeSession ?: return@withContext Result.failure(IllegalStateException("WebSocket not connected"))
        runCatching { session.sendStartActivityRealtime() }
    }

    /** Marks the end of streamed audio input so Gemini can finish processing the turn. */
    override suspend fun sendActivityEnd(): Result<Unit> = withContext(dispatchers.io) {
        val session = activeSession ?: return@withContext Result.failure(IllegalStateException("WebSocket not connected"))
        runCatching { session.sendStopActivityRealtime() }
    }

    /** Sends local FoodFlow action results back to Gemini for its response continuation. */
    override suspend fun sendToolResponses(
        responses: List<Pair<VoiceToolCallInvocation, VoiceActionResult>>
    ): Result<Unit> = withContext(dispatchers.io) {
        val session = activeSession ?: return@withContext Result.failure(IllegalStateException("WebSocket not connected"))
        if (session.isClosed()) return@withContext Result.failure(IllegalStateException("WebSocket closed"))
        try {
            val parts = responses.map { (invocation, result) ->
                GeminiFoodFlowToolSchema.toFunctionResponsePart(invocation, result)
            }
            session.sendFunctionResponse(parts)
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Failed sending tool responses", e)
            Result.failure(e)
        }
    }

    /** Cancels connection/receive work and closes the session, respecting explicit user closure. */
    override suspend fun close(isExplicitClose: Boolean) {
        if (isExplicitClose) {
            explicitCloseRequested = true
        }
        // Cancel any in-flight handshake immediately instead of blocking up to CONNECT_TIMEOUT_MS
        pendingConnectJob?.cancel()

        connectionMutex.withLock {
            explicitCloseRequested = isExplicitClose
            receiveJob?.cancel()
            receiveJob = null
            val session = activeSession
            activeSession = null
            if (session != null) {
                withContext(dispatchers.io) {
                    runCatching { session.stopReceiving() }
                    runCatching { session.close() }
                }
            }
            _serverEvents.tryEmit(
                VoiceServerEvent.Disconnected(
                    reason = if (isExplicitClose) "Closed by user" else "Session suspended",
                    wasGraceful = true
                )
            )
        }
    }

    /** Reads the PCM sample rate from the server-provided audio MIME type. */
    private fun parseSampleRate(mimeType: String): Int {
        val match = Regex("""rate=(\d+)""").find(mimeType)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 24_000
    }
}
