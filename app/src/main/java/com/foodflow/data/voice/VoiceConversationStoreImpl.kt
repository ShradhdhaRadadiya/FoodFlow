package com.foodflow.data.voice

import com.foodflow.domain.model.VoiceConversationContext
import com.foodflow.domain.model.VoiceConversationMessage
import com.foodflow.domain.model.VoiceMessageRole
import com.foodflow.domain.model.VoicePendingConfirmation
import com.foodflow.domain.model.VoiceRecommendationItem
import com.foodflow.domain.model.VoiceResponseType
import com.foodflow.domain.model.VoiceStructuredResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-scoped bounded conversation context store.
 * - Survives screen navigation and WebSocket reconnections.
 * - Enforces strict chronological ordering:
 *   Older turns -> Latest User Message -> Latest Assistant Response / Tool Cards.
 *   Even when streaming speech transcription arrives slightly after a tool call or output
 *   chunk for the same turnId, the USER message is guaranteed to sit directly ABOVE its
 *   corresponding assistant response.
 * - Bounds history to MAX_HISTORY_MESSAGES (40) and summarizes evicted older turns.
 * - Tracks recent food recommendations so follow-ups ("the second one") resolve reliably.
 * - Stores zero sensitive payment/credential data.
 */
@Singleton
class VoiceConversationStoreImpl @Inject constructor() : VoiceConversationDataSource {

    private val turnCounter = AtomicLong(0L)
    private val _conversation = MutableStateFlow(
        VoiceConversationContext(
            conversationId = "conv_${UUID.randomUUID().toString().take(8)}",
            sessionId = "sess_${UUID.randomUUID().toString().take(8)}"
        )
    )
    override val conversation: StateFlow<VoiceConversationContext> = _conversation.asStateFlow()

    /** Adds a message while replacing duplicate turn-and-role entries and keeping history bounded. */
    override fun appendMessage(message: VoiceConversationMessage) {
        _conversation.update { current ->
            // Prevent duplicate identical messages for the same turn & role
            val duplicateIdx = current.messages.indexOfLast {
                it.turnId == message.turnId &&
                    it.role == message.role &&
                    it.text.trim().equals(message.text.trim(), ignoreCase = false) &&
                    it.structuredResponse == message.structuredResponse
            }
            val updated = if (duplicateIdx >= 0) {
                current.messages.toMutableList().apply { set(duplicateIdx, message) }
            } else {
                insertInChronologicalOrder(current.messages, message)
            }
            applyBoundAndSummary(current, updated, message)
        }
    }

    /** Inserts or refreshes the live message as streamed text and response metadata arrive. */
    override fun updateStreamingMessage(
        turnId: Long,
        role: VoiceMessageRole,
        fullText: String,
        isStreaming: Boolean,
        isInterrupted: Boolean,
        isFallback: Boolean,
        recommendations: List<VoiceRecommendationItem>,
        structuredResponse: VoiceStructuredResponse?
    ) {
        val trimmed = fullText.trim()
        if (trimmed.isEmpty() && recommendations.isEmpty() && structuredResponse == null) return
        _conversation.update { current ->
            val existingIdx = current.messages.indexOfLast { it.turnId == turnId && it.role == role }
            val updatedMessage = if (existingIdx >= 0) {
                val prev = current.messages[existingIdx]
                prev.copy(
                    text = trimmed.ifEmpty { prev.text },
                    isStreaming = isStreaming,
                    isInterrupted = isInterrupted || prev.isInterrupted,
                    isFallback = isFallback || prev.isFallback,
                    recommendations = if (recommendations.isNotEmpty()) recommendations else prev.recommendations,
                    structuredResponse = structuredResponse ?: prev.structuredResponse
                )
            } else {
                VoiceConversationMessage(
                    id = "msg_${turnId}_${role.name}_${UUID.randomUUID().toString().take(6)}",
                    turnId = turnId,
                    role = role,
                    text = trimmed,
                    timestampMs = System.currentTimeMillis(),
                    isStreaming = isStreaming,
                    isInterrupted = isInterrupted,
                    isFallback = isFallback,
                    recommendations = recommendations,
                    structuredResponse = structuredResponse
                )
            }
            val nextList = if (existingIdx >= 0) {
                current.messages.toMutableList().apply { set(existingIdx, updatedMessage) }
            } else {
                insertInChronologicalOrder(current.messages, updatedMessage)
            }
            applyBoundAndSummary(current, nextList, updatedMessage)
        }
    }

