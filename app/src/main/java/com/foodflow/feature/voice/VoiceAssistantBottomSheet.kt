package com.foodflow.feature.voice

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.foodflow.domain.model.VoiceConversationMessage
import com.foodflow.domain.model.VoiceMessageRole
import com.foodflow.domain.model.VoiceSheetDetent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Single Unified ChatGPT-Style Persistent Global Voice Assistant Bottom Sheet.
 *
 * Key architectural guarantees:
 * - Mounted ONCE at FoodFlowRoot; preserves conversation, session, and detent across navigation.
 * - ZERO separate floating result trays: ALL conversation text, user queries, inline processing
 *   states, and FoodFlow RestaurantCard / FoodItemCard results render strictly inside this single
 *   unified bottom sheet in exact chronological order (Oldest -> Latest User -> Assistant Response).
 * - Smooth vertical drag handle resizing across COLLAPSED (~30%), HALF_EXPANDED (~55%),
 *   EXPANDED (~82%), and HIDDEN without scroll conflicts with the inner LazyColumn.
 * - ChatGPT-style footer: empty input displays Mic only; non-empty input displays Send only;
 *   tapping Mic transitions smoothly into focused Voice Listening Mode with real-amplitude
 *   AudioVisualizer and strictly Speak/Stop controls.
 * - Single clean IME + navigationBars inset handling prevents keyboard overlap or clipping.
 */
