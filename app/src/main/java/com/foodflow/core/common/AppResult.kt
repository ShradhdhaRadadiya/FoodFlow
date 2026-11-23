package com.foodflow.core.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.math.min
import kotlin.math.pow

/**
 * Strongly-typed error hierarchy so ViewModels surface accurate, actionable customer copy
 * while keeping structured diagnostics for developer logs and tests.
 */
sealed class AppError(
    open val userMessage: String,
    open val technicalDetail: String = userMessage,
    open val isRetryable: Boolean = false
) {
    data class Offline(
        override val userMessage: String = "No internet connection. Please check your network and try again.",
        override val technicalDetail: String = "No network connectivity"
    ) : AppError(userMessage, technicalDetail, isRetryable = true)

    data class ConnectionTimeout(
        override val userMessage: String = "Could not connect to our servers in time. Please try again.",
        override val technicalDetail: String = "Connection timeout"
    ) : AppError(userMessage, technicalDetail, isRetryable = true)

    data class Timeout(
        override val userMessage: String = "The request timed out. Please try again.",
        override val technicalDetail: String = "Socket timeout"
    ) : AppError(userMessage, technicalDetail, isRetryable = true)

    data class DnsFailure(
        override val userMessage: String = "Unable to reach FoodFlow servers right now. Check your connection.",
        override val technicalDetail: String = "DNS resolution failed"
    ) : AppError(userMessage, technicalDetail, isRetryable = true)

    data class HttpBadRequest(
        override val userMessage: String = "We couldn't process that request. Please review your details and try again.",
        override val technicalDetail: String = "HTTP 400 Bad Request"
    ) : AppError(userMessage, technicalDetail, isRetryable = false)

    data class Unauthorized(
        override val userMessage: String = "Your session has expired. Please sign in again.",
        override val technicalDetail: String = "HTTP 401 Unauthorized"
    ) : AppError(userMessage, technicalDetail, isRetryable = false)

    data class NotFound(
        override val userMessage: String = "The requested item or order could not be found.",
        override val technicalDetail: String = "HTTP 404 Not Found"
    ) : AppError(userMessage, technicalDetail, isRetryable = false)

    data class ServerError(
        val httpCode: Int = 500,
        override val userMessage: String = "Our servers are temporarily busy. Please try again shortly.",
        override val technicalDetail: String = "HTTP $httpCode"
    ) : AppError(userMessage, technicalDetail, isRetryable = httpCode >= 500)

    data class InvalidResponse(
        override val userMessage: String = "Unable to read the server response right now. Please try again.",
        override val technicalDetail: String = "Malformed API payload"
    ) : AppError(userMessage, technicalDetail, isRetryable = true)

    data class SerializationError(
        override val userMessage: String = "We encountered a data formatting issue. Please try again.",
        override val technicalDetail: String = "JSON serialization failed"
    ) : AppError(userMessage, technicalDetail, isRetryable = false)

    data class EmptyResponse(
        override val userMessage: String = "No data was returned from the server. Please retry.",
        override val technicalDetail: String = "Empty response body"
    ) : AppError(userMessage, technicalDetail, isRetryable = true)

    data class DatabaseError(
        override val userMessage: String = "Unable to save your data locally. Please try again.",
        override val technicalDetail: String = "Local database operation failed"
    ) : AppError(userMessage, technicalDetail, isRetryable = true)

    data class Conflict(
        override val userMessage: String,
        override val technicalDetail: String = userMessage
    ) : AppError(userMessage, technicalDetail, isRetryable = false)

    data class Validation(
        override val userMessage: String,
        override val technicalDetail: String = userMessage
    ) : AppError(userMessage, technicalDetail, isRetryable = false)

    data class Unknown(
        override val userMessage: String = "Something unexpected happened. Please try again.",
        override val technicalDetail: String = "Unexpected error"
    ) : AppError(userMessage, technicalDetail, isRetryable = false)
}

/**
 * Reusable generic Result wrapper for Clean Architecture repository and use-case boundaries.
 */
sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Error(val error: AppError) : AppResult<Nothing>
}

/** Transforms successful data while passing failures through unchanged. */
inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Error -> this
}

/** Returns successful data, or null when this result contains an error. */
fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.data

/**
 * Centralized error mapper translating low-level network, HTTP, JSON, and SQLite exceptions
 * into domain-level [AppError] values while never swallowing [CancellationException].
 */
object ErrorMapper {

