package com.lumaschedule.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import com.lumaschedule.app.widgets.BootReceiver
import com.lumaschedule.app.widgets.ReminderReceiver
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class CourseReminderEngine(private val context: Context) {
    private val dbFile = File(context.filesDir, "lumaschedule.sqlite")
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun sync(): JSONObject {
        if (!dbFile.exists()) return report(false, 0, 0, 0, 0)
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val settings = readSetting(db, SETTINGS_KEY)?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: JSONObject().put("enabled", false).put("offsetMinutes", 15)
            val enabled = settings.optBoolean("enabled", false)
            val offset = settings.optInt("offsetMinutes", 15).coerceIn(0, 180)
            val previous = readIds(db)

            if (!enabled) {
                val cancelled = previous.count { cancel(it) }
                saveIds(db, emptyList())
                return report(false, 0, 0, cancelled, 0)
            }

            val schedule = latestScheduleContext(db)
                ?: return report(true, 0, 0, previous.count { cancel(it) }, 0).also { saveIds(db, emptyList()) }
            val termStart = parseDate(schedule.startDate)
                ?: throw IllegalStateException("课表缺少有效的学期开始日期，暂时无法自动安排课程提醒。")
            val zone = ZoneId.of(schedule.timezone.ifBlank { "Asia/Shanghai" })
            val nowMs = System.currentTimeMillis()
            val desired = LinkedHashMap<Int, NativeReminder>()
            var skipped = 0

            db.rawQuery(
                "SELECT m.id, c.name, COALESCE(c.teacher,''), COALESCE(m.location,''), m.weekday, m.start_section, COALESCE(m.start_time,''), m.weeks_mask " +
                    "FROM courses c JOIN course_meetings m ON m.course_id=c.id WHERE c.schedule_id=? ORDER BY m.weekday,m.start_section,c.name",
                arrayOf(schedule.scheduleId)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val meetingId = cursor.getString(0)
                    val courseName = cursor.getString(1)
                    val teacher = cursor.getString(2)
                    val room = cursor.getString(3)
                    val weekday = cursor.getInt(4)
                    val startSection = cursor.getInt(5)
                    val explicitStart = cursor.getString(6)
                    val mask = cursor.getLong(7)
                    val startText = explicitStart.takeIf { it.isNotBlank() } ?: resolveSlot(schedule.sectionsJson, startSection)
                    val startTime = startText?.let(::parseTime)
                    if (startTime == null || weekday !in 1..7) {
                        skipped++
                        continue
                    }
                    val weeks = weeksFromMask(mask, schedule.weekCount)
                    for (week in weeks) {
                        val date = termStart.plusDays(((week - 1) * 7L) + weekday - 1L)
                        val startAt = LocalDateTime.of(date, startTime).atZone(zone).toInstant().toEpochMilli()
                        val triggerAt = startAt - offset * 60_000L
                        if (triggerAt <= nowMs) {
                            skipped++
                            continue
                        }
                        val id = stableId("$meetingId|$week|$startText|$offset|$courseName|$room|$teacher|$weekday")
                        var body = "$startText · 第 $week 周"
                        if (room.isNotBlank()) body += " · $room"
                        if (teacher.isNotBlank()) body += " · $teacher"
                        desired[id] = NativeReminder(id, triggerAt, "上课提醒 · $courseName", body)
                    }
                }
            }

            val desiredIds = desired.keys.toSet()
            val previousIds = previous.toSet()
            val stale = previousIds - desiredIds
            val cancelled = stale.count { cancel(it) }
            var scheduled = 0
            desired.forEach { (id, reminder) ->
                if (id !in previousIds && schedule(reminder)) scheduled++
            }
            saveIds(db, desiredIds.sorted())
            return report(true, desiredIds.size, scheduled, cancelled, skipped)
        } finally {
            db.close()
        }
    }

    private fun latestScheduleContext(db: SQLiteDatabase): ScheduleContext? = db.rawQuery(
        "SELECT s.id,t.start_date,t.week_count,COALESCE(t.timezone,'Asia/Shanghai'),COALESCE(ts.sections_json,'') " +
            "FROM schedules s JOIN terms t ON t.id=s.term_id LEFT JOIN time_schemes ts ON ts.id=s.time_scheme_id ORDER BY s.rowid DESC LIMIT 1",
        null
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else ScheduleContext(
            cursor.getString(0), cursor.getString(1), cursor.getInt(2).coerceIn(1, 64), cursor.getString(3), cursor.getString(4)
        )
    }

    private fun readSetting(db: SQLiteDatabase, key: String): String? = db.rawQuery(
        "SELECT value_json FROM settings WHERE key=? LIMIT 1", arrayOf(key)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun readIds(db: SQLiteDatabase): List<Int> {
        val raw = readSetting(db, IDS_KEY) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optInt(it).takeIf { id -> id > 0 } }
    }

    private fun saveIds(db: SQLiteDatabase, ids: List<Int>) {
        db.execSQL(
            "INSERT INTO settings(key,value_json) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value_json=excluded.value_json",
            arrayOf(IDS_KEY, JSONArray(ids).toString())
        )
    }

    private fun schedule(reminder: NativeReminder): Boolean {
        val pending = PendingIntent.getBroadcast(
            context,
            reminder.id,
            Intent(context, ReminderReceiver::class.java).apply {
                putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, reminder.id)
                putExtra(ReminderReceiver.EXTRA_TITLE, reminder.title)
                putExtra(ReminderReceiver.EXTRA_BODY, reminder.body)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAt, pending)
        val stored = JSONObject()
            .put("id", reminder.id)
            .put("triggerAtEpochMs", reminder.triggerAt)
            .put("title", reminder.title)
            .put("body", reminder.body)
        context.getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(BootReceiver.key(reminder.id), stored.toString()).apply()
        return true
    }

    private fun cancel(id: Int): Boolean {
        val pending = PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pending != null) {
            alarmManager.cancel(pending)
            pending.cancel()
        }
        context.getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(BootReceiver.key(id)).apply()
        return true
    }

    private fun weeksFromMask(mask: Long, weekCount: Int): List<Int> {
        if (mask == 0L) return (1..weekCount).toList()
        return (1..weekCount).filter { week -> (mask and (1L shl (week - 1))) != 0L }
    }

    private fun resolveSlot(raw: String, section: Int): String? {
        if (raw.isBlank()) return null
        val root: Any = runCatching { if (raw.trim().startsWith("[")) JSONArray(raw) else JSONObject(raw) }.getOrNull() ?: return null
        val slots = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("timeSlots") ?: root.optJSONArray("slots") ?: root.optJSONArray("sections")
            else -> null
        } ?: return null
        for (i in 0 until slots.length()) {
            val slot = slots.optJSONObject(i) ?: continue
            val number = slot.optInt("number", slot.optInt("section", slot.optInt("index", -1)))
            if (number != section) continue
            for (key in arrayOf("startTime", "start_time", "start")) {
                slot.optString(key).trim().takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return null
    }

    private fun parseDate(raw: String): LocalDate? {
        val normalized = raw.trim().take(10).replace('/', '-').replace('.', '-')
        return runCatching { LocalDate.parse(normalized) }.getOrNull()
    }

    private fun parseTime(raw: String): LocalTime? = runCatching {
        val text = raw.trim()
        if (text.length >= 5) LocalTime.parse(text.take(5)) else null
    }.getOrNull()

    private fun stableId(seed: String): Int {
        var hash = 0x811c9dc5u
        seed.encodeToByteArray().forEach { byte ->
            hash = hash xor byte.toUByte().toUInt()
            hash *= 0x01000193u
        }
        val id = (hash and 0x7fff_ffffu).toInt()
        return if (id == 0) 1 else id
    }

    private fun report(enabled: Boolean, future: Int, scheduled: Int, cancelled: Int, skipped: Int) = JSONObject()
        .put("enabled", enabled)
        .put("futureCount", future)
        .put("scheduledCount", scheduled)
        .put("cancelledCount", cancelled)
        .put("skippedCount", skipped)

    private data class ScheduleContext(
        val scheduleId: String,
        val startDate: String,
        val weekCount: Int,
        val timezone: String,
        val sectionsJson: String
    )

    private data class NativeReminder(val id: Int, val triggerAt: Long, val title: String, val body: String)

    companion object {
        private const val SETTINGS_KEY = "reminders.course.default"
        private const val IDS_KEY = "reminders.course.native_ids"
    }
}
