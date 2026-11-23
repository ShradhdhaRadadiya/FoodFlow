package com.foodflow.data.voice

import android.util.Log
import com.foodflow.core.common.DispatcherProvider
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.domain.model.VoiceActionResult
import com.foodflow.domain.model.VoiceConversationContext
import com.foodflow.domain.model.VoiceMessageRole
import com.foodflow.domain.model.VoiceToolCallInvocation
import com.google.firebase.Firebase
import com.google.firebase.ai.Chat
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GeminiTextChat"
private const val TEXT_MODEL_ID = "gemini-3.5-flash-lite"
private const val TEXT_REQUEST_TIMEOUT_MS = 30_000L
private const val MAX_TOOL_ROUNDS = 3
private const val MAX_HISTORY_TURNS = 12

/**
 * Dedicated text-only Gemini AI chat data source using Firebase AI Logic (`Firebase.ai`).
 * - Strictly text-in / text-out (+ FoodFlow tool declarations for real catalog/cart/order cards).
 * - Uses `gemini-3.5-flash-lite` + streaming (`sendMessageStream`) + App Check / model pre-warming
 *   + single-roundtrip short-circuiting when FoodFlow tools already produce rich cards or grounded
 *   action summaries to keep first-message latency minimal.
 * - Never initializes Gemini Live audio WebSockets, TTS, AudioTrack, AudioFocus, or microphone capture.
 * - Authenticated transparently through Firebase App Check (no client API key).
 */
