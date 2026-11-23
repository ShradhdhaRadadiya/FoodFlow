package com.foodflow.voice

import com.foodflow.data.voice.AssistantTextChatResponse
import com.foodflow.data.voice.VoiceAssistantActionDataSource
import com.foodflow.data.voice.VoiceAssistantSessionManager
import com.foodflow.data.voice.VoiceAudioFocusCoordinator
import com.foodflow.data.voice.VoiceAudioFocusEvent
import com.foodflow.data.voice.VoiceAudioPlaybackDataSource
import com.foodflow.data.voice.VoiceAudioRecordingDataSource
import com.foodflow.data.voice.VoiceConversationStoreImpl
import com.foodflow.data.voice.VoiceErrorMapper
import com.foodflow.data.voice.VoiceLocalFallbackEngine
import com.foodflow.data.voice.VoiceTextChatDataSource
import com.foodflow.data.voice.VoiceWebSocketDataSource
import com.foodflow.domain.model.AssistantInteractionMode
import com.foodflow.domain.model.VoiceActionRequest
import com.foodflow.domain.model.VoiceActionResult
import com.foodflow.domain.model.VoiceAssistantError
import com.foodflow.domain.model.VoiceAssistantPhase
import com.foodflow.domain.model.VoiceAudioChunk
import com.foodflow.domain.model.VoiceAudioRoute
import com.foodflow.domain.model.VoiceConnectionState
import com.foodflow.domain.model.VoiceConversationContext
import com.foodflow.domain.model.VoiceConversationMessage
import com.foodflow.domain.model.VoiceErrorCategory
import com.foodflow.domain.model.VoiceMessageRole
import com.foodflow.data.voice.PcmSpeechGainProcessor
import com.foodflow.domain.model.Restaurant
import com.foodflow.domain.model.VoicePlaybackState
import com.foodflow.domain.model.VoiceRecommendationItem
import com.foodflow.domain.model.VoiceRecordingState
import com.foodflow.domain.model.VoiceResponseType
import com.foodflow.domain.model.VoiceServerEvent
import com.foodflow.domain.model.VoiceSheetDetent
import com.foodflow.domain.model.VoiceStructuredResponse
import com.foodflow.domain.model.VoiceToolCallInvocation
import com.foodflow.testutil.FakeConnectivityObserver
import com.foodflow.testutil.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAssistantSessionAndInterruptionTest {

    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = TestDispatcherProvider(testDispatcher)
    private lateinit var connectivity: FakeConnectivityObserver
    private lateinit var fakeWs: FakeVoiceWebSocketDataSource
    private lateinit var fakeTextChat: FakeVoiceTextChatDataSource
    private lateinit var fakeRecorder: FakeVoiceAudioRecordingDataSource
    private lateinit var fakePlayback: FakeVoiceAudioPlaybackDataSource
    private lateinit var fakeFocus: FakeVoiceAudioFocusCoordinator
    private lateinit var fakeActionExecutor: FakeVoiceAssistantActionDataSource
    private lateinit var fakeFallback: FakeVoiceLocalFallbackEngine
    private lateinit var conversationStore: VoiceConversationStoreImpl
    private lateinit var manager: VoiceAssistantSessionManager

    @Before
    fun setUp() {
        connectivity = FakeConnectivityObserver(initialOnline = true)
        fakeWs = FakeVoiceWebSocketDataSource()
        fakeTextChat = FakeVoiceTextChatDataSource()
        fakeRecorder = FakeVoiceAudioRecordingDataSource()
        fakePlayback = FakeVoiceAudioPlaybackDataSource()
        fakeFocus = FakeVoiceAudioFocusCoordinator()
        fakeActionExecutor = FakeVoiceAssistantActionDataSource()
        fakeFallback = FakeVoiceLocalFallbackEngine()
        conversationStore = VoiceConversationStoreImpl()
        manager = VoiceAssistantSessionManager(
            webSocketDataSource = fakeWs,
            textChatDataSource = fakeTextChat,
            audioRecordingDataSource = fakeRecorder,
            audioPlaybackDataSource = fakePlayback,
            conversationStore = conversationStore,
            actionExecutor = fakeActionExecutor,
            audioFocusCoordinator = fakeFocus,
            localFallbackEngine = fakeFallback,
            connectivityObserver = connectivity,
            errorMapper = VoiceErrorMapper(),
            dispatchers = dispatchers
        )
    }

    @After
    fun tearDown() {
        manager.releaseResources()
    }

    // -------------------------------------------------------------------------
    // CONVERSATION TESTS
    // -------------------------------------------------------------------------

    @Test
    fun `start new conversation connects once in voice mode and continues across screen navigation`() = runTest(testDispatcher) {
        manager.openAssistant()
        manager.connectSession()
        advanceUntilIdle()

        val initialConversationId = manager.observeConversation().value.conversationId
        assertEquals(VoiceConnectionState.CONNECTED, manager.observeAssistantState().value.connectionState)
        assertEquals(VoiceSheetDetent.HALF_EXPANDED, manager.observeAssistantState().value.sheetDetent)
        assertEquals(1, fakeWs.connectCalls.get())
        assertTrue(manager.observeConversation().value.messages.isEmpty())

        // Navigate across multiple FoodFlow screens while keeping sheet detent persistent
        manager.setSheetDetent(VoiceSheetDetent.COLLAPSED)
        manager.updateScreenContext("Search", null, null)
        manager.updateScreenContext("Restaurant Detail", "rest_1", "Spice Route Kitchen")
        manager.updateScreenContext("Cart", null, null)
        advanceUntilIdle()

        assertEquals(initialConversationId, manager.observeConversation().value.conversationId)
        assertEquals("Cart", manager.observeConversation().value.currentScreenName)
        assertEquals("rest_1", manager.observeConversation().value.selectedRestaurantId)
        assertEquals(1, fakeWs.connectCalls.get())
        assertEquals(VoiceSheetDetent.COLLAPSED, manager.observeAssistantState().value.sheetDetent)
        assertTrue(manager.observeAssistantState().value.isMinimized)
    }

    @Test
    fun `follow-up referencing second recommendation resolves item 2 from conversation context`() = runTest(testDispatcher) {
        val recs = listOf(
            VoiceRecommendationItem(1, "f1", "Paneer Tikka", "r1", "Spice Route", 180.0, true, "Medium", 4.6, true),
            VoiceRecommendationItem(2, "f2", "Veg Dum Biryani", "r1", "Spice Route", 195.0, true, "Spicy", 4.7, true),
            VoiceRecommendationItem(3, "f3", "Masala Dosa", "r2", "Dakshin Express", 140.0, true, "Mild", 4.5, true)
        )
        conversationStore.updateRecommendations(recs)

        val resolved = conversationStore.resolveRecommendationByOrdinal(2)
        assertNotNull(resolved)
        assertEquals("f2", resolved?.foodId)
        assertEquals("Veg Dum Biryani", resolved?.foodName)
    }

    @Test
    fun `conversation history stays bounded to 40 messages and summarizes older turns`() = runTest(testDispatcher) {
        repeat(55) { i ->
            conversationStore.appendMessage(
                VoiceConversationMessage(
                    id = "msg_$i",
                    turnId = i.toLong(),
                    role = if (i % 2 == 0) VoiceMessageRole.USER else VoiceMessageRole.ASSISTANT,
                    text = "Turn message number $i about vegetarian dishes",
                    timestampMs = 1_000L + i
                )
            )
        }

        val ctx = conversationStore.conversation.value
        assertEquals(VoiceConversationContext.MAX_HISTORY_MESSAGES, ctx.messages.size)
        assertTrue(ctx.olderContextSummary.isNotBlank())
        assertTrue(ctx.messages.last().text.contains("54"))
    }

    @Test
    fun `restore conversation preserves resumption handle and history after reconnect`() = runTest(testDispatcher) {
        manager.openAssistant()
        manager.connectSession()
        advanceUntilIdle()

        fakeWs.emitServerEvent(VoiceServerEvent.SessionResumptionUpdated("resume_token_xyz", resumable = true))
        advanceUntilIdle()
        manager.sendTextMessage("Find veg food under 200")
        advanceUntilIdle()

        val restored = manager.restoreConversation()
        assertEquals("resume_token_xyz", restored.resumptionHandle)
        assertTrue(restored.messages.any { it.text.contains("Find veg food under 200") })
    }

    // -------------------------------------------------------------------------
    // WEBSOCKET TESTS
    // -------------------------------------------------------------------------

    @Test
    fun `duplicate concurrent connectSession calls establish only a single WebSocket connection`() = runTest(testDispatcher) {
        val jobs = (1..5).map { async { manager.connectSession() } }
        jobs.awaitAll()
        advanceUntilIdle()

        assertEquals(1, fakeWs.connectCalls.get())
        assertEquals(VoiceConnectionState.CONNECTED, manager.observeAssistantState().value.connectionState)
    }

    @Test
    fun `connection timeout and unexpected disconnection trigger bounded reconnect up to max 3 attempts`() = runTest(testDispatcher) {
        fakeWs.nextConnectFailure = IllegalStateException("timeout waiting for handshake")
        manager.openAssistant()
        manager.connectSession()
        advanceUntilIdle()

        // Wait enough virtual time for all 3 bounded exponential retries
        advanceTimeBy(30_000L)
        advanceUntilIdle()

        // 1 initial attempt + max 3 bounded reconnect attempts = 4 total max (never infinite)
        assertTrue(
            "Expected bounded reconnects <= 4, got ${fakeWs.connectCalls.get()}",
            fakeWs.connectCalls.get() <= 4
        )
        assertTrue(manager.observeAssistantState().value.isFallbackMode)
    }

    @Test
    fun `auth failure does not retry infinitely and exposes non-retryable error`() = runTest(testDispatcher) {
        fakeWs.nextConnectErrorEvent = VoiceAssistantError(
            category = VoiceErrorCategory.AUTH_FAILURE,
            userMessage = "Authentication failed for Gemini Live.",
            canRetry = false,
            fallbackAvailable = true
        )
        fakeWs.nextConnectFailure = IllegalStateException("401 unauthorized")

        manager.openAssistant()
        manager.connectSession()
        advanceTimeBy(20_000L)
        advanceUntilIdle()

        assertEquals(1, fakeWs.connectCalls.get())
        assertEquals(VoiceErrorCategory.AUTH_FAILURE, manager.observeAssistantState().value.activeError?.category)
        assertTrue(manager.observeAssistantState().value.isFallbackMode)
    }

    @Test
    fun `quota exceeded never retries infinitely and switches transparently to local fallback`() = runTest(testDispatcher) {
        manager.openAssistant()
        manager.connectSession()
        advanceUntilIdle()
        assertEquals(1, fakeWs.connectCalls.get())

        fakeWs.emitServerEvent(
            VoiceServerEvent.Error(
                VoiceAssistantError(
                    category = VoiceErrorCategory.QUOTA_EXHAUSTED,
                    userMessage = "Live AI free-tier quota is temporarily reached.",
                    canRetry = false,
                    fallbackAvailable = true
                )
            )
        )
        advanceTimeBy(20_000L)
        advanceUntilIdle()

        // No reconnect loop triggered on quota exhaustion
        assertEquals(1, fakeWs.connectCalls.get())
        assertTrue(manager.observeAssistantState().value.isFallbackMode)

        // Subsequent user request uses clean local fallback without internal label prefix
        manager.sendTextMessage("Find vegetarian food under 200")
        advanceUntilIdle()

        val lastMessage = manager.observeConversation().value.messages.last()
        assertEquals(VoiceMessageRole.FALLBACK_LOCAL, lastMessage.role)
        assertTrue(lastMessage.isFallback)
        assertTrue(lastMessage.text.isNotBlank())
        assertFalse(lastMessage.text.contains("Local FoodFlow Assistant"))
    }

    @Test
    fun `malformed server error is surfaced safely without crashing session`() = runTest(testDispatcher) {
        manager.openAssistant()
        manager.connectSession()
        advanceUntilIdle()

        fakeWs.emitServerEvent(
            VoiceServerEvent.Error(
                VoiceAssistantError(
                    category = VoiceErrorCategory.MALFORMED_MESSAGE,
                    userMessage = "Received an unexpected response format.",
                    canRetry = false,
                    fallbackAvailable = true
                )
            )
        )
        advanceUntilIdle()

        assertEquals(VoiceErrorCategory.MALFORMED_MESSAGE, manager.observeAssistantState().value.activeError?.category)
    }

    @Test
    fun `session GoingAway triggers graceful session renewal using resumption handle`() = runTest(testDispatcher) {
        manager.openAssistant()
        manager.connectSession()
        advanceUntilIdle()
        fakeWs.emitServerEvent(VoiceServerEvent.SessionResumptionUpdated("handle_v2", resumable = true))
        advanceUntilIdle()

        fakeWs.emitServerEvent(VoiceServerEvent.GoingAway(timeLeftSeconds = 10L))
        advanceTimeBy(5_000L)
        advanceUntilIdle()

        assertTrue(fakeWs.connectCalls.get() >= 2)
        assertEquals("handle_v2", fakeWs.lastResumptionHandle)
    }

    @Test
    fun `explicit close prevents automatic reconnect even if disconnect event arrives`() = runTest(testDispatcher) {
        manager.openAssistant()
        manager.connectSession()
        advanceUntilIdle()
        assertEquals(1, fakeWs.connectCalls.get())

        manager.closeAssistant()
        advanceUntilIdle()

        // Simulate late socket disconnect event arriving after explicit close
        fakeWs.emitServerEvent(VoiceServerEvent.Disconnected(reason = "Late socket EOF", wasGraceful = false))
        advanceTimeBy(20_000L)
        advanceUntilIdle()

        assertEquals(1, fakeWs.connectCalls.get())
        assertEquals(VoiceConnectionState.CLOSED, manager.observeAssistantState().value.connectionState)
        assertFalse(manager.observeAssistantState().value.isOpen)
    }

    // -------------------------------------------------------------------------
    // AUDIO RECORDING, PLAYBACK, FOCUS & NOISE TESTS
    // -------------------------------------------------------------------------

    @Test
    fun `microphone permission denied and permanently denied set clear error state without recording`() = runTest(testDispatcher) {
        fakeRecorder.permissionGranted = false
        manager.openAssistant()
        advanceUntilIdle()

        manager.startListening()
        advanceUntilIdle()

        assertFalse(fakeRecorder.isRecording.value)
        assertEquals(VoiceRecordingState.PERMISSION_REQUIRED, manager.observeAssistantState().value.recordingState)
        assertEquals(VoiceErrorCategory.PERMISSION_DENIED, manager.observeAssistantState().value.activeError?.category)

        manager.onMicrophonePermissionResult(granted = false, permanentlyDenied = true)
        advanceUntilIdle()
        assertTrue(manager.observeAssistantState().value.isPermissionPermanentlyDenied)
        assertEquals(
            VoiceErrorCategory.PERMISSION_PERMANENTLY_DENIED,
            manager.observeAssistantState().value.activeError?.category
        )
    }

    @Test
    fun `recording streams ordered audio chunks sequentially to WebSocket`() = runTest(testDispatcher) {
        manager.openAssistant()
        advanceUntilIdle()

        manager.startListening()
        advanceUntilIdle()
        assertEquals(VoiceRecordingState.LISTENING, manager.observeAssistantState().value.recordingState)

        fakeRecorder.emitChunk(seq = 1L, rms = 0.12f)
        fakeRecorder.emitChunk(seq = 2L, rms = 0.14f)
        fakeRecorder.emitChunk(seq = 3L, rms = 0.15f)
        advanceUntilIdle()

        val sentSeqs = fakeWs.sentAudioChunks.map { it.sequenceNumber }
        assertEquals(listOf(1L, 2L, 3L), sentSeqs)
    }

    @Test
    fun `audio focus loss and becoming noisy immediately stop playback and recording`() = runTest(testDispatcher) {
        manager.openAssistant()
        advanceUntilIdle()
        manager.startListening()
        advanceUntilIdle()
        fakeWs.emitServerEvent(VoiceServerEvent.AudioOutput(ByteArray(960) { 2 }, 24_000))
        advanceUntilIdle()

        // Simulate wired/Bluetooth headset unplug (BECOMING_NOISY)
        fakeFocus.emitFocusEvent(VoiceAudioFocusEvent.BECOMING_NOISY)
        advanceUntilIdle()

        assertFalse(fakeRecorder.isRecording.value)
        assertTrue(fakePlayback.stopAndFlushCalls.get() >= 1)
        assertEquals(VoiceAssistantPhase.IDLE, manager.observeAssistantState().value.phase)
    }

    // -------------------------------------------------------------------------
    // INTERRUPTION (BARGE-IN) TESTS
    // -------------------------------------------------------------------------

    @Test
    fun `manual interruption while AI is speaking immediately flushes playback and discards stale turn chunks`() = runTest(testDispatcher) {
        manager.openAssistant()
        manager.startListening()
        advanceUntilIdle()

        // AI begins speaking turn 1 in VOICE mode
        fakeWs.emitServerEvent(VoiceServerEvent.OutputTranscript("Here are some delicious options", isFinal = false))
        fakeWs.emitServerEvent(VoiceServerEvent.AudioOutput(ByteArray(1200) { 7 }, 24_000))
        advanceUntilIdle()

        assertEquals(VoicePlaybackState.SPEAKING, manager.observeAssistantState().value.playbackState)
        val turnBeforeInterrupt = manager.observeAssistantState().value.activeTurnId
        assertEquals(1, fakePlayback.enqueuedChunks.size)

        // User manually interrupts AI response
        manager.interruptAssistant("User tapped stop")
        advanceUntilIdle()

        val turnAfterInterrupt = manager.observeAssistantState().value.activeTurnId
        assertTrue("Turn ID must advance on barge-in", turnAfterInterrupt > turnBeforeInterrupt)
        assertTrue(fakePlayback.stopAndFlushCalls.get() >= 1)
        assertEquals(VoicePlaybackState.INTERRUPTED, manager.observeAssistantState().value.playbackState)

        // Stale chunk tagged with old turnId MUST be rejected by playback engine
        val staleAccepted = fakePlayback.enqueueChunk(
            VoiceAudioChunk(
                sequenceNumber = 99L,
                timestampMs = System.currentTimeMillis(),
                turnId = turnBeforeInterrupt,
                pcmBytes = ByteArray(640) { 1 },
                sampleRateHz = 24_000
            )
        )
        assertFalse("Stale turn audio must never mix with new turn", staleAccepted)

        // New turn audio plays cleanly in VOICE mode
        fakeWs.emitServerEvent(VoiceServerEvent.AudioOutput(ByteArray(960) { 9 }, 24_000))
        advanceUntilIdle()
        assertEquals(turnAfterInterrupt, fakePlayback.enqueuedChunks.last().turnId)
    }

    @Test
    fun `server-triggered interruption stops playback immediately and marks message interrupted`() = runTest(testDispatcher) {
        manager.openAssistant()
        manager.startListening()
        advanceUntilIdle()

        fakeWs.emitServerEvent(VoiceServerEvent.OutputTranscript("Starting long menu explanation...", isFinal = false))
        fakeWs.emitServerEvent(VoiceServerEvent.AudioOutput(ByteArray(960) { 5 }, 24_000))
        advanceUntilIdle()

        fakeWs.emitServerEvent(VoiceServerEvent.InterruptedByServer)
        advanceUntilIdle()

        assertTrue(fakePlayback.stopAndFlushCalls.get() >= 1)
        assertEquals(VoiceAssistantPhase.LISTENING, manager.observeAssistantState().value.phase)
        assertTrue(manager.observeConversation().value.messages.any { it.isInterrupted })
    }

    @Test
    fun `client VAD speech frames while AI is speaking trigger automatic barge-in`() = runTest(testDispatcher) {
        manager.openAssistant()
        advanceUntilIdle()
        manager.startListening()
        advanceUntilIdle()

        // AI starts speaking
        fakeWs.emitServerEvent(VoiceServerEvent.AudioOutput(ByteArray(960) { 4 }, 24_000))
        advanceUntilIdle()
        assertEquals(VoicePlaybackState.SPEAKING, manager.observeAssistantState().value.playbackState)

        val flushesBefore = fakePlayback.stopAndFlushCalls.get()
        // 4 consecutive loud user speech frames exceed BARGE_IN_RMS_THRESHOLD (0.28f)
        repeat(4) { idx ->
            fakeRecorder.emitChunk(seq = (idx + 1).toLong(), rms = 0.52f)
        }
        advanceUntilIdle()

        assertTrue(
            "Consecutive user speech frames must trigger barge-in flush",
            fakePlayback.stopAndFlushCalls.get() > flushesBefore
        )
    }

    @Test
    fun `rapid repeated interruptions stay stable and preserve conversation history`() = runTest(testDispatcher) {
        manager.openAssistant()
        advanceUntilIdle()
        manager.sendTextMessage("Find biryani")
        manager.startListening()
        advanceUntilIdle()

        repeat(6) { idx ->
            fakeWs.emitServerEvent(VoiceServerEvent.AudioOutput(ByteArray(480) { idx.toByte() }, 24_000))
            advanceUntilIdle()
            manager.interruptAssistant("Rapid barge-in #$idx")
            advanceUntilIdle()
        }

        assertTrue(fakePlayback.stopAndFlushCalls.get() >= 6)
        assertTrue(manager.observeConversation().value.messages.any { it.text.contains("Find biryani") })
    }

    // -------------------------------------------------------------------------
    // LIFECYCLE TESTS
    // -------------------------------------------------------------------------

    @Test
    fun `app backgrounding during listening and speaking closes session and releases resources while foreground restores silently`() = runTest(testDispatcher) {
        manager.openAssistant()
        advanceUntilIdle()
        manager.startListening()
        advanceUntilIdle()
        fakeWs.emitServerEvent(VoiceServerEvent.AudioOutput(ByteArray(960) { 3 }, 24_000))
        advanceUntilIdle()

        assertTrue(fakeWs.isConnected)
        assertTrue(fakeRecorder.isRecording.value)
        assertTrue(fakeFocus.hasFocus.value)

        // App moves to background: entire voice session (WebSocket, mic, playback, focus/receivers) MUST close
        manager.onAppBackgrounded()
        advanceUntilIdle()

        assertFalse("WebSocket MUST close on backgrounding", fakeWs.isConnected)
        assertTrue("WebSocket close must be called", fakeWs.closeCalls.get() >= 1)
        assertEquals(true, fakeWs.lastExplicitClose)
        assertFalse("Mic MUST stop immediately on backgrounding", fakeRecorder.isRecording.value)
        assertTrue("Recorder MUST release on backgrounding", fakeRecorder.releaseCalls.get() >= 1)
        assertFalse("Audio focus MUST be abandoned on backgrounding", fakeFocus.hasFocus.value)
        assertTrue("Audio focus & receivers MUST release on backgrounding", fakeFocus.releaseCalls.get() >= 1)
        assertTrue(fakePlayback.stopAndFlushCalls.get() >= 1)
        assertTrue("Audio playback MUST release on backgrounding", fakePlayback.releaseCalls.get() >= 1)
        assertEquals(VoiceConnectionState.CLOSED, manager.observeAssistantState().value.connectionState)
        assertEquals(VoiceAssistantPhase.CLOSED, manager.observeAssistantState().value.phase)
        assertEquals(AssistantInteractionMode.TEXT, manager.observeAssistantState().value.interactionMode)

        // Return to foreground: sheet, detent & history restored silently; WebSocket & mic do NOT auto-start
        manager.onAppForegrounded()
        advanceUntilIdle()

        assertTrue(manager.observeAssistantState().value.isOpen)
        assertEquals(VoiceSheetDetent.HALF_EXPANDED, manager.observeAssistantState().value.sheetDetent)
        assertEquals(VoiceAssistantPhase.IDLE, manager.observeAssistantState().value.phase)
        assertEquals(VoiceConnectionState.CLOSED, manager.observeAssistantState().value.connectionState)
        assertFalse("WebSocket MUST NOT auto-reconnect on foreground return", fakeWs.isConnected)
        assertFalse("Mic MUST NOT auto-restart on foreground return", fakeRecorder.isRecording.value)
        assertEquals(VoiceRecordingState.IDLE, manager.observeAssistantState().value.recordingState)

        // Explicitly tapping Speak after returning to foreground cleanly starts a new voice session
        manager.startListening()
        advanceUntilIdle()
        assertTrue("User tapping Speak after foregrounding must reconnect cleanly", fakeWs.isConnected)
        assertTrue("User tapping Speak after foregrounding must start recording", fakeRecorder.isRecording.value)
        assertTrue("User tapping Speak after foregrounding must re-acquire audio focus", fakeFocus.hasFocus.value)
    }

    @Test
    fun `permission revoked mid-session stops recording cleanly without crashing`() = runTest(testDispatcher) {
        manager.openAssistant()
        advanceUntilIdle()
        manager.startListening()
        advanceUntilIdle()

        fakeRecorder.failStreamWith(SecurityException("RECORD_AUDIO revoked by system"))
        advanceUntilIdle()

        assertFalse(fakeRecorder.isRecording.value)
        assertEquals(VoiceRecordingState.PERMISSION_REQUIRED, manager.observeAssistantState().value.recordingState)
        assertEquals(VoiceErrorCategory.PERMISSION_REVOKED, manager.observeAssistantState().value.activeError?.category)
    }

    @Test
    fun `closing assistant during active recording releases audio focus receivers and keeps history intact`() = runTest(testDispatcher) {
        manager.openAssistant()
        advanceUntilIdle()
        manager.sendTextMessage("Suggest a meal for two")
        manager.startListening()
        advanceUntilIdle()

        manager.closeAssistant()
        advanceUntilIdle()

        assertFalse(fakeWs.isConnected)
        assertTrue(fakeWs.closeCalls.get() >= 1)
        assertFalse(fakeRecorder.isRecording.value)
        assertTrue(fakeRecorder.releaseCalls.get() >= 1)
        assertFalse(fakeFocus.hasFocus.value)
        assertTrue(fakeFocus.releaseCalls.get() >= 1)
        assertTrue(fakePlayback.releaseCalls.get() >= 1)
        assertFalse(manager.observeAssistantState().value.isOpen)
        assertEquals(VoiceSheetDetent.HIDDEN, manager.observeAssistantState().value.sheetDetent)
        assertEquals(VoiceConnectionState.CLOSED, manager.observeAssistantState().value.connectionState)
        assertTrue(manager.observeConversation().value.messages.any { it.text.contains("Suggest a meal for two") })
    }

    @Test
    fun `releaseResources closes active session releases all resources and keeps singleton reusable`() = runTest(testDispatcher) {
        manager.openAssistant()
        manager.startListening()
        advanceUntilIdle()
        fakeWs.emitServerEvent(VoiceServerEvent.AudioOutput(ByteArray(960) { 5 }, 24_000))
        advanceUntilIdle()

        assertTrue(fakeWs.isConnected)
        assertTrue(fakeRecorder.isRecording.value)
        assertTrue(fakeFocus.hasFocus.value)

        // Simulate VoiceAssistantViewModel.onCleared() invoking synchronous releaseResources()
        manager.releaseResources()
        advanceUntilIdle()

        val stateAfterRelease = manager.observeAssistantState().value
        assertFalse(stateAfterRelease.isOpen)
        assertEquals(VoiceSheetDetent.HIDDEN, stateAfterRelease.sheetDetent)
        assertEquals(VoiceConnectionState.CLOSED, stateAfterRelease.connectionState)
        assertEquals(VoiceAssistantPhase.CLOSED, stateAfterRelease.phase)
        assertFalse(fakeWs.isConnected)
        assertTrue(fakeWs.closeCalls.get() >= 1)
        assertFalse(fakeRecorder.isRecording.value)
        assertTrue(fakeRecorder.releaseCalls.get() >= 1)
        assertFalse(fakeFocus.hasFocus.value)
        assertTrue(fakeFocus.releaseCalls.get() >= 1)
        assertTrue(fakePlayback.releaseCalls.get() >= 1)

        // Recreating Activity in the same process must still be able to open and use voice cleanly
        val chunksBeforeReopen = fakePlayback.enqueuedChunks.size
        manager.openAssistant()
        manager.startListening()
        advanceUntilIdle()
        fakeWs.emitServerEvent(VoiceServerEvent.AudioOutput(ByteArray(960) { 8 }, 24_000))
        advanceUntilIdle()

        assertTrue(fakeWs.isConnected)
        assertTrue(fakeRecorder.isRecording.value)
        assertEquals(chunksBeforeReopen + 1, fakePlayback.enqueuedChunks.size)
    }

    @Test
    fun `backgrounding mid-connect aborts session and prevents zombie socket or mic`() = runTest(testDispatcher) {
        fakeWs.connectDelayMs = 2_000L
        manager.openAssistant()

        val listenJob = async { manager.startListening() }
        advanceTimeBy(500L)

        // User backgrounds the app while WebSocket handshake is still in flight
        manager.onAppBackgrounded()
        advanceTimeBy(3_000L)
        listenJob.await()
        advanceUntilIdle()

        assertFalse("Zombie WebSocket must not stay connected after mid-connect backgrounding", fakeWs.isConnected)
        assertFalse("Mic must not start after mid-connect backgrounding", fakeRecorder.isRecording.value)
        assertFalse("Audio focus must not stay held after mid-connect backgrounding", fakeFocus.hasFocus.value)
        assertEquals(VoiceConnectionState.CLOSED, manager.observeAssistantState().value.connectionState)
    }

    @Test
    fun `going offline completely closes active voice session and releases all resources`() = runTest(testDispatcher) {
        manager.openAssistant()
        manager.startListening()
        advanceUntilIdle()
        fakeWs.emitServerEvent(VoiceServerEvent.AudioOutput(ByteArray(960) { 6 }, 24_000))
        advanceUntilIdle()

        assertTrue(fakeWs.isConnected)
        assertTrue(fakeRecorder.isRecording.value)
        assertTrue(fakeFocus.hasFocus.value)

        // App loses internet connectivity
        connectivity.setSimulatedOffline(true)
        advanceUntilIdle()

        val offlineState = manager.observeAssistantState().value
        assertFalse("WebSocket MUST close when app goes offline", fakeWs.isConnected)
        assertTrue("WebSocket close must be called on offline", fakeWs.closeCalls.get() >= 1)
        assertEquals(true, fakeWs.lastExplicitClose)
        assertFalse("Mic MUST stop when app goes offline", fakeRecorder.isRecording.value)
        assertTrue("Recorder MUST release when app goes offline", fakeRecorder.releaseCalls.get() >= 1)
        assertFalse("Audio focus MUST release when app goes offline", fakeFocus.hasFocus.value)
        assertTrue("Audio focus receivers MUST release when app goes offline", fakeFocus.releaseCalls.get() >= 1)
        assertTrue("Audio playback MUST release when app goes offline", fakePlayback.releaseCalls.get() >= 1)
        assertEquals(VoiceConnectionState.CLOSED, offlineState.connectionState)
        assertEquals(VoiceAssistantPhase.CLOSED, offlineState.phase)
        assertEquals(AssistantInteractionMode.TEXT, offlineState.interactionMode)
        assertEquals(VoiceErrorCategory.NO_INTERNET, offlineState.activeError?.category)

        // Returning online must NOT auto-reconnect WebSocket or restart mic, but clears offline error
        connectivity.setSimulatedOffline(false)
        advanceUntilIdle()

        val onlineState = manager.observeAssistantState().value
        assertFalse("WebSocket must NOT auto-reconnect when back online", fakeWs.isConnected)
        assertFalse("Mic must NOT auto-start when back online", fakeRecorder.isRecording.value)
        assertEquals(VoiceAssistantPhase.IDLE, onlineState.phase)
        assertEquals(null, onlineState.activeError)

        // Explicitly tapping Speak after reconnecting starts a clean new voice session
        manager.startListening()
        advanceUntilIdle()
        assertTrue(fakeWs.isConnected)
        assertTrue(fakeRecorder.isRecording.value)
    }

    @Test
    fun `hiding bottom sheet via drag-down or Back keeps open voice session alive`() = runTest(testDispatcher) {
        manager.openAssistant()
        manager.startListening()
        advanceUntilIdle()

        assertTrue(fakeWs.isConnected)
        val connectsBeforeHide = fakeWs.connectCalls.get()

        // Dragging down past HIDDEN or pressing Back from COLLAPSED dispatches hideAssistant()
        manager.hideAssistant()
        advanceUntilIdle()

        val hiddenState = manager.observeAssistantState().value
        assertFalse(hiddenState.isOpen)
        assertTrue(hiddenState.isMinimized)
        assertEquals(VoiceSheetDetent.HIDDEN, hiddenState.sheetDetent)
        assertTrue("Open voice session MUST stay connected when sheet is hidden via drag/Back", fakeWs.isConnected)
        assertEquals(0, fakeWs.closeCalls.get())

        // Reopening sheet preserves existing session without creating a duplicate WebSocket
        manager.openAssistant()
        advanceUntilIdle()
        assertTrue(manager.observeAssistantState().value.isOpen)
        assertTrue(fakeWs.isConnected)
        assertEquals(connectsBeforeHide, fakeWs.connectCalls.get())
    }

    // -------------------------------------------------------------------------
    // REFACTORED STOP, AUDIO GAIN, PERSISTENCE & DEDUPLICATION TESTS
    // -------------------------------------------------------------------------

    @Test
    fun `PcmSpeechGainProcessor amplifies quiet speech cleanly and soft-limits peaks without clipping`() {
        val processor = PcmSpeechGainProcessor(initialGainMultiplier = 2.25f)

        // Quiet speech sine-like samples around +/-4000 (~-18 dBFS)
        val quietPcm = ByteArray(400)
        for (i in 0 until 200) {
            val sample = (4000 * kotlin.math.sin(i * 0.25)).toInt().toShort()
            quietPcm[i * 2] = (sample.toInt() and 0xFF).toByte()
            quietPcm[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        val boosted = processor.processPcm16Le(quietPcm)

        fun rms(bytes: ByteArray): Double {
            var sumSq = 0.0
            val count = bytes.size / 2
            for (i in 0 until count) {
                val lo = bytes[i * 2].toInt() and 0xFF
                val hi = bytes[i * 2 + 1].toInt()
                val s = ((hi shl 8) or lo).toShort().toDouble()
                sumSq += s * s
            }
            return kotlin.math.sqrt(sumSq / count)
        }

        val inputRms = rms(quietPcm)
        val outputRms = rms(boosted)
        assertTrue("Expected boosted speech RMS ($outputRms) > 1.8x input RMS ($inputRms)", outputRms > inputRms * 1.8)

        // Extreme peak input (+32000 and -32000) MUST NOT wrap around or clip harshly
        val loudPcm = byteArrayOf(
            (32000 and 0xFF).toByte(), ((32000 shr 8) and 0xFF).toByte(),
            ((-32000) and 0xFF).toByte(), (((-32000) shr 8) and 0xFF).toByte()
        )
        val limited = processor.processPcm16Le(loudPcm)
        val posOut = (((limited[1].toInt()) shl 8) or (limited[0].toInt() and 0xFF)).toShort()
        val negOut = (((limited[3].toInt()) shl 8) or (limited[2].toInt() and 0xFF)).toShort()
        assertTrue("Positive peak must stay positive without wrap-around: $posOut", posOut in 20000..32767)
        assertTrue("Negative peak must stay negative without wrap-around: $negOut", negOut in -32768..-20000)
    }

    @Test
    fun `stopCurrentInteraction halts recording and playback immediately while preserving WebSocket and history`() =
        runTest(testDispatcher) {
            manager.openAssistant()
            advanceUntilIdle()
            manager.sendTextMessage("Show spicy dishes")
            manager.startListening()
            advanceUntilIdle()
            fakeWs.emitServerEvent(VoiceServerEvent.AudioOutput(ByteArray(960) { 6 }, 24_000))
            advanceUntilIdle()

            val turnBeforeStop = manager.observeAssistantState().value.activeTurnId
            assertTrue(fakeRecorder.isRecording.value)
            assertEquals(VoicePlaybackState.SPEAKING, manager.observeAssistantState().value.playbackState)

            manager.stopCurrentInteraction()
            advanceUntilIdle()

            val stateAfter = manager.observeAssistantState().value
            assertFalse("Mic must stop immediately", fakeRecorder.isRecording.value)
            assertFalse("Audio focus must be abandoned", fakeFocus.hasFocus.value)
            assertEquals(VoiceAssistantPhase.IDLE, stateAfter.phase)
            assertEquals("Ready when you are", stateAfter.statusMessage)
            assertEquals(VoiceRecordingState.IDLE, stateAfter.recordingState)
            assertEquals(VoicePlaybackState.IDLE, stateAfter.playbackState)
            assertTrue("Turn ID must advance on Stop", stateAfter.activeTurnId > turnBeforeStop)
            assertTrue("WebSocket session must remain connected for instant reuse", fakeWs.isConnected)
            assertTrue(
                "Conversation history must remain intact",
                manager.observeConversation().value.messages.any { it.text.contains("Show spicy dishes") }
            )

            // Late audio chunk from stopped turn MUST be rejected
            val acceptedStale = fakePlayback.enqueueChunk(
                VoiceAudioChunk(
                    sequenceNumber = 77L,
                    timestampMs = System.currentTimeMillis(),
                    turnId = turnBeforeStop,
                    pcmBytes = ByteArray(480) { 3 },
                    sampleRateHz = 24_000
                )
            )
            assertFalse("Stale audio after Stop must be discarded", acceptedStale)
        }

    @Test
    fun `sheetDetent persists across screen navigation without resetting conversation or creating duplicate sockets`() =
        runTest(testDispatcher) {
            manager.openAssistant()
            advanceUntilIdle()
            manager.setSheetDetent(VoiceSheetDetent.EXPANDED)
            manager.sendTextMessage("Top rated restaurants")
            advanceUntilIdle()

            // Simulate user tapping a restaurant card and navigating to RestaurantDetail
            manager.updateScreenContext("Restaurant Detail", "rest_pizza", "Napoli Oven")
            advanceUntilIdle()

            val snap = manager.observeAssistantState().value
            assertTrue(snap.isOpen)
            assertEquals(VoiceSheetDetent.EXPANDED, snap.sheetDetent)
            assertEquals(0, fakeWs.connectCalls.get())
            assertTrue(manager.observeConversation().value.messages.any { it.text.contains("Top rated restaurants") })
        }

    @Test
    fun `OutputTranscript does not create duplicate text message when structured cards exist for the same voice turn`() =
        runTest(testDispatcher) {
            val sampleRestaurant = Restaurant(
                id = "rest_1",
                name = "Spice Route Kitchen",
                cuisines = listOf("North Indian"),
                rating = 4.7,
                ratingCount = 240,
                deliveryTimeMin = 25,
                deliveryFee = 20.0,
                minOrderAmount = 99.0,
                offerText = "20% OFF",
                isPureVeg = false,
                isOpen = true,
                isPopular = true,
                isRecommended = true,
                isFavorite = false,
                imageKey = "indian",
                address = "SG Highway",
                distanceKm = 2.0,
                latitude = 23.02,
                longitude = 72.57
            )
            fakeActionExecutor.nextStructuredResult = VoiceActionResult(
                success = true,
                summaryForVoice = "Top restaurant: Spice Route Kitchen rated 4.7",
                structuredResponse = VoiceStructuredResponse(
                    type = VoiceResponseType.RESTAURANT_RECOMMENDATIONS,
                    headline = "Top restaurants for you",
                    restaurants = listOf(sampleRestaurant)
                )
            )

            manager.openAssistant()
            manager.startListening()
            advanceUntilIdle()

            fakeWs.emitServerEvent(
                VoiceServerEvent.ToolCallRequested(
                    listOf(VoiceToolCallInvocation("call_1", "search_restaurants", emptyMap()))
                )
            )
            advanceUntilIdle()

            // Gemini subsequently streams spoken transcript repeating restaurant names
            fakeWs.emitServerEvent(
                VoiceServerEvent.OutputTranscript("I found Spice Route Kitchen rated 4.7 stars!", isFinal = true)
            )
            fakeWs.emitServerEvent(VoiceServerEvent.TurnComplete)
            advanceUntilIdle()

            val messages = manager.observeConversation().value.messages
            val cardMessages = messages.filter { it.structuredResponse?.hasVisualCards == true }
            val duplicateAssistantBubbles = messages.filter {
                it.role == VoiceMessageRole.ASSISTANT && it.text.contains("Spice Route Kitchen")
            }
            assertEquals(1, cardMessages.size)
            assertTrue(
                "Duplicate text bubble repeating card details must be suppressed",
                duplicateAssistantBubbles.isEmpty()
            )
        }

    // -------------------------------------------------------------------------
    // TEXT vs VOICE INTERACTION MODE SEPARATION TESTS (ALL 7 SCENARIOS)
    // -------------------------------------------------------------------------

    @Test
    fun `opening chat without using microphone does not initialize voice session audio or mic`() =
        runTest(testDispatcher) {
            manager.openAssistant()
            advanceUntilIdle()

            val snap = manager.observeAssistantState().value
            assertTrue(snap.isOpen)
            assertEquals(AssistantInteractionMode.TEXT, snap.interactionMode)
            assertEquals(VoiceAssistantPhase.IDLE, snap.phase)
            assertEquals(0, fakeWs.connectCalls.get())
            assertEquals(0, fakePlayback.startSessionCalls.get())
            assertTrue(fakePlayback.enqueuedChunks.isEmpty())
            assertFalse(fakeRecorder.isRecording.value)
            assertFalse(fakeFocus.hasFocus.value)
        }

    @Test
    fun `typing a message and tapping Send produces text-only response with zero voice playback or audio focus`() =
        runTest(testDispatcher) {
            fakeTextChat.nextReply = "Here are 3 spicy vegetarian dinner options under ₹200."
            manager.openAssistant()
            advanceUntilIdle()

            manager.sendTextMessage("Suggest spicy veg dinner under 200")
            advanceUntilIdle()

            val snap = manager.observeAssistantState().value
            val messages = manager.observeConversation().value.messages
            assertEquals(AssistantInteractionMode.TEXT, snap.interactionMode)
            assertEquals(VoiceAssistantPhase.IDLE, snap.phase)
            assertEquals(VoicePlaybackState.IDLE, snap.playbackState)
            assertEquals(VoiceRecordingState.IDLE, snap.recordingState)
            assertEquals(0, fakeWs.connectCalls.get())
            assertTrue(fakeWs.sentTextTurns.isEmpty())
            assertEquals(0, fakePlayback.startSessionCalls.get())
            assertTrue(fakePlayback.enqueuedChunks.isEmpty())
            assertFalse(fakeFocus.hasFocus.value)
            assertFalse(fakeRecorder.isRecording.value)
            assertEquals(1, fakeTextChat.receivedPrompts.size)
            assertTrue(messages.any { it.role == VoiceMessageRole.USER && it.text == "Suggest spicy veg dinner under 200" })
            assertTrue(messages.any { it.role == VoiceMessageRole.ASSISTANT && it.text == fakeTextChat.nextReply })
        }

    @Test
    fun `sending multiple consecutive text messages produces text-only responses and preserves order`() =
        runTest(testDispatcher) {
            manager.openAssistant()
            advanceUntilIdle()

            fakeTextChat.nextReply = "Reply 1: Try Napoli Oven for Margherita Pizza."
            manager.sendTextMessage("Show me pizza places")
            advanceUntilIdle()

            fakeTextChat.nextReply = "Reply 2: Margherita Pizza is ₹199."
            manager.sendTextMessage("How much is Margherita?")
            advanceUntilIdle()

            fakeTextChat.nextReply = "Reply 3: Delivery takes about 25 minutes."
            manager.sendTextMessage("How fast is delivery?")
            advanceUntilIdle()

            val messages = manager.observeConversation().value.messages
            assertEquals(6, messages.size)
            assertEquals("Show me pizza places", messages[0].text)
            assertEquals("Reply 1: Try Napoli Oven for Margherita Pizza.", messages[1].text)
            assertEquals("How much is Margherita?", messages[2].text)
            assertEquals("Reply 2: Margherita Pizza is ₹199.", messages[3].text)
            assertEquals("How fast is delivery?", messages[4].text)
            assertEquals("Reply 3: Delivery takes about 25 minutes.", messages[5].text)

            // Zero voice session, zero audio playback, zero audio focus across all text turns
            assertEquals(0, fakeWs.connectCalls.get())
            assertEquals(0, fakePlayback.startSessionCalls.get())
            assertTrue(fakePlayback.enqueuedChunks.isEmpty())
            assertFalse(fakeFocus.hasFocus.value)
            assertFalse(fakeRecorder.isRecording.value)
        }

    @Test
    fun `explicit voice interaction enables audio playback with audio focus while text remains silent`() =
        runTest(testDispatcher) {
            manager.openAssistant()
            advanceUntilIdle()
            assertEquals(AssistantInteractionMode.TEXT, manager.observeAssistantState().value.interactionMode)

            // User explicitly taps Microphone / Speak button
            manager.startListening()
            advanceUntilIdle()

            assertEquals(AssistantInteractionMode.VOICE, manager.observeAssistantState().value.interactionMode)
            assertEquals(1, fakeWs.connectCalls.get())
            assertTrue(fakeRecorder.isRecording.value)
            assertTrue(fakeFocus.hasFocus.value)

            fakeWs.emitServerEvent(VoiceServerEvent.AudioOutput(ByteArray(960) { 4 }, 24_000))
            advanceUntilIdle()

            assertEquals(VoicePlaybackState.SPEAKING, manager.observeAssistantState().value.playbackState)
            assertEquals(1, fakePlayback.enqueuedChunks.size)
        }

    @Test
    fun `switching from voice to text stops audio playback abandons focus and drops delayed voice audio chunks`() =
        runTest(testDispatcher) {
            fakeTextChat.nextReply = "Paneer Butter Masala is ₹220 at Spice Route."
            manager.openAssistant()
            manager.startListening()
            advanceUntilIdle()

            // Voice AI is actively speaking
            fakeWs.emitServerEvent(VoiceServerEvent.OutputTranscript("Talking about restaurants...", isFinal = false))
            fakeWs.emitServerEvent(VoiceServerEvent.AudioOutput(ByteArray(960) { 7 }, 24_000))
            advanceUntilIdle()
            assertEquals(VoicePlaybackState.SPEAKING, manager.observeAssistantState().value.playbackState)
            assertTrue(fakeFocus.hasFocus.value)
            val chunksBeforeText = fakePlayback.enqueuedChunks.size
            val flushesBeforeText = fakePlayback.stopAndFlushCalls.get()

            // User types and sends a text message while voice response is actively playing
            manager.sendTextMessage("Price of Paneer Butter Masala?")
            advanceUntilIdle()

            val snapAfterText = manager.observeAssistantState().value
            assertEquals(AssistantInteractionMode.TEXT, snapAfterText.interactionMode)
            assertEquals(VoicePlaybackState.IDLE, snapAfterText.playbackState)
            assertEquals(VoiceRecordingState.IDLE, snapAfterText.recordingState)
            assertFalse("Mic must stop when switching to text", fakeRecorder.isRecording.value)
            assertFalse("Audio focus must be abandoned when switching to text", fakeFocus.hasFocus.value)
            assertTrue("Active audio must be flushed immediately", fakePlayback.stopAndFlushCalls.get() > flushesBeforeText)

            // Simulate delayed stale AudioOutput & OutputTranscript arriving late from the previous voice session
            fakeWs.emitServerEvent(VoiceServerEvent.AudioOutput(ByteArray(960) { 9 }, 24_000))
            fakeWs.emitServerEvent(VoiceServerEvent.OutputTranscript("Delayed voice tail that must not appear", isFinal = true))
            advanceUntilIdle()

            assertEquals(
                "Delayed AudioOutput from previous voice turn MUST NOT play in TEXT mode",
                chunksBeforeText,
                fakePlayback.enqueuedChunks.size
            )
            assertFalse("Delayed audio must NOT re-acquire audio focus", fakeFocus.hasFocus.value)
            assertEquals(VoicePlaybackState.IDLE, manager.observeAssistantState().value.playbackState)
            assertTrue(
                "Text reply must be present in conversation",
                manager.observeConversation().value.messages.any {
                    it.role == VoiceMessageRole.ASSISTANT && it.text == fakeTextChat.nextReply
                }
            )
            assertFalse(
                "Stale voice transcript must not pollute TEXT mode conversation",
                manager.observeConversation().value.messages.any {
                    it.text.contains("Delayed voice tail")
                }
            )
        }

    @Test
    fun `closing and reopening assistant preserves unified text and voice history silently in TEXT mode`() =
        runTest(testDispatcher) {
            fakeTextChat.nextReply = "Here are popular dosas nearby."
            manager.openAssistant()
            manager.sendTextMessage("Find masala dosa")
            advanceUntilIdle()

            // Also add a voice turn
            manager.startListening()
            advanceUntilIdle()
            fakeWs.emitServerEvent(VoiceServerEvent.InputTranscript("Add the first one", isFinal = true))
            fakeWs.emitServerEvent(VoiceServerEvent.OutputTranscript("Added Masala Dosa to cart.", isFinal = true))
            fakeWs.emitServerEvent(VoiceServerEvent.TurnComplete)
            advanceUntilIdle()

            val wsConnectsBeforeClose = fakeWs.connectCalls.get()
            manager.closeAssistant()
            advanceUntilIdle()

            // Reopen chat without tapping microphone
            manager.openAssistant()
            advanceUntilIdle()

            val snap = manager.observeAssistantState().value
            val messages = manager.observeConversation().value.messages
            assertTrue(snap.isOpen)
            assertEquals(AssistantInteractionMode.TEXT, snap.interactionMode)
            assertEquals(VoiceAssistantPhase.IDLE, snap.phase)
            assertEquals("Reopening assistant must NOT auto-connect voice WebSocket", wsConnectsBeforeClose, fakeWs.connectCalls.get())
            assertTrue(messages.any { it.text.contains("Find masala dosa") })
            assertTrue(messages.any { it.text.contains("Here are popular dosas nearby.") })
            assertTrue(messages.any { it.text.contains("Added Masala Dosa to cart.") })
        }

    @Test
    fun `text chat invokes FoodFlow tools and renders structured cards without audio or duplicate bubbles`() =
        runTest(testDispatcher) {
            val sampleRestaurant = Restaurant(
                id = "rest_1",
                name = "Spice Route Kitchen",
                cuisines = listOf("North Indian"),
                rating = 4.7,
                ratingCount = 240,
                deliveryTimeMin = 25,
                deliveryFee = 20.0,
                minOrderAmount = 99.0,
                offerText = "20% OFF",
                isPureVeg = false,
                isOpen = true,
                isPopular = true,
                isRecommended = true,
                isFavorite = false,
                imageKey = "indian",
                address = "SG Highway",
                distanceKm = 2.0,
                latitude = 23.02,
                longitude = 72.57
            )
            fakeActionExecutor.nextStructuredResult = VoiceActionResult(
                success = true,
                summaryForVoice = "Top restaurant: Spice Route Kitchen rated 4.7",
                structuredResponse = VoiceStructuredResponse(
                    type = VoiceResponseType.RESTAURANT_RECOMMENDATIONS,
                    headline = "Top restaurants for you",
                    restaurants = listOf(sampleRestaurant)
                )
            )
            fakeTextChat.toolCallsToTrigger = listOf(
                VoiceToolCallInvocation("call_txt_1", "search_restaurants", emptyMap())
            )
            fakeTextChat.nextReply = "I found Spice Route Kitchen!"

            manager.openAssistant()
            manager.sendTextMessage("Find restaurants near me")
            advanceUntilIdle()

            val messages = manager.observeConversation().value.messages
            val cardMessages = messages.filter { it.structuredResponse?.hasVisualCards == true }
            assertEquals(1, cardMessages.size)
            assertEquals("", cardMessages.first().structuredResponse?.spokenResponse)
            assertEquals(0, fakeWs.connectCalls.get())
            assertEquals(0, fakePlayback.startSessionCalls.get())
            assertTrue(fakePlayback.enqueuedChunks.isEmpty())
            assertFalse(fakeFocus.hasFocus.value)
        }

    @Test
    fun `openAssistant pre-warms text chat client silently without opening WebSocket or audio`() =
        runTest(testDispatcher) {
            manager.openAssistant()
            advanceUntilIdle()

            assertEquals(1, fakeTextChat.warmUpCalls.get())
            assertEquals(0, fakeWs.connectCalls.get())
            assertEquals(0, fakePlayback.startSessionCalls.get())
            assertFalse(fakeFocus.hasFocus.value)
            assertEquals(AssistantInteractionMode.TEXT, manager.observeAssistantState().value.interactionMode)
        }

    @Test
    fun `streaming text chat updates assistant message progressively and finalizes cleanly`() =
        runTest(testDispatcher) {
            fakeTextChat.streamedChunks = listOf("Fresh ", "wood-fired ", "pizzas are ready!")
            fakeTextChat.nextReply = "Fresh wood-fired pizzas are ready!"

            manager.openAssistant()
            manager.sendTextMessage("Tell me about your pizzas")
            advanceUntilIdle()

            val assistantMsg = manager.observeConversation().value.messages
                .lastOrNull { it.role == VoiceMessageRole.ASSISTANT }
            assertNotNull(assistantMsg)
            assertEquals("Fresh wood-fired pizzas are ready!", assistantMsg?.text)
            assertFalse(assistantMsg?.isStreaming ?: true)
            assertEquals(VoiceAssistantPhase.IDLE, manager.observeAssistantState().value.phase)
            assertEquals(0, fakeWs.connectCalls.get())
            assertEquals(0, fakePlayback.startSessionCalls.get())
        }

    // -------------------------------------------------------------------------
    // CONTROLLABLE FAKES
    // -------------------------------------------------------------------------

    private class FakeVoiceWebSocketDataSource : VoiceWebSocketDataSource {
        override val activeModelName: String = "gemini-3.1-flash-live-preview"
        private var connectedFlag = false
        override val isConnected: Boolean get() = connectedFlag

        private val _events = MutableSharedFlow<VoiceServerEvent>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        override val serverEvents: SharedFlow<VoiceServerEvent> = _events.asSharedFlow()

        val connectCalls = AtomicInteger(0)
        val closeCalls = AtomicInteger(0)
        var lastExplicitClose: Boolean? = null
        var connectDelayMs: Long = 0L
        val sentAudioChunks = CopyOnWriteArrayList<VoiceAudioChunk>()
        val sentTextTurns = CopyOnWriteArrayList<String>()
        var lastResumptionHandle: String? = null
        var nextConnectFailure: Throwable? = null
        var nextConnectErrorEvent: VoiceAssistantError? = null

        override suspend fun connect(systemPrompt: String, resumptionHandle: String?): Result<Unit> {
            connectCalls.incrementAndGet()
            lastResumptionHandle = resumptionHandle
            if (connectDelayMs > 0L) {
                kotlinx.coroutines.delay(connectDelayMs)
            }
            nextConnectErrorEvent?.let {
                _events.tryEmit(VoiceServerEvent.Error(it))
            }
            val failure = nextConnectFailure
            if (failure != null) {
                return Result.failure(failure)
            }
            connectedFlag = true
            _events.tryEmit(VoiceServerEvent.Connected)
            _events.tryEmit(VoiceServerEvent.SetupComplete)
            return Result.success(Unit)
        }

        override suspend fun sendAudioChunk(chunk: VoiceAudioChunk): Result<Unit> {
            sentAudioChunks.add(chunk)
            return Result.success(Unit)
        }

        override suspend fun sendTextTurn(text: String, endOfTurn: Boolean): Result<Unit> {
            sentTextTurns.add(text)
            return Result.success(Unit)
        }

        override suspend fun sendActivityStart(): Result<Unit> = Result.success(Unit)
        override suspend fun sendActivityEnd(): Result<Unit> = Result.success(Unit)

        override suspend fun sendToolResponses(
            responses: List<Pair<VoiceToolCallInvocation, VoiceActionResult>>
        ): Result<Unit> = Result.success(Unit)

        override suspend fun close(isExplicitClose: Boolean) {
            closeCalls.incrementAndGet()
            lastExplicitClose = isExplicitClose
            connectedFlag = false
        }

        fun emitServerEvent(event: VoiceServerEvent) {
            _events.tryEmit(event)
        }
    }

    private class FakeVoiceAudioRecordingDataSource : VoiceAudioRecordingDataSource {
        var permissionGranted: Boolean = true
        private val _isRecording = MutableStateFlow(false)
        override val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
        private val _amp = MutableStateFlow(0f)
        override val inputAmplitude: StateFlow<Float> = _amp.asStateFlow()
        override val activeAudioEffectsSummary: StateFlow<String> = MutableStateFlow("AEC • NS • AGC")

        private var chunkChannel: Channel<VoiceAudioChunk>? = null
        private var turnIdProvider: () -> Long = { 1L }

        override fun hasRecordPermission(): Boolean = permissionGranted

        override fun startRecording(turnIdProvider: () -> Long): Result<Flow<VoiceAudioChunk>> {
            if (!permissionGranted) return Result.failure(SecurityException("Missing RECORD_AUDIO"))
            this.turnIdProvider = turnIdProvider
            val ch = Channel<VoiceAudioChunk>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            chunkChannel = ch
            _isRecording.value = true
            return Result.success(ch.receiveAsFlow())
        }

        fun emitChunk(seq: Long, rms: Float) {
            _amp.value = rms
            chunkChannel?.trySend(
                VoiceAudioChunk(
                    sequenceNumber = seq,
                    timestampMs = System.currentTimeMillis(),
                    turnId = turnIdProvider(),
                    pcmBytes = ByteArray(640) { 1 },
                    sampleRateHz = 16_000,
                    rmsAmplitude = rms
                )
            )
        }

        fun failStreamWith(cause: Throwable) {
            _isRecording.value = false
            chunkChannel?.close(cause)
        }

        val releaseCalls = AtomicInteger(0)

        override fun stopRecording() {
            _isRecording.value = false
            _amp.value = 0f
            chunkChannel?.close()
            chunkChannel = null
        }

        override fun release() {
            releaseCalls.incrementAndGet()
            stopRecording()
        }
    }

    private class FakeVoiceAudioPlaybackDataSource : VoiceAudioPlaybackDataSource {
        private val _isPlaying = MutableStateFlow(false)
        override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
        private val _amp = MutableStateFlow(0f)
        override val outputAmplitude: StateFlow<Float> = _amp.asStateFlow()

        @Volatile
        var activeTurnId: Long = 0L
        val enqueuedChunks = CopyOnWriteArrayList<VoiceAudioChunk>()
        val startSessionCalls = AtomicInteger(0)
        val stopAndFlushCalls = AtomicInteger(0)
        val releaseCalls = AtomicInteger(0)
        var mutedFlag: Boolean = false

        override fun startSession(turnId: Long): Result<Unit> {
            startSessionCalls.incrementAndGet()
            activeTurnId = turnId
            return Result.success(Unit)
        }

        override fun enqueueChunk(chunk: VoiceAudioChunk): Boolean {
            if (chunk.turnId != activeTurnId) return false
            enqueuedChunks.add(chunk)
            _isPlaying.value = true
            _amp.value = 0.4f
            return true
        }

        override fun stopImmediatelyAndFlush(newTurnId: Long) {
            activeTurnId = newTurnId
            stopAndFlushCalls.incrementAndGet()
            _isPlaying.value = false
            _amp.value = 0f
        }

        override fun setMuted(muted: Boolean) {
            mutedFlag = muted
        }

        var currentGainMultiplier: Float = 2.25f

        override fun setPlaybackGain(gainMultiplier: Float) {
            currentGainMultiplier = gainMultiplier
        }

        override fun release() {
            releaseCalls.incrementAndGet()
            _isPlaying.value = false
            _amp.value = 0f
        }
    }

    private class FakeVoiceAudioFocusCoordinator : VoiceAudioFocusCoordinator {
        private val _hasFocus = MutableStateFlow(false)
        override val hasFocus: StateFlow<Boolean> = _hasFocus.asStateFlow()
        override val activeRoute: StateFlow<VoiceAudioRoute> = MutableStateFlow(VoiceAudioRoute.SPEAKER)
        private val _events = MutableSharedFlow<VoiceAudioFocusEvent>(
            replay = 0,
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        override val focusEvents: SharedFlow<VoiceAudioFocusEvent> = _events.asSharedFlow()
        val releaseCalls = AtomicInteger(0)

        override fun requestAudioFocus(): Boolean {
            _hasFocus.value = true
            return true
        }

        override fun abandonAudioFocus() {
            _hasFocus.value = false
        }

        override fun release() {
            releaseCalls.incrementAndGet()
            abandonAudioFocus()
        }

        fun emitFocusEvent(event: VoiceAudioFocusEvent) {
            _events.tryEmit(event)
        }
    }

    private class FakeVoiceAssistantActionDataSource : VoiceAssistantActionDataSource {
        var nextStructuredResult: VoiceActionResult? = null

        override suspend fun execute(
            action: VoiceActionRequest,
            context: VoiceConversationContext
        ): VoiceActionResult = nextStructuredResult ?: VoiceActionResult(
            success = true,
            summaryForVoice = "Executed ${action.javaClass.simpleName}"
        )
    }

    private class FakeVoiceTextChatDataSource : VoiceTextChatDataSource {
        override val activeModelName: String = "gemini-3.5-flash-lite"
        val warmUpCalls = AtomicInteger(0)
        val receivedPrompts = CopyOnWriteArrayList<String>()
        var nextReply: String = "Here are the best options for you."
        var streamedChunks: List<String> = emptyList()
        var nextFailure: Throwable? = null
        var toolCallsToTrigger: List<VoiceToolCallInvocation> = emptyList()

        override fun warmUp() {
            warmUpCalls.incrementAndGet()
        }

        override suspend fun sendTextMessage(
            userText: String,
            turnId: Long,
            context: VoiceConversationContext,
            onToolCalls: suspend (List<VoiceToolCallInvocation>) -> List<Pair<VoiceToolCallInvocation, VoiceActionResult>>,
            onPartialText: (String) -> Unit
        ): Result<AssistantTextChatResponse> {
            receivedPrompts.add(userText)
            nextFailure?.let { return Result.failure(it) }
            val invoked = if (toolCallsToTrigger.isNotEmpty()) {
                onToolCalls(toolCallsToTrigger)
                true
            } else {
                if (streamedChunks.isNotEmpty()) {
                    val buf = StringBuilder()
                    streamedChunks.forEach { chunk ->
                        buf.append(chunk)
                        onPartialText(buf.toString().trim())
                    }
                }
                false
            }
            return Result.success(AssistantTextChatResponse(replyText = nextReply, invokedTools = invoked))
        }
    }

    private class FakeVoiceLocalFallbackEngine : VoiceLocalFallbackEngine {
        override suspend fun handleFallbackQuery(
            userQuery: String,
            context: VoiceConversationContext
        ): Pair<VoiceActionResult, String> {
            val result = VoiceActionResult(
                success = true,
                summaryForVoice = "Found 2 vegetarian options under ₹200."
            )
            return result to "Found 2 vegetarian options under ₹200."
        }
    }
}
