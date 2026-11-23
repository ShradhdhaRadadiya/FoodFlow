package com.foodflow.domain.model

/**
 * Pure Kotlin domain models for FoodFlow's Global Real-Time AI Voice Assistant.
 * Keeps connection, recording, playback, sheet detent, structured responses, and conversation
 * states cleanly separated to prevent impossible state combinations.
 */

enum class AssistantInteractionMode {
    TEXT,
    VOICE
}

enum class VoiceConnectionState(val label: String) {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting to FoodFlow AI..."),
    CONNECTED("Ready when you are"),
    RECONNECTING("Reconnecting..."),
    CLOSING("Closing session..."),
    CLOSED("Session closed"),
    ERROR("Connection error")
}

enum class VoiceRecordingState {
    IDLE,
    PERMISSION_REQUIRED,
    LISTENING,
    PAUSED,
    MUTED,
    ERROR
}

enum class VoicePlaybackState {
    IDLE,
    BUFFERING,
    SPEAKING,
    INTERRUPTED,
    PAUSED,
    ERROR
}

enum class VoiceSheetDetent {
    HIDDEN,
    COLLAPSED,
    HALF_EXPANDED,
    EXPANDED
}

enum class VoiceAssistantPhase(val userVisibleStatus: String) {
    IDLE("Ready when you are"),
    INITIALIZING("Connecting to FoodFlow AI..."),
    REQUESTING_PERMISSION("Microphone permission needed"),
    CONNECTING("Connecting to FoodFlow AI..."),
    CONNECTED("Ready when you are"),
    LISTENING("Listening to you..."),
    PROCESSING("Understanding your request..."),
    THINKING("Finding the best options for you..."),
    RESPONDING("Preparing your answer..."),
    SPEAKING("Speaking..."),
    INTERRUPTED("Response interrupted"),
    PAUSED("Ready when you are"),
    RECONNECTING("Reconnecting..."),
    ERROR("Something went wrong"),
    CLOSING("Closing session..."),
    CLOSED("Ready when you are")
}

enum class VoiceErrorCategory(val isRetryable: Boolean) {
    PERMISSION_DENIED(false),
    PERMISSION_PERMANENTLY_DENIED(false),
    PERMISSION_REVOKED(false),
    MICROPHONE_UNAVAILABLE(false),
    NO_INTERNET(true),
    CONNECTION_TIMEOUT(true),
    AUTH_FAILURE(false),
    INVALID_MODEL(false),
    UNSUPPORTED_AUDIO(false),
    RATE_LIMITED(true),
    QUOTA_EXHAUSTED(false),
    SESSION_EXPIRED(true),
    SERVER_ERROR(true),
    MALFORMED_MESSAGE(false),
    RECORDING_FAILED(true),
    PLAYBACK_FAILED(true),
    AUDIO_FOCUS_LOST(false),
    DEVICE_DISCONNECTED(false),
    SAFETY_BLOCKED(false),
    INVALID_TOOL_CALL(false),
    UNKNOWN(true)
}

data class VoiceAssistantError(
    val category: VoiceErrorCategory,
    val userMessage: String,
    val recoveryHint: String? = null,
    val canRetry: Boolean = category.isRetryable,
    val fallbackAvailable: Boolean = true
)

enum class VoiceMessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL_ACTION,
    FALLBACK_LOCAL
}

enum class VoiceResponseType {
    RESTAURANT_RECOMMENDATIONS,
    FOOD_RECOMMENDATIONS,
    CART_SUMMARY,
    NAVIGATION_CONFIRMATION,
    CONVERSATIONAL_TEXT,
    ERROR
}

data class VoiceCartSummaryItem(
    val itemId: String,
    val foodName: String,
    val quantity: Int,
    val lineTotal: Double,
    val isVeg: Boolean
)

data class VoiceCartSummarySnapshot(
    val restaurantId: String,
    val restaurantName: String,
    val itemCount: Int,
    val subtotal: Double,
    val totalPayable: Double,
    val items: List<VoiceCartSummaryItem> = emptyList()
)

data class VoiceStructuredResponse(
    val type: VoiceResponseType,
    val headline: String = "",
    val bodyText: String = "",
    val spokenResponse: String = "",
    val restaurants: List<Restaurant> = emptyList(),
    val foodItems: List<FoodItem> = emptyList(),
    val foodRestaurantNames: Map<String, String> = emptyMap(),
    val cartSummary: VoiceCartSummarySnapshot? = null,
    val navigationTarget: VoiceNavigationTarget? = null,
    val error: VoiceAssistantError? = null
) {
    val hasVisualCards: Boolean
        get() = restaurants.isNotEmpty() || foodItems.isNotEmpty() || cartSummary != null
}

