package com.foodflow.feature.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foodflow.domain.model.CartItem
import com.foodflow.domain.model.VoiceActionRequest
import com.foodflow.domain.model.VoiceAssistantPhase
import com.foodflow.domain.model.VoiceAssistantSnapshot
import com.foodflow.domain.model.VoiceConversationContext
import com.foodflow.domain.model.VoiceNavigationTarget
import com.foodflow.domain.repository.VoiceAssistantController
import com.foodflow.domain.usecase.cart.ObserveCartItemsUseCase
import com.foodflow.domain.usecase.cart.RemoveCartItemUseCase
import com.foodflow.domain.usecase.cart.UpdateCartQuantityUseCase
import com.foodflow.domain.usecase.restaurant.ToggleFavoriteRestaurantUseCase
import com.foodflow.domain.usecase.voice.ConnectVoiceAssistantUseCase
import com.foodflow.domain.usecase.voice.DisconnectVoiceAssistantUseCase
import com.foodflow.domain.usecase.voice.ExecuteAssistantActionUseCase
import com.foodflow.domain.usecase.voice.ObserveConversationUseCase
import com.foodflow.domain.usecase.voice.ObserveVoiceAssistantStateUseCase
import com.foodflow.domain.usecase.voice.RestoreConversationUseCase
import com.foodflow.domain.usecase.voice.SendTextMessageUseCase
import com.foodflow.domain.usecase.voice.StartVoiceRecordingUseCase
import com.foodflow.domain.usecase.voice.StopAssistantUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Bridges Voice Assistant UI intents to domain use cases and the application-scoped session controller.
 *
 * State flow: controller state, conversation history, Room cart items, and text draft are combined
 * into [uiState]. UI actions enter through [onIntent]; audio/text work is delegated to use cases,
 * while one-shot navigation and permission requests leave through [effects]. This ViewModel is
 * hosted once at FoodFlowRoot so the conversation and inline cart quantities survive navigation.
 */
