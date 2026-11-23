package com.foodflow.domain.usecase.scheduled

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * Formats requested order placement timestamps consistently across Checkout, Confirmation,
 * Order History, and Scheduled Order Detail views.
 *
 * Explicitly clarifies that the selected timestamp is when the order will be placed with
 * the restaurant, never the estimated delivery arrival time.
 */
class FormatScheduledPlacementUseCase @Inject constructor() {

    /**
     * Produces the clear confirmation copy:
     * e.g. "Your order will be placed on 20 November at 8:00 PM."
     */
    fun formatConfirmationSentence(
        placementUtcMs: Long,
        timezoneId: String = TimeZone.getDefault().id
    ): String {
        val tz = TimeZone.getTimeZone(timezoneId.ifBlank { TimeZone.getDefault().id })
        val dateText = SimpleDateFormat("d MMMM", Locale.US).apply { timeZone = tz }.format(Date(placementUtcMs))
        val timeText = SimpleDateFormat("h:mm a", Locale.US).apply { timeZone = tz }.format(Date(placementUtcMs))
        return "Your order will be placed on $dateText at $timeText."
    }

    /**
     * Produces a compact summary line for cards and summaries:
     * e.g. "20 Nov 2026 at 8:00 PM"
     */
    fun formatDateTimeSummary(
        placementUtcMs: Long,
        timezoneId: String = TimeZone.getDefault().id
    ): String {
        val tz = TimeZone.getTimeZone(timezoneId.ifBlank { TimeZone.getDefault().id })
        val dateText = SimpleDateFormat("d MMM yyyy", Locale.US).apply { timeZone = tz }.format(Date(placementUtcMs))
        val timeText = SimpleDateFormat("h:mm a", Locale.US).apply { timeZone = tz }.format(Date(placementUtcMs))
        return "$dateText at $timeText"
    }

    /** Formats the scheduled placement date in the requested time zone. */
    fun formatDate(
        placementUtcMs: Long,
        timezoneId: String = TimeZone.getDefault().id
    ): String {
        val tz = TimeZone.getTimeZone(timezoneId.ifBlank { TimeZone.getDefault().id })
        return SimpleDateFormat("EEE, d MMM yyyy", Locale.US).apply { timeZone = tz }.format(Date(placementUtcMs))
    }

    /** Formats the scheduled placement time in the requested time zone. */
    fun formatTime(
        placementUtcMs: Long,
        timezoneId: String = TimeZone.getDefault().id
    ): String {
        val tz = TimeZone.getTimeZone(timezoneId.ifBlank { TimeZone.getDefault().id })
        return SimpleDateFormat("h:mm a", Locale.US).apply { timeZone = tz }.format(Date(placementUtcMs))
    }
}