    /** Checks whether this turn already contains recommendation or cart cards. */
    override fun hasStructuredCardsForTurn(turnId: Long): Boolean {
        return _conversation.value.messages.any { msg ->
            msg.turnId == turnId && msg.structuredResponse?.let { sr ->
                sr.type == VoiceResponseType.RESTAURANT_RECOMMENDATIONS ||
                    sr.type == VoiceResponseType.FOOD_RECOMMENDATIONS ||
                    sr.type == VoiceResponseType.CART_SUMMARY
            } == true
        }
    }

    /** Ends the assistant message for a turn and marks it as interrupted. */
    override fun markTurnInterrupted(turnId: Long) {
        _conversation.update { current ->
            val updated = current.messages.map { msg ->
                if (msg.turnId == turnId && msg.role == VoiceMessageRole.ASSISTANT) {
                    msg.copy(isStreaming = false, isInterrupted = true)
                } else {
                    msg
                }
            }
            current.copy(messages = updated)
        }
    }

    /** Saves the latest recommendations for later numbered references. */
    override fun updateRecommendations(items: List<VoiceRecommendationItem>) {
        if (items.isEmpty()) return
        _conversation.update { current ->
            current.copy(recentRecommendations = items.take(10))
        }
    }

    /** Updates the app screen and selected restaurant in the assistant context. */
    override fun updateScreenContext(
        screenName: String,
        restaurantId: String?,
        restaurantName: String?
    ) {
        _conversation.update { current ->
            current.copy(
                currentScreenName = screenName,
                selectedRestaurantId = restaurantId ?: current.selectedRestaurantId,
                selectedRestaurantName = restaurantName ?: current.selectedRestaurantName
            )
        }
    }

    /** Stores a non-empty server resumption token for reconnecting the session. */
    override fun updateResumptionHandle(handle: String?) {
        if (handle.isNullOrBlank()) return
        _conversation.update { it.copy(resumptionHandle = handle) }
    }

    /** Sets or clears an assistant action that is waiting for user confirmation. */
    override fun setPendingConfirmation(confirmation: VoicePendingConfirmation?) {
        _conversation.update { it.copy(pendingConfirmation = confirmation) }
    }

    /** Advances the conversation turn counter and returns the new identifier. */
    override fun advanceTurnId(): Long {
        val next = turnCounter.incrementAndGet()
        _conversation.update { it.copy(activeTurnId = next) }
        return next
    }

    /** Returns the current conversation turn identifier. */
    override fun currentTurnId(): Long = turnCounter.get()

    /** Finds a recent recommendation using its displayed one-based number. */
    override fun resolveRecommendationByOrdinal(ordinal1Based: Int): VoiceRecommendationItem? {
        val list = _conversation.value.recentRecommendations
        return list.firstOrNull { it.index == ordinal1Based }
            ?: list.getOrNull(ordinal1Based - 1)
    }

    /** Builds recent conversation and selection context for resuming a live session. */
    override fun buildContextSummaryForReconnect(): String {
        val ctx = _conversation.value
        return buildString {
            if (ctx.olderContextSummary.isNotBlank()) {
                appendLine("Earlier conversation summary: ${ctx.olderContextSummary}")
            }
            val recentTurns = ctx.messages.takeLast(6)
            if (recentTurns.isNotEmpty()) {
                appendLine("Recent turns:")
                recentTurns.forEach { msg ->
                    val speaker = when (msg.role) {
                        VoiceMessageRole.USER -> "User"
                        VoiceMessageRole.ASSISTANT, VoiceMessageRole.FALLBACK_LOCAL -> "Assistant"
                        VoiceMessageRole.TOOL_ACTION -> "Action"
                        VoiceMessageRole.SYSTEM -> "System"
                    }
                    appendLine("- $speaker: ${msg.text.take(180)}")
                }
            }
            if (ctx.recentRecommendations.isNotEmpty()) {
                appendLine("Most recent numbered recommendations shown to user:")
                ctx.recentRecommendations.forEach { item ->
                    appendLine(
                        "  ${item.index}. ${item.foodName} (id=${item.foodId}) from ${item.restaurantName} at ₹${item.price.toInt()} [${if (item.isVeg) "Veg" else "Non-Veg"}]"
                    )
                }
            }
            ctx.selectedRestaurantName?.let {
                appendLine("User is currently viewing restaurant: $it (id=${ctx.selectedRestaurantId})")
            }
            appendLine("Current app screen: ${ctx.currentScreenName}")
        }.trim()
    }

