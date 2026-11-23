package com.foodflow.domain

import com.foodflow.domain.model.RestaurantOperatingHours
import com.foodflow.domain.model.ScheduleEndConditionType
import com.foodflow.domain.model.ScheduleRecurrenceRule
import com.foodflow.domain.model.ScheduleRecurrenceType
import com.foodflow.domain.usecase.scheduled.CalculateNextScheduleOccurrenceUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ScheduleRecurrenceCalculatorTest {

    private val calculator = CalculateNextScheduleOccurrenceUseCase()
    private val utc = TimeZone.getTimeZone("UTC")
    private val hours = RestaurantOperatingHours(
        openHour = 8,
        openMinute = 0,
        closeHour = 23,
        closeMinute = 0,
        preparationLeadMinutes = 20
    )

    private fun utcMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `one-time schedule validates past times, prep lead time, and operating hours`() {
        val now = utcMillis(2026, Calendar.NOVEMBER, 20, 12, 0)
        val rule = ScheduleRecurrenceRule(
            type = ScheduleRecurrenceType.ONE_TIME,
            hourOfDay = 12,
            minute = 10,
            timezoneId = "UTC"
        )

        // Past time rejected
        val pastErrors = calculator.validateScheduleConfiguration(
            scheduledAtUtcMs = now - 60_000L,
            rule = rule,
            operatingHours = hours,
            nowUtcMs = now
        )
        assertTrue(pastErrors.any { it.contains("future", ignoreCase = true) })

        // Within 20-minute prep lead time rejected
        val leadErrors = calculator.validateScheduleConfiguration(
            scheduledAtUtcMs = now + 10 * 60_000L,
            rule = rule,
            operatingHours = hours,
            nowUtcMs = now
        )
        assertTrue(leadErrors.any { it.contains("20 minutes", ignoreCase = true) })

        // Outside operating hours (03:00 AM) rejected
        val closedSlot = utcMillis(2026, Calendar.NOVEMBER, 21, 3, 0)
        val closedErrors = calculator.validateScheduleConfiguration(
            scheduledAtUtcMs = closedSlot,
            rule = rule.copy(hourOfDay = 3, minute = 0),
            operatingHours = hours,
            nowUtcMs = now
        )
        assertTrue(closedErrors.any { it.contains("operating hours", ignoreCase = true) })

        // Valid future slot passes with zero errors
        val validSlot = utcMillis(2026, Calendar.NOVEMBER, 20, 18, 30)
        val validErrors = calculator.validateScheduleConfiguration(
            scheduledAtUtcMs = validSlot,
            rule = rule.copy(hourOfDay = 18, minute = 30),
            operatingHours = hours,
            nowUtcMs = now
        )
        assertTrue(validErrors.isEmpty())
    }

    @Test
    fun `daily recurrence advances cleanly and stops after max occurrences`() {
        val start = utcMillis(2026, Calendar.NOVEMBER, 20, 19, 30)
        val rule = ScheduleRecurrenceRule(
            type = ScheduleRecurrenceType.DAILY,
            hourOfDay = 19,
            minute = 30,
            endConditionType = ScheduleEndConditionType.AFTER_OCCURRENCES,
            maxOccurrences = 2,
            timezoneId = "UTC"
        )

        val occ2 = calculator(
            rule = rule,
            baseScheduledAtUtcMs = start,
            afterUtcMs = start,
            completedOccurrences = 1
        )
        assertEquals(utcMillis(2026, Calendar.NOVEMBER, 21, 19, 30), occ2)

        val occ3 = calculator(
            rule = rule,
            baseScheduledAtUtcMs = start,
            afterUtcMs = occ2!!,
            completedOccurrences = 2
        )
        assertNull(occ3)
    }

    @Test
    fun `weekly recurrence supports multi-weekday Mon Wed Fri and every Friday`() {
        // Nov 20, 2026 is a Friday
        val fridayNoon = utcMillis(2026, Calendar.NOVEMBER, 20, 12, 0)
        val mwfRule = ScheduleRecurrenceRule(
            type = ScheduleRecurrenceType.WEEKLY,
            hourOfDay = 12,
            minute = 0,
            weekdays = listOf(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.FRIDAY),
            timezoneId = "UTC"
        )

        // After Friday Nov 20 12:00 -> next should be Monday Nov 23 12:00
        val nextMon = calculator(mwfRule, fridayNoon, fridayNoon, completedOccurrences = 1)
        assertEquals(utcMillis(2026, Calendar.NOVEMBER, 23, 12, 0), nextMon)

        // After Monday Nov 23 12:00 -> next should be Wednesday Nov 25 12:00
        val nextWed = calculator(mwfRule, fridayNoon, nextMon!!, completedOccurrences = 2)
        assertEquals(utcMillis(2026, Calendar.NOVEMBER, 25, 12, 0), nextWed)

        // Every Friday only -> Nov 27, 2026
        val fridayOnly = mwfRule.copy(weekdays = listOf(Calendar.FRIDAY))
        val nextFri = calculator(fridayOnly, fridayNoon, fridayNoon, completedOccurrences = 1)
        assertEquals(utcMillis(2026, Calendar.NOVEMBER, 27, 12, 0), nextFri)
    }

    @Test
    fun `monthly recurrence clamps 31st safely for Feb non-leap, Feb leap, and April`() {
        val rule31 = ScheduleRecurrenceRule(
            type = ScheduleRecurrenceType.MONTHLY,
            hourOfDay = 20,
            minute = 0,
            dayOfMonth = 31,
            timezoneId = "UTC"
        )

        // Jan 31, 2027 -> Feb 28, 2027 (non-leap year)
        val jan2027 = utcMillis(2027, Calendar.JANUARY, 31, 20, 0)
        val feb2027 = calculator(rule31, jan2027, jan2027, completedOccurrences = 1)
        assertEquals(utcMillis(2027, Calendar.FEBRUARY, 28, 20, 0), feb2027)

        // Mar 31, 2027 -> Apr 30, 2027
        val mar2027 = utcMillis(2027, Calendar.MARCH, 31, 20, 0)
        val apr2027 = calculator(rule31, mar2027, mar2027, completedOccurrences = 3)
        assertEquals(utcMillis(2027, Calendar.APRIL, 30, 20, 0), apr2027)

        // Jan 31, 2028 -> Feb 29, 2028 (leap year)
        val jan2028 = utcMillis(2028, Calendar.JANUARY, 31, 20, 0)
        val feb2028 = calculator(rule31, jan2028, jan2028, completedOccurrences = 1)
        assertEquals(utcMillis(2028, Calendar.FEBRUARY, 29, 20, 0), feb2028)
    }

    @Test
    fun `end date boundary stops recurring occurrences after end date`() {
        val start = utcMillis(2026, Calendar.NOVEMBER, 20, 19, 0)
        val endDate = utcMillis(2026, Calendar.NOVEMBER, 21, 12, 0)
        val rule = ScheduleRecurrenceRule(
            type = ScheduleRecurrenceType.DAILY,
            hourOfDay = 19,
            minute = 0,
            endConditionType = ScheduleEndConditionType.ON_DATE,
            endDateUtcMs = endDate,
            timezoneId = "UTC"
        )

        val day2 = calculator(rule, start, start, completedOccurrences = 1)
        assertEquals(utcMillis(2026, Calendar.NOVEMBER, 21, 19, 0), day2)

        val day3 = calculator(rule, start, day2!!, completedOccurrences = 2)
        assertNull(day3)
    }

    @Test
    fun `DST spring-forward transition preserves customer local wall-clock delivery time`() {
        val nyTz = TimeZone.getTimeZone("America/New_York")
        // March 7, 2026 19:00 EST (day before US spring-forward DST on March 8, 2026)
        val beforeDst = Calendar.getInstance(nyTz).apply {
            clear()
            set(2026, Calendar.MARCH, 7, 19, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val rule = ScheduleRecurrenceRule(
            type = ScheduleRecurrenceType.DAILY,
            hourOfDay = 19,
            minute = 0,
            timezoneId = "America/New_York"
        )

        val afterDst = calculator(rule, beforeDst, beforeDst, completedOccurrences = 1)
        assertNotNull(afterDst)
        val calAfter = Calendar.getInstance(nyTz).apply { timeInMillis = afterDst!! }
        assertEquals(8, calAfter.get(Calendar.DAY_OF_MONTH))
        assertEquals(19, calAfter.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, calAfter.get(Calendar.MINUTE))
    }
}
