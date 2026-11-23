package com.foodflow.feature.voice

import com.foodflow.core.mvi.MviEffect
import com.foodflow.core.mvi.MviIntent
import com.foodflow.core.mvi.MviState
import com.foodflow.domain.model.AssistantInteractionMode
import com.foodflow.domain.model.FoodItem
import com.foodflow.domain.model.VoiceActionRequest
import com.foodflow.domain.model.VoiceAssistantError
import com.foodflow.domain.model.VoiceAssistantPhase
import com.foodflow.domain.model.VoiceAudioRoute
import com.foodflow.domain.model.VoiceConnectionState
import com.foodflow.domain.model.VoiceConversationMessage
import com.foodflow.domain.model.VoiceNavigationTarget
import com.foodflow.domain.model.VoicePendingConfirmation
import com.foodflow.domain.model.VoicePlaybackState
import com.foodflow.domain.model.VoiceRecommendationItem
import com.foodflow.domain.model.VoiceRecordingState
import com.foodflow.domain.model.VoiceSheetDetent
import com.foodflow.domain.model.VoiceStructuredResponse

data class VoiceQuickPrompt(
    val id: String,
    val label: String,
    val promptText: String
)

data class VoiceAssistantUiState(
    val isSheetOpen: Boolean = false,
    val sheetDetent: VoiceSheetDetent = VoiceSheetDetent.HIDDEN,
    val interactionMode: AssistantInteractionMode = AssistantInteractionMode.TEXT,
    val phase: VoiceAssistantPhase = VoiceAssistantPhase.IDLE,
    val connectionState: VoiceConnectionState = VoiceConnectionState.DISCONNECTED,
    val recordingState: VoiceRecordingState = VoiceRecordingState.IDLE,
    val playbackState: VoicePlaybackState = VoicePlaybackState.IDLE,
    val isFallbackMode: Boolean = false,
    val hasMicPermission: Boolean = false,
    val isPermissionPermanentlyDenied: Boolean = false,
    val inputAmplitude: Float = 0f,
    val outputAmplitude: Float = 0f,
    val statusText: String = VoiceAssistantPhase.IDLE.userVisibleStatus,
    val liveUserTranscript: String = "",
    val liveAssistantTranscript: String = "",
    val messages: List<VoiceConversationMessage> = emptyList(),
    val recentRecommendations: List<VoiceRecommendationItem> = emptyList(),
    val cartQuantitiesByFoodId: Map<String, Int> = emptyMap(),
    val pendingConfirmation: VoicePendingConfirmation? = null,
    val activeError: VoiceAssistantError? = null,
    val textInputDraft: String = "",
    val currentScreenName: String = "Home",
    val activeAudioRoute: VoiceAudioRoute = VoiceAudioRoute.SPEAKER,
    val activeModelName: String = "",
    val quickPrompts: List<VoiceQuickPrompt> = listOf(
        VoiceQuickPrompt("near_me", "Find restaurants near me", "Find restaurants near me"),
        VoiceQuickPrompt("popular_pizzas", "Show popular pizzas", "Show popular pizzas"),
        VoiceQuickPrompt("under_200", "Find food under ₹200", "Find food under ₹200"),
        VoiceQuickPrompt("veg_meals", "Show vegetarian meals", "Show vegetarian meals")
    )
) : MviState {
    val isListening: Boolean
        get() = recordingState == VoiceRecordingState.LISTENING || phase == VoiceAssistantPhase.LISTENING

    val isSpeaking: Boolean
        get() = playbackState == VoicePlaybackState.SPEAKING || phase == VoiceAssistantPhase.SPEAKING

    val isProcessing: Boolean
        get() = phase == VoiceAssistantPhase.CONNECTING ||
            phase == VoiceAssistantPhase.INITIALIZING ||
            phase == VoiceAssistantPhase.PROCESSING ||
            phase == VoiceAssistantPhase.THINKING ||
            phase == VoiceAssistantPhase.RESPONDING ||
            phase == VoiceAssistantPhase.RECONNECTING

    val canStop: Boolean
        get() = isListening || isSpeaking || isProcessing

    val isMinimized: Boolean
        get() = sheetDetent == VoiceSheetDetent.COLLAPSED

    val latestStructuredCardResponse: VoiceStructuredResponse?
        get() = messages.lastOrNull { it.structuredResponse?.hasVisualCards == true }?.structuredResponse
}

sealed interface VoiceAssistantIntent : MviIntent {
    data object OpenAssistant : VoiceAssistantIntent
    data object HideAssistant : VoiceAssistantIntent
    data object CloseAssistant : VoiceAssistantIntent
    data class ChangeSheetDetent(val detent: VoiceSheetDetent) : VoiceAssistantIntent

    data object TapSpeak : VoiceAssistantIntent
    data object TapStop : VoiceAssistantIntent
    data object RetryConnection : VoiceAssistantIntent

    data class UpdateTextInput(val text: String) : VoiceAssistantIntent
    data object SubmitTextInput : VoiceAssistantIntent
    data class SelectQuickPrompt(val prompt: VoiceQuickPrompt) : VoiceAssistantIntent

    data class OpenRestaurant(val restaurantId: String) : VoiceAssistantIntent
    data class ToggleFavoriteRestaurant(val restaurantId: String) : VoiceAssistantIntent
    data class AddFoodToCart(val item: FoodItem) : VoiceAssistantIntent
    data class IncrementFoodInCart(val foodId: String) : VoiceAssistantIntent
    data class DecrementFoodInCart(val foodId: String) : VoiceAssistantIntent
    data object OpenCart : VoiceAssistantIntent

    data object ConfirmPendingAction : VoiceAssistantIntent
    data object DismissPendingAction : VoiceAssistantIntent

    data class PermissionResult(
        val granted: Boolean,
        val permanentlyDenied: Boolean = false
    ) : VoiceAssistantIntent

    data class ScreenContextChanged(
        val screenName: String,
        val restaurantId: String? = null,
        val restaurantName: String? = null
    ) : VoiceAssistantIntent

    data class ExecuteDirectAction(val action: VoiceActionRequest) : VoiceAssistantIntent
}

sealed interface VoiceAssistantEvent {
    data object AppBackgrounded : VoiceAssistantEvent
    data object AppForegrounded : VoiceAssistantEvent
}

sealed interface VoiceAssistantUiEffect : MviEffect {
    data object RequestMicrophonePermission : VoiceAssistantUiEffect
    data class Navigate(val target: VoiceNavigationTarget) : VoiceAssistantUiEffect
    data class ShowMessage(val message: String) : VoiceAssistantUiEffect
}