    /** Returns the currently restored conversation snapshot. */
    override fun restoreSnapshot(): VoiceConversationContext = _conversation.value

    /** Changes the live session identifier while preserving conversation messages. */
    override fun resetSessionKeepingHistory(newSessionId: String) {
        _conversation.update { current ->
            current.copy(sessionId = newSessionId)
        }
    }

    /**
     * Inserts [message] preserving strict chronological conversation order:
     * 1. Ascending by `turnId`.
     * 2. Within the same `turnId`, USER messages ALWAYS precede assistant/tool responses.
     */
    private fun insertInChronologicalOrder(
        existing: List<VoiceConversationMessage>,
        message: VoiceConversationMessage
    ): List<VoiceConversationMessage> {
        if (existing.isEmpty()) return listOf(message)
        val result = existing.toMutableList()
        val insertIndex = result.indexOfFirst { item ->
            when {
                item.turnId > message.turnId -> true
                item.turnId == message.turnId -> roleOrder(message.role) < roleOrder(item.role)
                else -> false
            }
        }
        if (insertIndex >= 0) {
            result.add(insertIndex, message)
        } else {
            result.add(message)
        }
        return result
    }

    /** Assigns ordering priority so user messages precede assistant and tool replies. */
    private fun roleOrder(role: VoiceMessageRole): Int = when (role) {
        VoiceMessageRole.USER -> 0
        VoiceMessageRole.SYSTEM -> 1
        VoiceMessageRole.TOOL_ACTION -> 2
        VoiceMessageRole.ASSISTANT, VoiceMessageRole.FALLBACK_LOCAL -> 3
    }

    /** Limits retained messages and folds evicted turns into a compact reconnect summary. */
    private fun applyBoundAndSummary(
        current: VoiceConversationContext,
        updatedMessages: List<VoiceConversationMessage>,
        latestMessage: VoiceConversationMessage
    ): VoiceConversationContext {
        val max = VoiceConversationContext.MAX_HISTORY_MESSAGES
        val (boundedMessages, nextSummary) = if (updatedMessages.size > max) {
            val overflow = updatedMessages.size - max
            val evicted = updatedMessages.take(overflow)
            val kept = updatedMessages.drop(overflow)
            val evictedDigest = evicted
                .filter { it.role == VoiceMessageRole.USER || it.role == VoiceMessageRole.ASSISTANT }
                .joinToString("; ") { "${it.role.name.lowercase()}: ${it.text.take(60)}" }
            val merged = listOf(current.olderContextSummary, evictedDigest)
                .filter { it.isNotBlank() }
                .joinToString(" | ")
                .takeLast(500)
            kept to merged
        } else {
            updatedMessages to current.olderContextSummary
        }

        val nextRecommendations = if (latestMessage.recommendations.isNotEmpty()) {
            latestMessage.recommendations.take(10)
        } else {
            current.recentRecommendations
        }

        return current.copy(
            messages = boundedMessages,
            olderContextSummary = nextSummary,
            latestUserRequest = if (latestMessage.role == VoiceMessageRole.USER) latestMessage.text else current.latestUserRequest,
            latestAiResponse = if (latestMessage.role == VoiceMessageRole.ASSISTANT || latestMessage.role == VoiceMessageRole.FALLBACK_LOCAL) {
                latestMessage.text
            } else {
                current.latestAiResponse
            },
            recentRecommendations = nextRecommendations
        )
    }
}