@Singleton
class FirebaseGeminiTextChatDataSourceImpl @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val connectivityObserver: ConnectivityObserver
) : VoiceTextChatDataSource {

    override val activeModelName: String = TEXT_MODEL_ID

    private val tools by lazy { GeminiFoodFlowToolSchema.buildTools() }
    private val textGenerationConfig by lazy {
        generationConfig {
            temperature = 0.2f
            maxOutputTokens = 256
        }
    }

    private val appCheckWarmUpStarted = AtomicBoolean(false)

    @Volatile
    private var cachedModelEntry: Pair<String, GenerativeModel>? = null

    /** Initializes reusable text-chat configuration asynchronously before the first user prompt. */
    override fun warmUp() {
        // Pre-build tool schema & generation config off the critical first-message path
        tools
        textGenerationConfig
        runCatching { Firebase.ai }

        // Pre-fetch Firebase App Check token so the very first Gemini request doesn't stall
        // waiting on cold attestation / debug token exchange.
        if (connectivityObserver.isCurrentlyOnline() && appCheckWarmUpStarted.compareAndSet(false, true)) {
            runCatching {
                FirebaseAppCheck.getInstance()
                    .getAppCheckToken(false)
                    .addOnFailureListener {
                        appCheckWarmUpStarted.set(false)
                    }
            }.onFailure {
                appCheckWarmUpStarted.set(false)
            }
        }
    }

    /** Sends one text turn, streams partial text, handles tool calls, and returns the final reply. */
    override suspend fun sendTextMessage(
        userText: String,
        turnId: Long,
        context: VoiceConversationContext,
        onToolCalls: suspend (List<VoiceToolCallInvocation>) -> List<Pair<VoiceToolCallInvocation, VoiceActionResult>>,
        onPartialText: (String) -> Unit
    ): Result<AssistantTextChatResponse> {
        if (!connectivityObserver.isCurrentlyOnline()) {
            return Result.failure(IllegalStateException("Offline"))
        }

        return withContext(dispatchers.io) {
            try {
                val contextSummary = buildContextSummary(context)
                val systemPrompt = GeminiFoodFlowToolSchema.buildTextSystemInstruction(contextSummary)
                val history = buildSanitizedHistory(context, excludeTurnId = turnId)

                val model = getOrCreateModel(systemPrompt)
                val chat = model.startChat(history = history)
                var invokedTools = false

                val finalText = withTimeout(TEXT_REQUEST_TIMEOUT_MS) {
                    var streamOutcome = collectStreamTurn(
                        chat = chat,
                        prompt = content(role = "user") { text(userText) },
                        onPartialText = onPartialText
                    )

                    var round = 0
                    while (streamOutcome.functionCalls.isNotEmpty() && round < MAX_TOOL_ROUNDS) {
                        invokedTools = true
                        val invocations = streamOutcome.functionCalls.map {
                            GeminiFoodFlowToolSchema.toDomainInvocation(it)
                        }
                        val toolResults = onToolCalls(invocations)

                        // Fast path: when tool execution already rendered rich visual cards,
                        // triggered navigation, or opened a confirmation banner, skip the
                        // redundant 2nd Gemini roundtrip (cuts latency in half!).
                        val alreadyHandledByRichUi = toolResults.any { (_, res) ->
                            res.structuredResponse?.hasVisualCards == true ||
                                res.navigationTarget != null ||
                                res.requiresConfirmation != null
                        }
                        if (alreadyHandledByRichUi) {
                            return@withTimeout ""
                        }

                        // Fast path: when every tool succeeded and produced a grounded, user-ready
                        // FoodFlow summary (e.g. cart update or live order status), return it directly
                        // without waiting for an extra network roundtrip just to paraphrase it.
                        if (toolResults.isNotEmpty() &&
                            toolResults.all { (_, res) -> res.success && res.summaryForVoice.isNotBlank() }
                        ) {
                            return@withTimeout toolResults
                                .joinToString("\n") { (_, res) -> res.summaryForVoice.trim() }
                        }

                        // Otherwise (e.g. empty search / tool error), send results back so Gemini can
                        // retry with a refined query or compose a helpful conversational reply.
                        val functionResponseContent = content(role = "function") {
                            toolResults.forEach { (invocation, result) ->
                                part(GeminiFoodFlowToolSchema.toFunctionResponsePart(invocation, result))
                            }
                        }
                        streamOutcome = collectStreamTurn(
                            chat = chat,
                            prompt = functionResponseContent,
                            onPartialText = onPartialText
                        )
                        round++
                    }
                    streamOutcome.text
                }

                if (finalText.isBlank() && !invokedTools) {
                    Result.failure(IllegalStateException("Empty response from Gemini text chat"))
                } else {
                    Result.success(
                        AssistantTextChatResponse(
                            replyText = finalText,
                            invokedTools = invokedTools
                        )
                    )
                }
            } catch (e: TimeoutCancellationException) {
                runCatching { Log.e(TAG, "Text chat request timed out", e) }
                Result.failure(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { Log.e(TAG, "Firebase AI text chat failed", e) }
                Result.failure(e)
            }
        }
    }

    /** Reuses a model for the same prompt or creates a newly configured Gemini model. */
    private fun getOrCreateModel(systemPrompt: String): GenerativeModel {
        cachedModelEntry?.let { (cachedPrompt, cachedModel) ->
            if (cachedPrompt == systemPrompt) return cachedModel
        }
        // Lazy Firebase.ai evaluation at inference time (never at class construction time)
        val created = Firebase.ai.generativeModel(
            modelName = TEXT_MODEL_ID,
            generationConfig = textGenerationConfig,
            tools = tools,
            systemInstruction = content { text(systemPrompt) }
        )
        cachedModelEntry = systemPrompt to created
        return created
    }

    /** Collects generated chunks and tool requests, invoking callbacks as the response arrives. */
    private suspend fun collectStreamTurn(
        chat: Chat,
        prompt: Content,
        onPartialText: (String) -> Unit
    ): StreamTurnOutcome {
        val textBuffer = StringBuilder()
        val collectedCalls = mutableListOf<FunctionCallPart>()

        chat.sendMessageStream(prompt).collect { chunk ->
            val chunkCalls = chunk.functionCalls
            if (chunkCalls.isNotEmpty()) {
                collectedCalls.addAll(chunkCalls)
            }
            val delta = chunk.text
            if (!delta.isNullOrEmpty()) {
                textBuffer.append(delta)
                if (collectedCalls.isEmpty()) {
                    val snapshot = textBuffer.toString().trim()
                    if (snapshot.isNotEmpty()) {
                        onPartialText(snapshot)
                    }
                }
            }
        }

        return StreamTurnOutcome(
            text = textBuffer.toString().trim(),
            functionCalls = collectedCalls
        )
    }

    private data class StreamTurnOutcome(
        val text: String,
        val functionCalls: List<FunctionCallPart>
    )

    /** Formats current FoodFlow screen and conversation facts for the text model prompt. */
    private fun buildContextSummary(context: VoiceConversationContext): String = buildString {
        appendLine("Current FoodFlow screen: ${context.currentScreenName}")
        if (!context.selectedRestaurantName.isNullOrBlank()) {
            appendLine("Selected restaurant: ${context.selectedRestaurantName} (id=${context.selectedRestaurantId})")
        }
        if (context.olderContextSummary.isNotBlank()) {
            appendLine("Earlier conversation summary: ${context.olderContextSummary}")
        }
        if (context.recentRecommendations.isNotEmpty()) {
            appendLine("Most recent numbered recommendations:")
            context.recentRecommendations.forEach { rec ->
                appendLine("  ${rec.index}. ${rec.foodName} (id=${rec.foodId}) from ${rec.restaurantName} — ₹${rec.price.toInt()}")
            }
        }
    }

    /**
     * Converts recent unified conversation messages (from both TEXT and VOICE turns) into
     * strictly alternating `user` -> `model` history entries accepted by Gemini `startChat`.
     */
    /** Builds bounded model history while removing transient or unsafe message content. */
    private fun buildSanitizedHistory(
        context: VoiceConversationContext,
        excludeTurnId: Long
    ): List<Content> {
        val rawTurns = context.messages
            .filter { msg ->
                msg.turnId != excludeTurnId &&
                    msg.text.isNotBlank() &&
                    msg.role != VoiceMessageRole.SYSTEM
            }
            .takeLast(MAX_HISTORY_TURNS)
            .map { msg ->
                val role = if (msg.role == VoiceMessageRole.USER) "user" else "model"
                role to msg.text.trim()
            }

        // Merge consecutive entries with the same role
        val merged = mutableListOf<Pair<String, String>>()
        for ((role, text) in rawTurns) {
            if (merged.isNotEmpty() && merged.last().first == role) {
                val prev = merged.removeAt(merged.lastIndex)
                merged.add(role to "${prev.second}\n$text")
            } else {
                merged.add(role to text)
            }
        }

        // Gemini chat history must start with a user turn and end with a model turn before sendMessage(userText)
        while (merged.isNotEmpty() && merged.first().first != "user") {
            merged.removeAt(0)
        }
        while (merged.isNotEmpty() && merged.last().first == "user") {
            merged.removeAt(merged.lastIndex)
        }

        return merged.map { (role, text) ->
            content(role = role) { text(text) }
        }
    }
}
