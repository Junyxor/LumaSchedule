package com.lumaschedule.app.widgets

import com.lumaschedule.app.data.LumaDatabase
import com.lumaschedule.app.data.ScheduleMath
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Works out what the "next course" widget should show right now and when that changes. */
object NextCourseResolver {
    data class State(
        val label: String,
        val title: String,
        val meta: String,
        val badge: String,
        /** Epoch millis of the next moment the content changes, or null when nothing will. */
        val refreshAtMs: Long?
    )

    private data class Occurrence(
        val meeting: ScheduleMath.Meeting,
        val date: LocalDate,
        val start: ZonedDateTime,
        val end: ZonedDateTime,
        val estimated: Boolean
    )

    fun resolve(source: LumaDatabase.CalendarSource?, instant: ZonedDateTime): State {
        if (source == null) return State("LumaSchedule", "还没有课表", "打开应用导入课表", "", null)
        val now = instant.withZoneSameInstant(source.zone)
        val today = now.toLocalDate()
        val nextMidnight = today.plusDays(1).atStartOfDay(source.zone)
        val termStart = source.termStart
        val currentWeek = termStart?.let { ScheduleMath.teachingWeek(today, it, source.weekStartsOn) }
        val badge = if (currentWeek != null && currentWeek in 1..source.weekCount) "第 $currentWeek 周" else ""

        val resolver = ScheduleMath.TimeResolver(source.slots, source.meetings)
        val occurrences = ArrayList<Occurrence>()
        for (offset in 0..LOOKAHEAD_DAYS) {
            val date = today.plusDays(offset.toLong())
            val week = termStart?.let { ScheduleMath.teachingWeek(date, it, source.weekStartsOn) }
            if (week != null && week !in 1..source.weekCount) continue
            for (meeting in source.meetings) {
                if (meeting.day != ScheduleMath.weekdayOf(date)) continue
                if (week != null && !ScheduleMath.inWeek(meeting.weeksMask, week)) continue
                // Same times as the app shows, including estimated ones (marked with 约 below).
                val range = resolver.resolve(meeting)
                occurrences += Occurrence(meeting, date, range.startAt(date).atZone(source.zone), range.endAt(date).atZone(source.zone), range.estimated)
            }
        }
        occurrences.sortWith(compareBy<Occurrence> { it.start }.thenBy { it.meeting.name })

        val current = occurrences.firstOrNull { !it.start.isAfter(now) && it.end.isAfter(now) }
        if (current != null) {
            val approx = if (current.estimated) "约 " else ""
            return State(
                label = "正在上课 · 至 $approx${clock(current.end)}",
                title = current.meeting.name,
                meta = listOf(current.meeting.room, current.meeting.teacher).filter { it.isNotBlank() }.joinToString(" · ")
                    .ifBlank { "$approx${clock(current.start)}–${clock(current.end)}" },
                badge = badge,
                refreshAtMs = current.end.toInstant().toEpochMilli()
            )
        }
        val next = occurrences.firstOrNull { it.start.isAfter(now) }
        if (next != null) {
            val days = ChronoUnit.DAYS.between(today, next.date)
            val at = (if (next.estimated) "约 " else "") + clock(next.start)
            val label = when (days) {
                0L -> "下一节 · $at"
                1L -> "明天 $at"
                2L -> "后天 $at"
                in 3L..6L -> "${ScheduleMath.weekdayLabel(ScheduleMath.weekdayOf(next.date))} $at"
                else -> "${next.date.monthValue}月${next.date.dayOfMonth}日 $at"
            }
            val meta = listOf("${clock(next.start)}–${clock(next.end)}", next.meeting.room).filter { it.isNotBlank() }.joinToString(" · ")
            val refresh = if (next.start.isBefore(nextMidnight)) next.start else nextMidnight
            return State(label, next.meeting.name, meta, badge, refresh.toInstant().toEpochMilli())
        }
        val status = when {
            termStart != null && currentWeek != null && currentWeek < 1 ->
                ScheduleMath.weekStart(termStart, source.weekStartsOn).let { "学期从 ${it.monthValue}月${it.dayOfMonth}日 开始" }
            termStart != null && currentWeek != null && currentWeek > source.weekCount -> "本学期已结束"
            source.meetings.isEmpty() -> "课表里还没有课程"
            else -> "接下来两周没有课"
        }
        return State("下一节", "近期没有课程", status, badge, nextMidnight.toInstant().toEpochMilli())
    }

    private fun clock(time: ZonedDateTime) = "%02d:%02d".format(time.hour, time.minute)

    private const val LOOKAHEAD_DAYS = 14
}
