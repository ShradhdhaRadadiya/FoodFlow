package com.foodflow.domain.usecase.scheduled

import com.foodflow.domain.model.RestaurantOperatingHours
import com.foodflow.domain.model.ScheduleEndConditionType
import com.foodflow.domain.model.ScheduleRecurrenceRule
import com.foodflow.domain.model.ScheduleRecurrenceType
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject

/**
 * Pure, timezone- and DST-aware schedule occurrence engine.
 *
 * Uses [TimeZone] + [Calendar] in the customer's configured timezone while returning
 * normalized UTC epoch milliseconds so WorkManager delays, Room queries, and backend
 * timestamps remain consistent even across timezone or daylight-saving transitions.
 */
class CalculateNextScheduleOccurrenceUseCase @Inject constructor() {

    /**
     * Computes the next recurrence candidate using the schedule's local time zone, then returns its
     * UTC epoch value for persistence and worker scheduling. The one-time/daily/weekly/monthly branch
     * applies the relevant calendar rule; occurrence-count and inclusive local end-date limits are
     * checked around that candidate. Returns `null` when no future occurrence remains.
     */
    operator fun invoke(
        rule: ScheduleRecurrenceRule,
        baseScheduledAtUtcMs: Long,
        afterUtcMs: Long,
        completedOccurrences: Int = 0
    ): Long? {
        // 1. Enforce end-after-N-occurrences limit
        if (rule.isRecurring && rule.endConditionType == ScheduleEndConditionType.AFTER_OCCURRENCES) {
            val max = rule.maxOccurrences ?: return null
            if (completedOccurrences >= max.coerceAtMost(MAX_ALLOWED_OCCURRENCES)) {
                return null
            }
        }

        val tz = TimeZone.getTimeZone(rule.timezoneId.ifBlank { TimeZone.getDefault().id })

        val candidateUtcMs: Long? = when (rule.type) {
            ScheduleRecurrenceType.ONE_TIME -> {
                if (completedOccurrences > 0) null
                else baseScheduledAtUtcMs.takeIf { it > afterUtcMs }
            }

            ScheduleRecurrenceType.DAILY -> {
                calculateNextDaily(
                    tz = tz,
                    hourOfDay = rule.hourOfDay,
                    minute = rule.minute,
                    anchorUtcMs = maxOf(baseScheduledAtUtcMs - 1L, afterUtcMs)
                )
            }

            ScheduleRecurrenceType.WEEKLY -> {
                val targetDays = rule.weekdays.filter { it in Calendar.SUNDAY..Calendar.SATURDAY }.toSet()
                if (targetDays.isEmpty()) {
                    null
                } else {
                    calculateNextWeekly(
                        tz = tz,
                        hourOfDay = rule.hourOfDay,
                        minute = rule.minute,
                        weekdays = targetDays,
                        anchorUtcMs = maxOf(baseScheduledAtUtcMs - 1L, afterUtcMs)
                    )
                }
            }

            ScheduleRecurrenceType.MONTHLY -> {
                val desiredDay = (rule.dayOfMonth ?: extractDayOfMonth(baseScheduledAtUtcMs, tz))
                    .coerceIn(1, 31)
                calculateNextMonthly(
                    tz = tz,
                    hourOfDay = rule.hourOfDay,
                    minute = rule.minute,
                    desiredDayOfMonth = desiredDay,
                    anchorUtcMs = maxOf(baseScheduledAtUtcMs - 1L, afterUtcMs)
                )
            }
        }

        if (candidateUtcMs == null) return null

        // 2. Enforce end-on-date boundary (inclusive through 23:59:59 of the selected end date)
        if (rule.isRecurring && rule.endConditionType == ScheduleEndConditionType.ON_DATE) {
            val endLimit = rule.endDateUtcMs ?: return null
            val endOfDayLimit = endOfDayInTimezone(endLimit, tz)
            if (candidateUtcMs > endOfDayLimit) {
                return null
            }
        }

        return candidateUtcMs
    }

