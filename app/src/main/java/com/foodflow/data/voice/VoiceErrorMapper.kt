package com.foodflow.data.voice

import android.util.Log
import com.foodflow.domain.model.VoiceAssistantError
import com.foodflow.domain.model.VoiceErrorCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized Voice Assistant error classification & recovery mapper.
 * - Never swallows CancellationException.
 * - Never exposes raw stack traces, tokens, or internal credentials to users.
 * - Clearly differentiates non-retryable quota/auth/permission errors from transient outages.
 */
@Singleton
class VoiceErrorMapper @Inject constructor() {

    /** Converts a transport or assistant failure into a safe user-facing error and recovery choice. */
    fun mapThrowable(
        throwable: Throwable,
        isOnline: Boolean = true,
        contextTag: String = "VoiceAssistant"
    ): VoiceErrorMappingResult {
        if (throwable is CancellationException && throwable !is TimeoutCancellationException) {
            throw throwable
        }

        val rawMessage = buildString {
            append(throwable.javaClass.simpleName)
            append(": ")
            append(throwable.message.orEmpty())
            throwable.cause?.let { cause ->
                append(" | cause=")
                append(cause.javaClass.simpleName)
                append(": ")
                append(cause.message.orEmpty())
            }
        }
        val lower = rawMessage.lowercase()
        runCatching { Log.e(contextTag, "Mapped voice error: ${throwable.javaClass.simpleName}", throwable) }

        if (!isOnline) {
            return VoiceErrorMappingResult(
                error = VoiceAssistantError(
                    category = VoiceErrorCategory.NO_INTERNET,
                    userMessage = "You're offline. Switched to local FoodFlow menu search.",
                    recoveryHint = "Reconnect to the internet to resume live Gemini voice.",
                    canRetry = true,
                    fallbackAvailable = true
                ),
                shouldTriggerFallback = true
            )
        }

        if (throwable is TimeoutCancellationException || throwable is SocketTimeoutException || lower.contains("timeout")) {
            return VoiceErrorMappingResult(
                categoryError(
                    category = VoiceErrorCategory.CONNECTION_TIMEOUT,
                    message = "Connection timed out while reaching the voice assistant.",
                    hint = "Tap Retry or continue with local menu search."
                ),
                shouldTriggerFallback = true
            )
        }

        if (throwable is SecurityException || lower.contains("record_audio") || lower.contains("permission")) {
            return VoiceErrorMappingResult(
                error = VoiceAssistantError(
                    category = VoiceErrorCategory.PERMISSION_REVOKED,
                    userMessage = "Microphone permission is required to speak.",
                    recoveryHint = "Grant microphone access or type your request below.",
                    canRetry = false,
                    fallbackAvailable = true
                ),
                shouldTriggerFallback = false
            )
        }

        if (lower.contains("quota") || lower.contains("resource_exhausted") || lower.contains("billing") || lower.contains("free tier")) {
            return VoiceErrorMappingResult(
                error = VoiceAssistantError(
                    category = VoiceErrorCategory.QUOTA_EXHAUSTED,
                    userMessage = "You've reached the current AI usage limit. Local FoodFlow search is ready.",
                    recoveryHint = "Use quick menu search below or try Gemini voice again later.",
                    canRetry = false,
                    fallbackAvailable = true
                ),
                shouldTriggerFallback = true
            )
        }

        if (lower.contains("429") || lower.contains("rate limit") || lower.contains("too many requests")) {
            return VoiceErrorMappingResult(
                error = VoiceAssistantError(
                    category = VoiceErrorCategory.RATE_LIMITED,
                    userMessage = "The voice assistant is busy right now. Please wait a moment and try again.",
                    recoveryHint = "You can also search FoodFlow items locally below.",
                    canRetry = true,
                    fallbackAvailable = true
                ),
                shouldTriggerFallback = true
            )
        }

        if (lower.contains("403") ||
            lower.contains("401") ||
            lower.contains("app check") ||
            lower.contains("attestation") ||
            lower.contains("handshake") ||
            lower.contains("api key") ||
            lower.contains("unauthenticated") ||
            lower.contains("unauthorized")
        ) {
            return VoiceErrorMappingResult(
                error = VoiceAssistantError(
                    category = VoiceErrorCategory.AUTH_FAILURE,
                    userMessage = "Voice AI authentication could not be verified right now.",
                    recoveryHint = "Local FoodFlow assistant fallback is available below.",
                    canRetry = false,
                    fallbackAvailable = true
                ),
                shouldTriggerFallback = true
            )
        }

        if (lower.contains("model") && (lower.contains("not found") || lower.contains("invalid") || lower.contains("unsupported") || lower.contains("404"))) {
            return VoiceErrorMappingResult(
                error = VoiceAssistantError(
                    category = VoiceErrorCategory.INVALID_MODEL,
                    userMessage = "The configured live voice model is currently unavailable.",
                    recoveryHint = "Local FoodFlow assistant fallback is ready below.",
                    canRetry = false,
                    fallbackAvailable = true
                ),
                shouldTriggerFallback = true
            )
        }

        if (lower.contains("session") && (lower.contains("expired") || lower.contains("goaway") || lower.contains("1001") || lower.contains("1008"))) {
            return VoiceErrorMappingResult(
                error = VoiceAssistantError(
                    category = VoiceErrorCategory.SESSION_EXPIRED,
                    userMessage = "Voice session expired. Reconnecting with your conversation context...",
                    recoveryHint = "Tap Retry if reconnection does not complete.",
                    canRetry = true,
                    fallbackAvailable = true
                ),
                shouldTriggerFallback = false
            )
        }

        if (lower.contains("safety") || lower.contains("blocked") || lower.contains("prohibited")) {
            return VoiceErrorMappingResult(
                error = VoiceAssistantError(
                    category = VoiceErrorCategory.SAFETY_BLOCKED,
                    userMessage = "That response could not be spoken due to safety restrictions.",
                    recoveryHint = "Try rephrasing your food request.",
                    canRetry = false,
                    fallbackAvailable = false
                ),
                shouldTriggerFallback = false
            )
        }

        if (lower.contains("audio") && lower.contains("format")) {
            return VoiceErrorMappingResult(
                error = VoiceAssistantError(
                    category = VoiceErrorCategory.UNSUPPORTED_AUDIO,
                    userMessage = "Audio format is not supported on this device.",
                    recoveryHint = "You can continue using text commands below.",
                    canRetry = false,
                    fallbackAvailable = true
                ),
                shouldTriggerFallback = false
            )
        }

        if (lower.contains("500") || lower.contains("502") || lower.contains("503") || lower.contains("unavailable") || throwable is IOException) {
            return VoiceErrorMappingResult(
                error = VoiceAssistantError(
                    category = VoiceErrorCategory.SERVER_ERROR,
                    userMessage = "The AI assistant is temporarily unavailable.",
                    recoveryHint = "Tap Retry or continue with local FoodFlow search.",
                    canRetry = true,
                    fallbackAvailable = true
                ),
                shouldTriggerFallback = true
            )
        }

        return VoiceErrorMappingResult(
            error = VoiceAssistantError(
                category = VoiceErrorCategory.UNKNOWN,
                userMessage = "Something went wrong with the voice assistant. Please try again.",
                recoveryHint = "You can retry voice or use local menu search below.",
                canRetry = true,
                fallbackAvailable = true
            ),
            shouldTriggerFallback = true
        )
    }

    /** Creates a retryable error result with fallback enabled. */
    private fun categoryError(
        category: VoiceErrorCategory,
        message: String,
        hint: String
    ): VoiceAssistantError = VoiceAssistantError(
        category = category,
        userMessage = message,
        recoveryHint = hint,
        canRetry = category.isRetryable,
        fallbackAvailable = true
    )
}