data class VoiceRecommendationItem(
    val index: Int,
    val foodId: String,
    val foodName: String,
    val restaurantId: String,
    val restaurantName: String,
    val price: Double,
    val isVeg: Boolean,
    val spiceLevel: String,
    val rating: Double,
    val isAvailable: Boolean
)

data class VoiceConversationMessage(
    val id: String,
    val turnId: Long,
    val role: VoiceMessageRole,
    val text: String,
    val timestampMs: Long,
    val isStreaming: Boolean = false,
    val isInterrupted: Boolean = false,
    val isFallback: Boolean = false,
    val recommendations: List<VoiceRecommendationItem> = emptyList(),
    val structuredResponse: VoiceStructuredResponse? = null
)

sealed interface VoiceNavigationTarget {
    data object Home : VoiceNavigationTarget
    data object Search : VoiceNavigationTarget
    data object Cart : VoiceNavigationTarget
    data object Orders : VoiceNavigationTarget
    data object Profile : VoiceNavigationTarget
    data object Checkout : VoiceNavigationTarget
    data object ScheduledOrders : VoiceNavigationTarget
    data class RestaurantDetail(val restaurantId: String) : VoiceNavigationTarget
    data class OrderTracking(val orderId: String) : VoiceNavigationTarget
}

data class VoicePendingConfirmation(
    val confirmationId: String,
    val title: String,
    val message: String,
    val action: VoiceActionRequest
)

data class VoiceConversationContext(
    val conversationId: String,
    val sessionId: String,
    val resumptionHandle: String? = null,
    val messages: List<VoiceConversationMessage> = emptyList(),
    val olderContextSummary: String = "",
    val latestUserRequest: String = "",
    val latestAiResponse: String = "",
    val recentRecommendations: List<VoiceRecommendationItem> = emptyList(),
    val selectedRestaurantId: String? = null,
    val selectedRestaurantName: String? = null,
    val currentScreenName: String = "Home",
    val pendingConfirmation: VoicePendingConfirmation? = null,
    val activeTurnId: Long = 0L
) {
    companion object {
        const val MAX_HISTORY_MESSAGES = 40
    }
}

enum class VoiceAudioRoute(val label: String) {
    SPEAKER("Speaker"),
    EARPIECE("Earpiece"),
    WIRED_HEADSET("Wired Headset"),
    BLUETOOTH("Bluetooth")
}

data class VoiceAssistantSnapshot(
    val isOpen: Boolean = false,
    val isMinimized: Boolean = false,
    val sheetDetent: VoiceSheetDetent = VoiceSheetDetent.HIDDEN,
    val interactionMode: AssistantInteractionMode = AssistantInteractionMode.TEXT,
    val phase: VoiceAssistantPhase = VoiceAssistantPhase.IDLE,
    val connectionState: VoiceConnectionState = VoiceConnectionState.DISCONNECTED,
    val recordingState: VoiceRecordingState = VoiceRecordingState.IDLE,
    val playbackState: VoicePlaybackState = VoicePlaybackState.IDLE,
    val isMuted: Boolean = false,
    val isFallbackMode: Boolean = false,
    val hasMicPermission: Boolean = false,
    val isPermissionPermanentlyDenied: Boolean = false,
    val inputAmplitude: Float = 0f,
    val outputAmplitude: Float = 0f,
    val activeTurnId: Long = 0L,
    val statusMessage: String = VoiceAssistantPhase.IDLE.userVisibleStatus,
    val liveUserTranscript: String = "",
    val liveAssistantTranscript: String = "",
    val activeError: VoiceAssistantError? = null,
    val reconnectAttempt: Int = 0,
    val maxReconnectAttempts: Int = 3,
    val hasAudioFocus: Boolean = false,
    val activeAudioRoute: VoiceAudioRoute = VoiceAudioRoute.SPEAKER,
    val activeModelName: String = "gemini-3.1-flash-live-preview",
    val audioEffectsSummary: String = ""
) {
    val isSessionActive: Boolean
        get() = connectionState == VoiceConnectionState.CONNECTED ||
            connectionState == VoiceConnectionState.CONNECTING ||
            connectionState == VoiceConnectionState.RECONNECTING ||
            recordingState == VoiceRecordingState.LISTENING ||
            playbackState == VoicePlaybackState.SPEAKING
}