    /**
     * Collects all validation messages rather than stopping at the first failure. It checks future
     * time and preparation lead, scheduling horizon, restaurant hours in the schedule time zone,
     * recurrence-specific fields, and the selected end condition; callers can display every issue
     * before persisting the schedule.
     */
    fun validateScheduleConfiguration(
        scheduledAtUtcMs: Long,
        rule: ScheduleRecurrenceRule,
        operatingHours: RestaurantOperatingHours,
        nowUtcMs: Long = System.currentTimeMillis()
    ): List<String> {
        val errors = mutableListOf<String>()
        val tz = TimeZone.getTimeZone(rule.timezoneId.ifBlank { TimeZone.getDefault().id })

        val minLeadMs = operatingHours.preparationLeadMinutes * 60_000L
        if (scheduledAtUtcMs <= nowUtcMs) {
            errors += "Scheduled date and time must be in the future."
        } else if (scheduledAtUtcMs < nowUtcMs + minLeadMs) {
            errors += "Please schedule at least ${operatingHours.preparationLeadMinutes} minutes ahead for kitchen preparation."
        }

        val maxHorizonMs = MAX_SCHEDULE_HORIZON_DAYS * 86_400_000L
        if (scheduledAtUtcMs > nowUtcMs + maxHorizonMs) {
            errors += "Orders can be scheduled up to $MAX_SCHEDULE_HORIZON_DAYS days in advance."
        }

        val targetCal = Calendar.getInstance(tz).apply { timeInMillis = scheduledAtUtcMs }
        val checkHour = if (rule.isRecurring) rule.hourOfDay else targetCal.get(Calendar.HOUR_OF_DAY)
        val checkMinute = if (rule.isRecurring) rule.minute else targetCal.get(Calendar.MINUTE)
        if (!operatingHours.isWithinOperatingHours(checkHour, checkMinute)) {
            errors += "Selected time is outside restaurant operating hours (${operatingHours.formattedWindow()})."
        }

        if (rule.type == ScheduleRecurrenceType.WEEKLY && rule.weekdays.none { it in Calendar.SUNDAY..Calendar.SATURDAY }) {
            errors += "Select at least one weekday for a weekly recurring order."
        }

        if (rule.type == ScheduleRecurrenceType.MONTHLY) {
            val day = rule.dayOfMonth
            if (day == null || day !in 1..31) {
                errors += "Select a valid day of the month (1–31)."
            }
        }

        if (rule.isRecurring) {
            when (rule.endConditionType) {
                ScheduleEndConditionType.NEVER -> Unit
                ScheduleEndConditionType.ON_DATE -> {
                    val endMs = rule.endDateUtcMs
                    if (endMs == null) {
                        errors += "Select an end date for this recurring schedule."
                    } else if (endOfDayInTimezone(endMs, tz) < scheduledAtUtcMs) {
                        errors += "End date must be on or after the first scheduled order placement."
                    } else if (endMs > nowUtcMs + MAX_RECURRING_HORIZON_DAYS * 86_400_000L) {
                        errors += "Recurring end date cannot exceed $MAX_RECURRING_HORIZON_DAYS days from today."
                    }
                }
                ScheduleEndConditionType.AFTER_OCCURRENCES -> {
                    val count = rule.maxOccurrences
                    if (count == null || count < 1) {
                        errors += "Specify at least 1 occurrence for the recurring limit."
                    } else if (count > MAX_ALLOWED_OCCURRENCES) {
                        errors += "Recurring schedules support up to $MAX_ALLOWED_OCCURRENCES occurrences."
                    }
                }
            }
        }

        return errors
    }

