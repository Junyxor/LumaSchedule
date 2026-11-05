package com.lumaschedule.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Calendar rules shared by the database, reminders, the widget and ICS export.
 * They mirror `src/lib/schedule/calendar.ts`:
 *
 * - Week 1 is the calendar week (Mon–Sun, or Sun–Sat when weekStartsOn = 7)
 *   that contains the term start date.
 * - `day` is 1 = Monday … 7 = Sunday regardless of the week start.
 * - A weeks mask of 0 means "every week".
 */
object ScheduleMath {
    const val MAX_WEEKS = 64
    const val MAX_SECTIONS = 30

    val PALETTE = listOf("violet", "blue", "cyan", "teal", "green", "lime", "amber", "orange", "red", "pink")

    fun parseDate(raw: String?): LocalDate? {
        val text = raw?.trim().orEmpty()
        val match = Regex("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})").find(text) ?: return null
        return runCatching {
            LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
        }.getOrNull()
    }

    fun parseTime(raw: String?): LocalTime? {
        val match = Regex("^(\\d{1,2})[:：](\\d{2})").find(raw?.trim().orEmpty()) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        return if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
    }

    /** "8:05" → "08:05"; invalid input → "". */
    fun normalizeTime(raw: String?): String = parseTime(raw)?.let { "%02d:%02d".format(it.hour, it.minute) }.orEmpty()

    fun normalizeWeekStart(value: Int): Int = if (value == 7) 7 else 1

    fun dayOffset(day: Int, weekStartsOn: Int): Int = if (weekStartsOn == 7) day % 7 else day - 1

    fun weekStart(date: LocalDate, weekStartsOn: Int): LocalDate {
        val day = date.dayOfWeek.value // 1 = Monday … 7 = Sunday
        return date.minusDays(dayOffset(day, weekStartsOn).toLong())
    }

    fun dateOf(termStart: LocalDate, weekStartsOn: Int, week: Int, day: Int): LocalDate =
        weekStart(termStart, weekStartsOn).plusDays((week - 1) * 7L + dayOffset(day, weekStartsOn))

    /** Teaching week of [date]; may be < 1 before the term or > weekCount after it. */
    fun teachingWeek(date: LocalDate, termStart: LocalDate, weekStartsOn: Int): Int {
        val days = ChronoUnit.DAYS.between(weekStart(termStart, weekStartsOn), date)
        return Math.floorDiv(days, 7L).toInt() + 1
    }

    fun weekdayOf(date: LocalDate): Int = date.dayOfWeek.value

    fun weekdayLabel(day: Int): String = when (day) {
        DayOfWeek.MONDAY.value -> "周一"
        DayOfWeek.TUESDAY.value -> "周二"
        DayOfWeek.WEDNESDAY.value -> "周三"
        DayOfWeek.THURSDAY.value -> "周四"
        DayOfWeek.FRIDAY.value -> "周五"
        DayOfWeek.SATURDAY.value -> "周六"
        else -> "周日"
    }

    fun weeksFromMask(mask: Long): List<Int> = (1..MAX_WEEKS).filter { week -> (mask and (1L shl (week - 1))) != 0L }

    fun maskFromWeeks(weeks: Iterable<Int>): Long {
        var mask = 0L
        weeks.forEach { week -> if (week in 1..MAX_WEEKS) mask = mask or (1L shl (week - 1)) }
        return mask
    }

    fun inWeek(mask: Long, week: Int): Boolean = mask == 0L || (week in 1..MAX_WEEKS && (mask and (1L shl (week - 1))) != 0L)

    fun colorFor(name: String): String = PALETTE[(name.hashCode() and Int.MAX_VALUE) % PALETTE.size]

    fun normalizedName(raw: String): String = raw.trim().filterNot { it.isWhitespace() }.lowercase()

    // ------------------------------------------------------------------ time slots

    data class Slot(val section: Int, val start: String, val end: String)

    /** Accepts the Shiguang format (`[{number,startTime,endTime}]`) and a few aliases. */
    fun parseSlots(raw: String?): List<Slot> {
        if (raw.isNullOrBlank()) return emptyList()
        val root: Any = runCatching { if (raw.trimStart().startsWith("[")) JSONArray(raw) else JSONObject(raw) }.getOrNull() ?: return emptyList()
        val array = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("timeSlots") ?: root.optJSONArray("slots") ?: root.optJSONArray("sections")
            else -> null
        } ?: return emptyList()
        val result = ArrayList<Slot>(array.length())
        for (index in 0 until array.length()) {
            val slot = array.optJSONObject(index) ?: continue
            val number = slot.optInt("number", slot.optInt("section", slot.optInt("index", slot.optInt("node", -1))))
            if (number !in 1..MAX_SECTIONS) continue
            val start = normalizeTime(firstString(slot, "startTime", "start_time", "start"))
            val end = normalizeTime(firstString(slot, "endTime", "end_time", "end"))
            if (start.isNotEmpty() && end.isNotEmpty()) result += Slot(number, start, end)
        }
        return result.distinctBy { it.section }.sortedBy { it.section }
    }

    fun slotsJson(slots: List<Slot>): String = JSONArray().apply {
        slots.forEach { put(JSONObject().put("number", it.section).put("startTime", it.start).put("endTime", it.end)) }
    }.toString()

    private fun firstString(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj.optString(key).trim()
            if (value.isNotEmpty() && value != "null") return value
        }
        return ""
    }

    // ------------------------------------------------------------------ meeting times

    data class Meeting(
        val id: String,
        val name: String,
        val teacher: String,
        val room: String,
        val day: Int,
        val startSection: Int,
        val endSection: Int,
        val startTime: String,
        val endTime: String,
        val weeksMask: Long
    )

    data class TimeRange(val start: LocalTime, val end: LocalTime?)

    /**
     * Resolves clock times for meetings: explicit time → saved slot → the most
     * common explicit time other meetings use for the same section. Meetings
     * with no trustworthy time are left out instead of guessing.
     */
    class TimeResolver(slots: List<Slot>, meetings: List<Meeting>) {
        private val slotStart = slots.associate { it.section to parseTime(it.start) }
        private val slotEnd = slots.associate { it.section to parseTime(it.end) }
        private val inferredStart = mostCommon(meetings.mapNotNull { m -> parseTime(m.startTime)?.let { m.startSection to it } })
        private val inferredEnd = mostCommon(meetings.mapNotNull { m -> parseTime(m.endTime)?.let { m.endSection to it } })

        fun resolve(meeting: Meeting): TimeRange? {
            val start = parseTime(meeting.startTime) ?: slotStart[meeting.startSection] ?: inferredStart[meeting.startSection] ?: return null
            val last = maxOf(meeting.startSection, meeting.endSection)
            val end = parseTime(meeting.endTime) ?: slotEnd[last] ?: inferredEnd[last]
            return TimeRange(start, end?.takeIf { it.isAfter(start) })
        }

        private fun mostCommon(pairs: List<Pair<Int, LocalTime>>): Map<Int, LocalTime> = pairs
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, times) -> times.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<LocalTime, Int>> { it.value }.thenBy { it.key }).first().key }
    }
}