@Composable
fun VoiceAssistantBottomSheet(
    uiState: VoiceAssistantUiState,
    onIntent: (VoiceAssistantIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    val isVisible = uiState.isSheetOpen && uiState.sheetDetent != VoiceSheetDetent.HIDDEN

    BackHandler(enabled = isVisible) {
        when (uiState.sheetDetent) {
            VoiceSheetDetent.EXPANDED -> onIntent(VoiceAssistantIntent.ChangeSheetDetent(VoiceSheetDetent.HALF_EXPANDED))
            VoiceSheetDetent.HALF_EXPANDED -> onIntent(VoiceAssistantIntent.ChangeSheetDetent(VoiceSheetDetent.COLLAPSED))
            VoiceSheetDetent.COLLAPSED -> onIntent(VoiceAssistantIntent.HideAssistant)
            VoiceSheetDetent.HIDDEN -> Unit
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        modifier = modifier.fillMaxSize()
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
        ) {
            val density = LocalDensity.current
            val availableHeight = maxHeight.coerceAtLeast(220.dp)

            val collapsedHeightDp = (availableHeight * 0.32f).coerceIn(
                minimumValue = min(210.dp, availableHeight - 8.dp),
                maximumValue = (availableHeight * 0.42f).coerceAtLeast(min(210.dp, availableHeight - 8.dp))
            )
            val halfExpandedHeightDp = (availableHeight * 0.56f).coerceIn(
                minimumValue = collapsedHeightDp,
                maximumValue = (availableHeight - 8.dp).coerceAtLeast(collapsedHeightDp)
            )
            val expandedHeightDp = (availableHeight * 0.82f).coerceIn(
                minimumValue = halfExpandedHeightDp,
                maximumValue = (availableHeight - 8.dp).coerceAtLeast(halfExpandedHeightDp)
            )

            val collapsedPx = with(density) { collapsedHeightDp.toPx() }
            val halfPx = with(density) { halfExpandedHeightDp.toPx() }
            val expandedPx = with(density) { expandedHeightDp.toPx() }

            val targetPx = when (uiState.sheetDetent) {
                VoiceSheetDetent.COLLAPSED -> collapsedPx
                VoiceSheetDetent.HALF_EXPANDED -> halfPx
                VoiceSheetDetent.EXPANDED -> expandedPx
                VoiceSheetDetent.HIDDEN -> 0f
            }

            val animatedHeightPx = remember { Animatable(targetPx) }
            val coroutineScope = rememberCoroutineScope()
            var isDragging by remember { mutableStateOf(false) }

            LaunchedEffect(targetPx, isDragging) {
                if (!isDragging && targetPx > 0f) {
                    animatedHeightPx.animateTo(
                        targetValue = targetPx,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
                }
            }

            val currentHeightDp = with(density) {
                animatedHeightPx.value.coerceIn(collapsedPx * 0.55f, expandedPx).toDp()
            }

            val isVoiceMode = uiState.isListening || uiState.isSpeaking

            // Single Unified Persistent Bottom Sheet Surface (ZERO external floating trays)
            Surface(
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 14.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .height(currentHeightDp)
                    .testTag("voice_assistant_sheet")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    // Draggable Header Zone — vertical drags resize the sheet cleanly without scroll conflict
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(collapsedPx, halfPx, expandedPx) {
                                detectVerticalDragGestures(
                                    onDragStart = { isDragging = true },
                                    onVerticalDrag = { change, dragAmount ->
                                        change.consume()
                                        val updated = (animatedHeightPx.value - dragAmount)
                                            .coerceIn(collapsedPx * 0.45f, expandedPx)
                                        coroutineScope.launch {
                                            animatedHeightPx.snapTo(updated)
                                        }
                                    },
                                    onDragCancel = { isDragging = false },
                                    onDragEnd = {
                                        isDragging = false
                                        val finalPx = animatedHeightPx.value
                                        val nextDetent = when {
                                            finalPx < collapsedPx * 0.65f -> VoiceSheetDetent.HIDDEN
                                            else -> {
                                                val anchors = listOf(
                                                    VoiceSheetDetent.COLLAPSED to collapsedPx,
                                                    VoiceSheetDetent.HALF_EXPANDED to halfPx,
                                                    VoiceSheetDetent.EXPANDED to expandedPx
                                                )
                                                anchors.minBy { (_, px) -> abs(finalPx - px) }.first
                                            }
                                        }
                                        if (nextDetent == VoiceSheetDetent.HIDDEN) {
                                            onIntent(VoiceAssistantIntent.HideAssistant)
                                        } else {
                                            onIntent(VoiceAssistantIntent.ChangeSheetDetent(nextDetent))
                                        }
                                    }
                                )
                            }
                            .testTag("voice_sheet_drag_zone")
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp, bottom = 6.dp)
                                .testTag("voice_sheet_drag_handle"),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(40.dp)
                                    .height(4.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.outlineVariant)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.GraphicEq,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            AssistantStatusIndicator(
                                phase = uiState.phase,
                                statusText = uiState.statusText,
                                isProcessing = uiState.isProcessing,
                                isFallbackMode = uiState.isFallbackMode,
                                modifier = Modifier.weight(1f)
                            )

                            IconButton(
                                onClick = {
                                    val next = when (uiState.sheetDetent) {
                                        VoiceSheetDetent.COLLAPSED -> VoiceSheetDetent.HALF_EXPANDED
                                        VoiceSheetDetent.HALF_EXPANDED -> VoiceSheetDetent.EXPANDED
                                        VoiceSheetDetent.EXPANDED -> VoiceSheetDetent.COLLAPSED
                                        VoiceSheetDetent.HIDDEN -> VoiceSheetDetent.HALF_EXPANDED
                                    }
                                    onIntent(VoiceAssistantIntent.ChangeSheetDetent(next))
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("voice_sheet_resize_button")
                            ) {
                                Icon(
                                    imageVector = if (uiState.sheetDetent == VoiceSheetDetent.EXPANDED) {
                                        Icons.Filled.KeyboardArrowDown
                                    } else {
                                        Icons.Filled.KeyboardArrowUp
                                    },
                                    contentDescription = if (uiState.sheetDetent == VoiceSheetDetent.EXPANDED) {
                                        "Collapse assistant"
                                    } else {
                                        "Expand assistant"
                                    }
                                )
                            }

                            IconButton(
                                onClick = { onIntent(VoiceAssistantIntent.CloseAssistant) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("voice_close_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Close assistant"
                                )
                            }
                        }
                    }

                    uiState.activeError?.let { error ->
                        VoiceErrorBanner(
                            error = error,
                            onRetry = { onIntent(VoiceAssistantIntent.RetryConnection) },
                            onRequestMicPermission = { onIntent(VoiceAssistantIntent.TapSpeak) },
                            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
                        )
                    }

                    uiState.pendingConfirmation?.let { confirmation ->
                        VoicePendingConfirmationBanner(
                            confirmation = confirmation,
                            onConfirm = { onIntent(VoiceAssistantIntent.ConfirmPendingAction) },
                            onDismiss = { onIntent(VoiceAssistantIntent.DismissPendingAction) },
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }

                    // Unified Chronological Conversation & Card Viewport (always inside this single sheet)
                    AssistantConversationViewport(
                        uiState = uiState,
                        onIntent = onIntent,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                        modifier = Modifier.padding(vertical = 6.dp)
                    )

                    // ChatGPT-Style Dynamic Footer:
                    // - Voice Listening Mode (focused mic + real amplitude visualizer + ONLY Speak/Stop)
                    // - OR Normal Input Mode (Text Field + Mic when empty / Send when non-empty)
                    AnimatedContent(
                        targetState = isVoiceMode,
                        transitionSpec = {
                            (fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 4 })
                                .togetherWith(fadeOut(tween(140)))
                        },
                        label = "voice_footer_mode_transition"
                    ) { voiceActive ->
                        if (voiceActive) {
                            VoiceListeningPanel(
                                phase = uiState.phase,
                                statusText = uiState.statusText,
                                isListening = uiState.isListening,
                                canStop = uiState.canStop,
                                inputAmplitude = uiState.inputAmplitude,
                                outputAmplitude = uiState.outputAmplitude,
                                onSpeakClick = { onIntent(VoiceAssistantIntent.TapSpeak) },
                                onStopClick = { onIntent(VoiceAssistantIntent.TapStop) },
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        } else {
                            AssistantInputBar(
                                text = uiState.textInputDraft,
                                isProcessing = uiState.isProcessing,
                                onTextChange = { onIntent(VoiceAssistantIntent.UpdateTextInput(it)) },
                                onSubmit = { onIntent(VoiceAssistantIntent.SubmitTextInput) },
                                onMicClick = { onIntent(VoiceAssistantIntent.TapSpeak) },
                                onStopClick = { onIntent(VoiceAssistantIntent.TapStop) },
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Displays conversation messages and keeps the latest content visible. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssistantConversationViewport(
    uiState: VoiceAssistantUiState,
    onIntent: (VoiceAssistantIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)

    val isNearBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = layoutInfo.totalItemsCount
            total <= 2 || lastVisibleIndex >= total - 3
        }
    }

    // Avoid rendering a duplicate live transcript bubble when it's already stored in uiState.messages
    val showLiveUserBubble = remember(uiState.liveUserTranscript, uiState.messages, uiState.isListening) {
        val trimmed = uiState.liveUserTranscript.trim()
        trimmed.isNotEmpty() &&
            uiState.isListening &&
            uiState.messages.none { it.role == VoiceMessageRole.USER && it.text.trim() == trimmed }
    }

    val lastMessage = uiState.messages.lastOrNull()
    val lastMessageId = lastMessage?.id
    val lastMessageTextLen = lastMessage?.text?.length ?: 0
    val lastMessageRichCount = (lastMessage?.structuredResponse?.restaurants?.size ?: 0) +
        (lastMessage?.structuredResponse?.foodItems?.size ?: 0) +
        (lastMessage?.recommendations?.size ?: 0)
    val showWelcomeState = uiState.messages.isEmpty() && !showLiveUserBubble && !uiState.isProcessing

    // Always auto-scroll to the bottom anchor when a new user message, AI response, rich card,
    // live transcript, or processing indicator appears (including the very first message after welcome state)
    LaunchedEffect(
        showWelcomeState,
        uiState.messages.size,
        lastMessageId,
        lastMessageRichCount,
        showLiveUserBubble,
        uiState.isProcessing
    ) {
        if (showWelcomeState) return@LaunchedEffect
        val total = snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        withFrameNanos { }
        val targetIndex = (listState.layoutInfo.totalItemsCount.coerceAtLeast(total) - 1).coerceAtLeast(0)
        listState.animateScrollToItem(index = targetIndex)
    }

    // Track streaming text growth and keyboard open while user is near the bottom
    LaunchedEffect(lastMessageTextLen, imeBottomPx > 0) {
        if (showWelcomeState) return@LaunchedEffect
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0 && isNearBottom) {
            withFrameNanos { }
            val latestTotal = listState.layoutInfo.totalItemsCount
            if (latestTotal > 0) {
                listState.animateScrollToItem(index = latestTotal - 1)
            }
        }
    }

    Box(modifier = modifier) {
        AnimatedContent(
            targetState = showWelcomeState,
            transitionSpec = {
                (fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 6 })
                    .togetherWith(fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it / 8 })
            },
            label = "voice_welcome_vs_conversation",
            modifier = Modifier.fillMaxSize()
        ) { isWelcome ->
            if (isWelcome) {
                // Anchored cleanly at the bottom directly above the input bar & mic button
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("voice_welcome_container"),
                    contentAlignment = Alignment.BottomStart
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(top = 6.dp, bottom = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = "How can I help you order?",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("voice_welcome_heading")
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("voice_quick_prompts_row")
                        ) {
                            uiState.quickPrompts.forEach { prompt ->
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                                    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier
                                        .clickable { onIntent(VoiceAssistantIntent.SelectQuickPrompt(prompt)) }
                                        .testTag("voice_quick_prompt_${prompt.id}")
                                ) {
                                    Text(
                                        text = prompt.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("voice_conversation_list"),
                    contentPadding = PaddingValues(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = uiState.messages,
                        key = { it.id },
                        contentType = { it.role.name }
                    ) { message ->
                        if (message.role == VoiceMessageRole.USER) {
                            CompactUserRequestRow(message = message)
                        } else {
                            AssistantResponseRenderer(
                                message = message,
                                cartQuantitiesByFoodId = uiState.cartQuantitiesByFoodId,
                                onOpenRestaurant = { id -> onIntent(VoiceAssistantIntent.OpenRestaurant(id)) },
                                onToggleFavoriteRestaurant = { id -> onIntent(VoiceAssistantIntent.ToggleFavoriteRestaurant(id)) },
                                onAddFoodToCart = { item -> onIntent(VoiceAssistantIntent.AddFoodToCart(item)) },
                                onIncrementFoodInCart = { id -> onIntent(VoiceAssistantIntent.IncrementFoodInCart(id)) },
                                onDecrementFoodInCart = { id -> onIntent(VoiceAssistantIntent.DecrementFoodInCart(id)) },
                                onOpenCart = { onIntent(VoiceAssistantIntent.OpenCart) }
                            )
                        }
                    }

                    if (showLiveUserBubble) {
                        item(key = "live_user_transcript", contentType = "live_user") {
                            CompactUserRequestChip(text = uiState.liveUserTranscript, isLive = true)
                        }
                    }

                    val hasStreamingAssistantBubble = lastMessage?.role == VoiceMessageRole.ASSISTANT &&
                        lastMessage.isStreaming &&
                        lastMessage.text.isNotBlank()
                    if (uiState.isProcessing && !hasStreamingAssistantBubble) {
                        item(key = "assistant_processing_indicator", contentType = "processing") {
                            AssistantProcessingBubble(statusText = uiState.statusText)
                        }
                    }

                    // Bottom sentinel so animateScrollToItem(total - 1) scrolls completely past tall AI cards
                    item(key = "chat_bottom_anchor", contentType = "bottom_anchor") {
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                }
            }
        }

        if (!showWelcomeState && !isNearBottom && uiState.messages.size > 2) {
                Surface(
                    onClick = {
                        coroutineScope.launch {
                            val total = listState.layoutInfo.totalItemsCount
                            if (total > 0) listState.animateScrollToItem(total - 1)
                        }
                    },
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 3.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .testTag("voice_jump_latest_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Latest",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
    }
}

/** Displays a user's message in the compact conversation layout. */
@Composable
private fun CompactUserRequestRow(message: VoiceConversationMessage) {
    CompactUserRequestChip(text = message.text, isLive = message.isStreaming)
}

/** Renders one user message, including its live-streaming state. */
@Composable
private fun CompactUserRequestChip(
    text: String,
    isLive: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = if (isLive) 0.78f else 0.94f),
            modifier = Modifier
                .widthIn(max = 280.dp)
                .testTag("voice_user_query_chip")
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
            )
        }
    }
}

/** Shows the assistant's current processing status in the conversation. */
@Composable
private fun AssistantProcessingBubble(statusText: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("voice_processing_bubble"),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = statusText.ifBlank { "Thinking..." },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