@HiltViewModel
class VoiceAssistantViewModel @Inject constructor(
    private val controller: VoiceAssistantController,
    private val connectVoiceAssistant: ConnectVoiceAssistantUseCase,
    private val disconnectVoiceAssistant: DisconnectVoiceAssistantUseCase,
    private val startVoiceRecording: StartVoiceRecordingUseCase,
    private val stopAssistant: StopAssistantUseCase,
    private val sendTextMessage: SendTextMessageUseCase,
    private val executeAssistantAction: ExecuteAssistantActionUseCase,
    private val restoreConversation: RestoreConversationUseCase,
    private val updateCartQuantity: UpdateCartQuantityUseCase,
    private val removeCartItem: RemoveCartItemUseCase,
    private val toggleFavoriteRestaurant: ToggleFavoriteRestaurantUseCase,
    observeAssistantState: ObserveVoiceAssistantStateUseCase,
    observeConversation: ObserveConversationUseCase,
    observeCartItems: ObserveCartItemsUseCase
) : ViewModel() {

    private val textDraft = MutableStateFlow("")
    private var latestCartItems: List<CartItem> = emptyList()

    private val _effects = MutableSharedFlow<VoiceAssistantUiEffect>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val effects: SharedFlow<VoiceAssistantUiEffect> = _effects.asSharedFlow()

    val uiState: StateFlow<VoiceAssistantUiState> = combine(
        observeAssistantState(),
        observeConversation(),
        observeCartItems(),
        textDraft
    ) { snapshot, conversation, cartItems, draft ->
        latestCartItems = cartItems
        mapToUiState(snapshot, conversation, cartItems, draft)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = VoiceAssistantUiState()
    )

    init {
        viewModelScope.launch {
            restoreConversation()
        }
        viewModelScope.launch {
            controller.observeNavigationRequests().collect { target ->
                _effects.tryEmit(VoiceAssistantUiEffect.Navigate(target))
            }
        }
    }

    /**
     * Routes UI actions into the voice flow: permission-gated Speak starts microphone capture,
     * text submission uses the text-chat use case, and Stop/Retry delegate to session use cases.
     * Cart actions use existing cart/domain operations; navigation and permission prompts are
     * emitted as one-shot effects for the host UI to perform.
     */
    fun onIntent(intent: VoiceAssistantIntent) {
        when (intent) {
            VoiceAssistantIntent.OpenAssistant -> {
                viewModelScope.launch { controller.openAssistant() }
            }

            VoiceAssistantIntent.HideAssistant -> {
                viewModelScope.launch { controller.hideAssistant() }
            }

            VoiceAssistantIntent.CloseAssistant -> {
                viewModelScope.launch { disconnectVoiceAssistant(isExplicitClose = true) }
            }

            is VoiceAssistantIntent.ChangeSheetDetent -> {
                viewModelScope.launch { controller.setSheetDetent(intent.detent) }
            }

            VoiceAssistantIntent.TapSpeak -> {
                val current = uiState.value
                if (!current.hasMicPermission) {
                    _effects.tryEmit(VoiceAssistantUiEffect.RequestMicrophonePermission)
                } else if (!current.isListening) {
                    viewModelScope.launch { startVoiceRecording() }
                }
            }

            VoiceAssistantIntent.TapStop -> {
                viewModelScope.launch { stopAssistant() }
            }

            VoiceAssistantIntent.RetryConnection -> {
                viewModelScope.launch { connectVoiceAssistant(isManualRetry = true) }
            }

            is VoiceAssistantIntent.UpdateTextInput -> {
                textDraft.value = intent.text
            }

            VoiceAssistantIntent.SubmitTextInput -> {
                val draft = textDraft.value.trim()
                if (draft.isNotEmpty()) {
                    textDraft.value = ""
                    viewModelScope.launch { sendTextMessage(draft) }
                }
            }

            is VoiceAssistantIntent.SelectQuickPrompt -> {
                viewModelScope.launch { sendTextMessage(intent.prompt.promptText) }
            }

            is VoiceAssistantIntent.OpenRestaurant -> {
                // Keep bottom sheet state persistent across navigation without forcibly dismissing it
                _effects.tryEmit(
                    VoiceAssistantUiEffect.Navigate(
                        VoiceNavigationTarget.RestaurantDetail(intent.restaurantId)
                    )
                )
            }

            is VoiceAssistantIntent.ToggleFavoriteRestaurant -> {
                viewModelScope.launch {
                    toggleFavoriteRestaurant(intent.restaurantId)
                }
            }

            is VoiceAssistantIntent.AddFoodToCart -> {
                viewModelScope.launch {
                    val result = executeAssistantAction(
                        VoiceActionRequest.AddItemToCart(
                            foodId = intent.item.id,
                            foodName = intent.item.name,
                            quantity = 1
                        )
                    )
                    _effects.tryEmit(VoiceAssistantUiEffect.ShowMessage(result.summaryForVoice))
                }
            }

            is VoiceAssistantIntent.IncrementFoodInCart -> {
                viewModelScope.launch {
                    val existing = latestCartItems.firstOrNull { it.itemId == intent.foodId }
                    if (existing != null) {
                        updateCartQuantity(existing.itemId, existing.quantity + 1)
                    } else {
                        executeAssistantAction(
                            VoiceActionRequest.AddItemToCart(foodId = intent.foodId, quantity = 1)
                        )
                    }
                }
            }

            is VoiceAssistantIntent.DecrementFoodInCart -> {
                viewModelScope.launch {
                    val existing = latestCartItems.firstOrNull { it.itemId == intent.foodId }
                    if (existing != null) {
                        if (existing.quantity <= 1) {
                            removeCartItem(existing.itemId)
                        } else {
                            updateCartQuantity(existing.itemId, existing.quantity - 1)
                        }
                    }
                }
            }

            VoiceAssistantIntent.OpenCart -> {
                _effects.tryEmit(VoiceAssistantUiEffect.Navigate(VoiceNavigationTarget.Cart))
            }

            VoiceAssistantIntent.ConfirmPendingAction -> {
                viewModelScope.launch { controller.confirmPendingAction() }
            }

            VoiceAssistantIntent.DismissPendingAction -> {
                viewModelScope.launch { controller.dismissPendingAction() }
            }

            is VoiceAssistantIntent.PermissionResult -> {
                controller.onMicrophonePermissionResult(
                    granted = intent.granted,
                    permanentlyDenied = intent.permanentlyDenied
                )
                if (intent.granted && uiState.value.isSheetOpen) {
                    viewModelScope.launch { startVoiceRecording() }
                }
            }

            is VoiceAssistantIntent.ScreenContextChanged -> {
                controller.updateScreenContext(
                    screenName = intent.screenName,
                    restaurantId = intent.restaurantId,
                    restaurantName = intent.restaurantName
                )
            }

            is VoiceAssistantIntent.ExecuteDirectAction -> {
                viewModelScope.launch { executeAssistantAction(intent.action) }
            }
        }
    }

    /** Forwards app foreground and background changes to the assistant controller. */
    fun onEvent(event: VoiceAssistantEvent) {
        when (event) {
            VoiceAssistantEvent.AppBackgrounded -> controller.onAppBackgrounded()
            VoiceAssistantEvent.AppForegrounded -> controller.onAppForegrounded()
        }
    }

    /** Releases shared assistant resources when this ViewModel is destroyed. */
    override fun onCleared() {
        // viewModelScope is already cancelled here; invoke synchronous idempotent cleanup
        controller.releaseResources()
        super.onCleared()
    }

    /** Combines assistant, conversation, cart, and draft data into screen state. */
    private fun mapToUiState(
        snapshot: VoiceAssistantSnapshot,
        conversation: VoiceConversationContext,
        cartItems: List<CartItem>,
        draft: String
    ): VoiceAssistantUiState {
        val statusText = when {
            snapshot.phase == VoiceAssistantPhase.ERROR && snapshot.activeError != null -> {
                snapshot.activeError.userMessage
            }
            snapshot.statusMessage.isNotBlank() &&
                !snapshot.statusMessage.contains("Paused", ignoreCase = true) -> {
                snapshot.statusMessage
            }
            else -> snapshot.phase.userVisibleStatus
        }

        val cartQtyMap = cartItems
            .groupBy { it.itemId }
            .mapValues { (_, items) -> items.sumOf { it.quantity } }

        return VoiceAssistantUiState(
            isSheetOpen = snapshot.isOpen,
            sheetDetent = snapshot.sheetDetent,
            interactionMode = snapshot.interactionMode,
            phase = snapshot.phase,
            connectionState = snapshot.connectionState,
            recordingState = snapshot.recordingState,
            playbackState = snapshot.playbackState,
            isFallbackMode = snapshot.isFallbackMode,
            hasMicPermission = snapshot.hasMicPermission,
            isPermissionPermanentlyDenied = snapshot.isPermissionPermanentlyDenied,
            inputAmplitude = snapshot.inputAmplitude,
            outputAmplitude = snapshot.outputAmplitude,
            statusText = statusText,
            liveUserTranscript = snapshot.liveUserTranscript,
            liveAssistantTranscript = snapshot.liveAssistantTranscript,
            messages = conversation.messages,
            recentRecommendations = conversation.recentRecommendations,
            cartQuantitiesByFoodId = cartQtyMap,
            pendingConfirmation = conversation.pendingConfirmation,
            activeError = snapshot.activeError,
            textInputDraft = draft,
            currentScreenName = conversation.currentScreenName,
            activeAudioRoute = snapshot.activeAudioRoute,
            activeModelName = snapshot.activeModelName
        )
    }
}