    /**
     * Generates a human-readable recurrence summary for checkout & schedule management cards.
     */
    fun formatRecurrenceSummary(rule: ScheduleRecurrenceRule): String {
        val timeText = formatTime12Hour(rule.hourOfDay, rule.minute)
        val base = when (rule.type) {
            ScheduleRecurrenceType.ONE_TIME -> "One-time order placement"
            ScheduleRecurrenceType.DAILY -> "Every day at $timeText"
            ScheduleRecurrenceType.WEEKLY -> {
                val sorted = rule.weekdays.distinct().sortedBy { mondayFirstSortOrder(it) }
                val labels = sorted.joinToString(", ") { shortWeekdayLabel(it) }
                if (labels.isEmpty()) "Weekly at $timeText" else "Every $labels at $timeText"
            }
            ScheduleRecurrenceType.MONTHLY -> {
                val day = (rule.dayOfMonth ?: 1).coerceIn(1, 31)
                "Monthly on the ${ordinal(day)} at $timeText"
            }
        }
        if (!rule.isRecurring) return base
        val suffix = when (rule.endConditionType) {
            ScheduleEndConditionType.NEVER -> ""
            ScheduleEndConditionType.ON_DATE -> rule.endDateUtcMs?.let {
                val sdf = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.US).apply {
                    timeZone = TimeZone.getTimeZone(rule.timezoneId.ifBlank { TimeZone.getDefault().id })
                }
                " · Until ${sdf.format(java.util.Date(it))}"
            }.orEmpty()
            ScheduleEndConditionType.AFTER_OCCURRENCES -> rule.maxOccurrences?.let {
                " · $it order${if (it == 1) "" else "s"}"
            }.orEmpty()
        }
        return base + suffix
    }

    /** Finds the next daily placement time while preserving the local clock time across DST. */
    private fun calculateNextDaily(
        tz: TimeZone,
        hourOfDay: Int,
        minute: Int,
        anchorUtcMs: Long
    ): Long {
        val cal = Calendar.getInstance(tz).apply {
            timeInMillis = anchorUtcMs
            set(Calendar.HOUR_OF_DAY, hourOfDay.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= anchorUtcMs) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            // Re-assert wall-clock hour/minute so DST shifts preserve local clock time
            cal.set(Calendar.HOUR_OF_DAY, hourOfDay.coerceIn(0, 23))
            cal.set(Calendar.MINUTE, minute.coerceIn(0, 59))
        }
        return cal.timeInMillis
    }

    /** Finds the next selected weekday placement time after the given anchor. */
    private fun calculateNextWeekly(
        tz: TimeZone,
        hourOfDay: Int,
        minute: Int,
        weekdays: Set<Int>,
        anchorUtcMs: Long
    ): Long? {
        val cal = Calendar.getInstance(tz).apply {
            timeInMillis = anchorUtcMs
            set(Calendar.HOUR_OF_DAY, hourOfDay.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        for (offsetDays in 0..14) {
            val probe = (cal.clone() as Calendar).apply {
                if (offsetDays > 0) add(Calendar.DAY_OF_YEAR, offsetDays)
                set(Calendar.HOUR_OF_DAY, hourOfDay.coerceIn(0, 23))
                set(Calendar.MINUTE, minute.coerceIn(0, 59))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (probe.get(Calendar.DAY_OF_WEEK) in weekdays && probe.timeInMillis > anchorUtcMs) {
                return probe.timeInMillis
            }
        }
        return null
    }

    /**
     * Handles months shorter than [desiredDayOfMonth] cleanly by clamping to the last
     * valid calendar day of that month (e.g., 31st -> Feb 28 / Feb 29 in leap years, Apr 30).
     */
    private fun calculateNextMonthly(
        tz: TimeZone,
        hourOfDay: Int,
        minute: Int,
        desiredDayOfMonth: Int,
        anchorUtcMs: Long
    ): Long {
        val anchorCal = Calendar.getInstance(tz).apply { timeInMillis = anchorUtcMs }
        var year = anchorCal.get(Calendar.YEAR)
        var month = anchorCal.get(Calendar.MONTH)

        repeat(24) {
            val probe = Calendar.getInstance(tz).apply {
                clear()
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, 1)
                val maxDay = getActualMaximum(Calendar.DAY_OF_MONTH)
                set(Calendar.DAY_OF_MONTH, desiredDayOfMonth.coerceAtMost(maxDay))
                set(Calendar.HOUR_OF_DAY, hourOfDay.coerceIn(0, 23))
                set(Calendar.MINUTE, minute.coerceIn(0, 59))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (probe.timeInMillis > anchorUtcMs) {
                return probe.timeInMillis
            }
            month += 1
            if (month > Calendar.DECEMBER) {
                month = Calendar.JANUARY
                year += 1
            }
        }
        return anchorUtcMs + 30 * 86_400_000L
    }

    /** Reads the day number for a timestamp in the schedule's time zone. */
    private fun extractDayOfMonth(utcMs: Long, tz: TimeZone): Int =
        Calendar.getInstance(tz).apply { timeInMillis = utcMs }.get(Calendar.DAY_OF_MONTH)

    /** Returns the final millisecond of the timestamp's local calendar day. */
    private fun endOfDayInTimezone(utcMs: Long, tz: TimeZone): Long =
        Calendar.getInstance(tz).apply {
            timeInMillis = utcMs
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

    /** Converts hour and minute values into a 12-hour time label. */
    private fun formatTime12Hour(hourOfDay: Int, minute: Int): String {
        val period = if (hourOfDay < 12) "AM" else "PM"
        val displayHour = when {
            hourOfDay == 0 -> 12
            hourOfDay > 12 -> hourOfDay - 12
            else -> hourOfDay
        }
        return String.format(java.util.Locale.US, "%d:%02d %s", displayHour, minute, period)
    }

    /** Maps a calendar weekday to its Monday-first display order. */
    private fun mondayFirstSortOrder(calDay: Int): Int = when (calDay) {
        Calendar.MONDAY -> 1
        Calendar.TUESDAY -> 2
        Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4
        Calendar.FRIDAY -> 5
        Calendar.SATURDAY -> 6
        Calendar.SUNDAY -> 7
        else -> 8
    }

    /** Returns the short display label for a calendar weekday. */
    private fun shortWeekdayLabel(calDay: Int): String = when (calDay) {
        Calendar.MONDAY -> "Mon"
        Calendar.TUESDAY -> "Tue"
        Calendar.WEDNESDAY -> "Wed"
        Calendar.THURSDAY -> "Thu"
        Calendar.FRIDAY -> "Fri"
        Calendar.SATURDAY -> "Sat"
        Calendar.SUNDAY -> "Sun"
        else -> ""
    }

    /** Adds the correct English ordinal suffix to a day number. */
    private fun ordinal(day: Int): String {
        if (day in 11..13) return "${day}th"
        return when (day % 10) {
            1 -> "${day}st"
            2 -> "${day}nd"
            3 -> "${day}rd"
            else -> "${day}th"
        }
    }

    companion object {
        const val MAX_ALLOWED_OCCURRENCES = 100
        const val MAX_SCHEDULE_HORIZON_DAYS = 90L
        const val MAX_RECURRING_HORIZON_DAYS = 365L
    }
}