data class VoiceAudioChunk(
    val sequenceNumber: Long,
    val timestampMs: Long,
    val turnId: Long,
    val pcmBytes: ByteArray,
    val sampleRateHz: Int,
    val rmsAmplitude: Float = 0f
) {
    /** Compares audio chunks by metadata and PCM sample contents. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VoiceAudioChunk) return false
        return sequenceNumber == other.sequenceNumber &&
            timestampMs == other.timestampMs &&
            turnId == other.turnId &&
            sampleRateHz == other.sampleRateHz &&
            pcmBytes.contentEquals(other.pcmBytes)
    }

    /** Builds a hash from the chunk metadata and PCM sample contents. */
    override fun hashCode(): Int {
        var result = sequenceNumber.hashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + turnId.hashCode()
        result = 31 * result + pcmBytes.contentHashCode()
        result = 31 * result + sampleRateHz
        return result
    }
}

sealed interface VoiceActionRequest {
    data class SearchRestaurants(
        val query: String = "",
        val vegOnly: Boolean = false,
        val minRating: Double? = null
    ) : VoiceActionRequest

    data class SearchFoodItems(
        val query: String = "",
        val vegOnly: Boolean = false,
        val maxPrice: Double? = null,
        val spicyOnly: Boolean = false,
        val mealType: String? = null,
        val forPeopleCount: Int? = null
    ) : VoiceActionRequest

    data class GetRestaurantDetails(
        val restaurantId: String? = null,
        val restaurantName: String? = null
    ) : VoiceActionRequest

    data object GetCartSummary : VoiceActionRequest

    data class AddItemToCart(
        val foodId: String? = null,
        val foodName: String? = null,
        val recommendationIndex: Int? = null,
        val quantity: Int = 1,
        val replaceExistingCart: Boolean = false
    ) : VoiceActionRequest

    data class RemoveCartItem(
        val itemId: String? = null,
        val foodName: String? = null
    ) : VoiceActionRequest

    data class UpdateCartQuantity(
        val itemId: String? = null,
        val foodName: String? = null,
        val quantity: Int
    ) : VoiceActionRequest

    data class NavigateToScreen(
        val destination: String,
        val restaurantId: String? = null,
        val restaurantName: String? = null,
        val orderId: String? = null
    ) : VoiceActionRequest

    data class GetOrderStatus(
        val orderId: String? = null
    ) : VoiceActionRequest
}

data class VoiceActionResult(
    val success: Boolean,
    val summaryForVoice: String,
    val structuredPayload: Map<String, String> = emptyMap(),
    val recommendations: List<VoiceRecommendationItem> = emptyList(),
    val structuredResponse: VoiceStructuredResponse? = null,
    val navigationTarget: VoiceNavigationTarget? = null,
    val requiresConfirmation: VoicePendingConfirmation? = null,
    val selectedRestaurantId: String? = null,
    val selectedRestaurantName: String? = null
)

data class VoiceToolCallInvocation(
    val callId: String,
    val name: String,
    val args: Map<String, String>
)

sealed interface VoiceServerEvent {
    data object Connected : VoiceServerEvent
    data object SetupComplete : VoiceServerEvent
    data class InputTranscript(val text: String, val isFinal: Boolean = false) : VoiceServerEvent
    data class OutputTranscript(val text: String, val isFinal: Boolean = false) : VoiceServerEvent
    data class AudioOutput(val pcmBytes: ByteArray, val sampleRateHz: Int = 24_000) : VoiceServerEvent
    data class ToolCallRequested(val calls: List<VoiceToolCallInvocation>) : VoiceServerEvent
    data class ToolCallCancelled(val callIds: List<String>) : VoiceServerEvent
    data object InterruptedByServer : VoiceServerEvent
    data object TurnComplete : VoiceServerEvent
    data class SessionResumptionUpdated(val newHandle: String?, val resumable: Boolean) : VoiceServerEvent
    data class GoingAway(val timeLeftSeconds: Long) : VoiceServerEvent
    data class Disconnected(val reason: String, val wasGraceful: Boolean) : VoiceServerEvent
    data class Error(val error: VoiceAssistantError) : VoiceServerEvent
}