    /** Maps known platform exceptions to app errors and rethrows coroutine cancellation. */
    fun map(throwable: Throwable): AppError {
        if (throwable is CancellationException) throw throwable
        return when (throwable) {
            is UnknownHostException -> AppError.DnsFailure(
                technicalDetail = throwable.message ?: "Unknown host"
            )
            is ConnectException -> AppError.ConnectionTimeout(
                technicalDetail = throwable.message ?: "Connection refused/timed out"
            )
            is SocketTimeoutException -> AppError.Timeout(
                technicalDetail = throwable.message ?: "Socket timeout"
            )
            is retrofit2.HttpException -> fromHttpCode(
                code = throwable.code(),
                detail = throwable.message()
            )
            is kotlinx.serialization.SerializationException -> AppError.SerializationError(
                technicalDetail = throwable.message ?: "Serialization error"
            )
            is android.database.sqlite.SQLiteException -> AppError.DatabaseError(
                technicalDetail = throwable.message ?: "SQLite failure"
            )
            is IOException -> AppError.Offline(
                technicalDetail = throwable.message ?: "Network IO failure"
            )
            is IllegalArgumentException -> AppError.InvalidResponse(
                technicalDetail = throwable.message ?: "Invalid payload argument"
            )
            else -> AppError.Unknown(
                technicalDetail = "${throwable::class.simpleName}: ${throwable.message}"
            )
        }
    }

    /** Maps common HTTP status ranges to matching user-facing and retryable error types. */
    fun fromHttpCode(code: Int, detail: String? = null): AppError = when (code) {
        400, 422 -> AppError.HttpBadRequest(technicalDetail = detail ?: "HTTP $code")
        401, 403 -> AppError.Unauthorized(technicalDetail = detail ?: "HTTP $code")
        404 -> AppError.NotFound(technicalDetail = detail ?: "HTTP 404")
        408 -> AppError.Timeout(technicalDetail = detail ?: "HTTP 408 Request Timeout")
        409 -> AppError.Conflict(
            userMessage = "There was a conflict updating your request. Please review and try again.",
            technicalDetail = detail ?: "HTTP 409 Conflict"
        )
        in 500..599 -> AppError.ServerError(httpCode = code, technicalDetail = detail ?: "HTTP $code")
        else -> AppError.Unknown(technicalDetail = detail ?: "HTTP $code")
    }
}

/**
 * Retries only error results accepted by [shouldRetry], passing each one-based attempt to [block].
 * Delay grows exponentially from [initialDelayMs], is capped at [maxDelayMs], and stops at
 * [maxAttempts]; successful and non-retryable results return immediately. Cancellation remains
 * the caller's responsibility and should propagate from [block].
 */
suspend fun <T> retryWithBackoff(
    maxAttempts: Int = 3,
    initialDelayMs: Long = 300L,
    maxDelayMs: Long = 3_000L,
    factor: Double = 2.0,
    shouldRetry: (AppError) -> Boolean = { it.isRetryable },
    block: suspend (attempt: Int) -> AppResult<T>
): AppResult<T> {
    var lastResult: AppResult<T> = AppResult.Error(AppError.Unknown())
    for (attempt in 1..maxAttempts) {
        val result = block(attempt)
        lastResult = result
        if (result is AppResult.Success) return result
        val error = (result as AppResult.Error).error
        if (attempt == maxAttempts || !shouldRetry(error)) return result
        val backoff = min(maxDelayMs, (initialDelayMs * factor.pow(attempt - 1)).toLong())
        delay(backoff)
    }
    return lastResult
}

/**
 * Value class with operator overloading for expressive, type-safe INR currency arithmetic.
 */
@JvmInline
value class Money(val amount: Double) : Comparable<Money> {
    /** Adds amounts and rounds the result to two decimal places. */
    operator fun plus(other: Money): Money = Money((amount + other.amount).roundTo2Decimals())
    /** Subtracts without allowing a negative amount, then rounds to cents. */
    operator fun minus(other: Money): Money = Money((amount - other.amount).coerceAtLeast(0.0).roundTo2Decimals())
    /** Multiplies an amount by an item count and rounds to cents. */
    operator fun times(quantity: Int): Money = Money((amount * quantity).roundTo2Decimals())
    /** Compares the numeric amount for sorting and range checks. */
    override fun compareTo(other: Money): Int = amount.compareTo(other.amount)

    companion object {
        val ZERO = Money(0.0)
    }
}

/** Rounds a decimal amount to two places using the shared app money convention. */
fun Double.roundTo2Decimals(): Double = kotlin.math.round(this * 100.0) / 100.0

/** Formats a rounded amount as an INR string, omitting decimals for whole rupees. */
fun Double.toInr(): String {
    val rounded = roundTo2Decimals()
    return if (rounded % 1.0 == 0.0) {
        "₹${rounded.toInt()}"
    } else {
        "₹${String.format(java.util.Locale.US, "%.2f", rounded)}"
    }
}

/**
 * Reified safe JSON parser helper demonstrating inline reified type preservation.
 */
inline fun <reified T> kotlinx.serialization.json.Json.decodeSafe(payload: String): AppResult<T> {
    return try {
        AppResult.Success(decodeFromString<T>(payload))
    } catch (e: kotlinx.serialization.SerializationException) {
        AppResult.Error(AppError.SerializationError(technicalDetail = e.message ?: "Parse error"))
    } catch (e: IllegalArgumentException) {
        AppResult.Error(AppError.InvalidResponse(technicalDetail = e.message ?: "Invalid payload"))
    }
}
