package com.lumaschedule.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.lumaschedule.app.DiagnosticLog
import com.lumaschedule.app.data.LumaDatabase
import com.lumaschedule.app.data.ScheduleMath
import com.lumaschedule.app.widgets.ReminderReceiver
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.ZonedDateTime

/**
 * Keeps a rolling window of course reminders armed in AlarmManager.
 *
 * Only the next [HORIZON_DAYS] days are scheduled (capped at [MAX_ALARMS]) so a
 * full semester never hits Android's ~500 alarms-per-app limit. The window is
 * refreshed whenever a reminder fires, on boot/update/time change, when the app
 * opens, and by a low-priority maintenance alarm. Scheduled ids live in
 * SharedPreferences rather than SQLite so restoring a backup from another
 * device can never orphan alarms that are armed on this one.
 */
class CourseReminderEngine(context: Context) {
    private val context = context.applicationContext
    private val database = LumaDatabase.get(this.context)
    private val alarmManager = this.context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Receivers and the bridge each create an engine, so serialise on a process-wide lock. */
    fun sync(): JSONObject = synchronized(LOCK) { syncLocked() }

    private fun syncLocked(): JSONObject {
        cleanUpLegacyAlarms()
        val settings = database.reminderSettings()
        val enabled = settings.optBoolean("enabled", false)
        val offset = settings.optInt("offsetMinutes", 15)
        val previous = trackedIds()

        if (!enabled) {
            previous.forEach(::cancel)
            saveTracked(emptySet())
            cancelMaintenance()
            return report(false, 0, 0, previous.size, 0, 0, null)
        }

        val source = database.calendarSource()
        if (source == null || source.termStart == null) {
            previous.forEach(::cancel)
            saveTracked(emptySet())
            scheduleMaintenance()
            return report(true, 0, 0, previous.size, 0, 0, if (source == null) "noSchedule" else "missingTermStart")
        }

        val now = ZonedDateTime.now(source.zone)
        val horizon = now.plusDays(HORIZON_DAYS)
        val resolver = ScheduleMath.TimeResolver(source.slots, source.meetings)
        val desired = ArrayList<Reminder>()
        var estimated = 0
        for (meeting in source.meetings) {
            // Same times as the app shows; estimated ones are flagged in the notification and in Settings.
            val range = resolver.resolve(meeting)
            if (range.estimated) estimated++
            var date = now.toLocalDate()
            while (!date.isAfter(horizon.toLocalDate())) {
                if (ScheduleMath.weekdayOf(date) == meeting.day) {
                    val week = ScheduleMath.teachingWeek(date, source.termStart, source.weekStartsOn)
                    if (week in 1..source.weekCount && ScheduleMath.inWeek(meeting.weeksMask, week)) {
                        val startAt = range.startAt(date).atZone(source.zone)
                        val triggerAt = startAt.minusMinutes(offset.toLong())
                        if (triggerAt.isAfter(now) && !triggerAt.isAfter(horizon)) {
                            desired += Reminder(
                                id = stableId("${meeting.id}|$date|${ScheduleMath.clock(range.startMinute)}|$offset"),
                                triggerAtMs = triggerAt.toInstant().toEpochMilli(),
                                title = title(meeting.name, offset),
                                body = body(meeting, range, week)
                            )
                        }
                    }
                }
                date = date.plusDays(1)
            }
        }
        val chosen = desired.sortedBy { it.triggerAtMs }.distinctBy { it.id }.take(MAX_ALARMS)
        val chosenIds = chosen.map { it.id }.toSet()
        val stale = previous - chosenIds
        stale.forEach(::cancel)
        var scheduled = 0
        chosen.forEach { if (schedule(it)) scheduled++ }
        saveTracked(chosenIds)
        scheduleMaintenance()
        val exact = canScheduleExactAlarms()
        DiagnosticLog.record(
            context,
            "INFO",
            "reminders.sync",
            "future=${chosen.size} scheduled=$scheduled cancelled=${stale.size} estimated=$estimated exact=$exact"
        )
        return report(true, chosen.size, scheduled, stale.size, 0, estimated, null)
    }

    /** One-off notification a minute from now, used by Settings to verify background delivery. */
    fun scheduleTest(delayMs: Long): Boolean {
        val reminder = Reminder(TEST_ID, System.currentTimeMillis() + delayMs.coerceIn(5_000L, 3_600_000L), "LumaSchedule · 测试提醒", "后台提醒工作正常，上课前会这样通知你。")
        return schedule(reminder)
    }

    private fun title(name: String, offset: Int) = if (offset <= 0) "$name · 现在上课" else "$name · ${offset} 分钟后上课"

    private fun body(meeting: ScheduleMath.Meeting, range: ScheduleMath.TimeRange, week: Int): String = buildList {
        add(if (range.estimated) "${range.label}（估算时间）" else range.label)
        if (meeting.room.isNotBlank()) add(meeting.room)
        if (meeting.teacher.isNotBlank()) add(meeting.teacher)
        add("第 $week 周")
    }.joinToString(" · ")

    private fun schedule(reminder: Reminder): Boolean {
        val pending = PendingIntent.getBroadcast(
            context,
            reminder.id,
            Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_REMIND
                putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, reminder.id)
                putExtra(ReminderReceiver.EXTRA_TITLE, reminder.title)
                putExtra(ReminderReceiver.EXTRA_BODY, reminder.body)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val scheduled = runCatching {
            if (canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMs, pending)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMs, pending)
            }
        }.recoverCatching {
            // The exact-alarm permission can be revoked between the check and the call.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMs, pending)
        }.isSuccess
        if (!scheduled) DiagnosticLog.record(context, "WARN", "reminders.schedule_failed", "id=${reminder.id}")
        return scheduled
    }

    private fun cancel(id: Int) {
        // Builds before the rolling window registered alarms without an action; cancel both shapes.
        for (action in listOf(ReminderReceiver.ACTION_REMIND, null)) {
            val intent = Intent(context, ReminderReceiver::class.java).apply { if (action != null) this.action = action }
            PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { pending ->
                alarmManager.cancel(pending)
                pending.cancel()
            }
        }
    }

    private fun scheduleMaintenance() {
        val pending = maintenanceIntent(PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val at = System.currentTimeMillis() + Duration.ofDays(MAINTENANCE_DAYS).toMillis()
        runCatching { alarmManager.set(AlarmManager.RTC, at, pending) }
    }

    private fun cancelMaintenance() {
        maintenanceIntent(PendingIntent.FLAG_NO_CREATE)?.let { pending ->
            alarmManager.cancel(pending)
            pending.cancel()
        }
    }

    private fun maintenanceIntent(flag: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        MAINTENANCE_REQUEST,
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_MAINTAIN),
        flag or PendingIntent.FLAG_IMMUTABLE
    )

    private fun trackedIds(): Set<Int> = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()

    private fun saveTracked(ids: Set<Int>) {
        prefs.edit().putStringSet(KEY_IDS, ids.map(Int::toString).toSet()).apply()
    }

    /** Cancels alarms armed by builds that tracked ids in SQLite and the old boot-restore preferences. */
    private fun cleanUpLegacyAlarms() {
        if (prefs.getBoolean(KEY_LEGACY_DONE, false)) return
        val ids = HashSet<Int>()
        database.getSettingRaw(LEGACY_IDS_KEY)?.let { raw ->
            runCatching { JSONArray(raw) }.getOrNull()?.let { array -> for (index in 0 until array.length()) array.optInt(index).takeIf { it != 0 }?.let(ids::add) }
        }
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        legacy.all.keys.forEach { key -> key.removePrefix("reminder_").toIntOrNull()?.let(ids::add) }
        ids.forEach(::cancel)
        legacy.edit().clear().apply()
        database.setSettingRaw(LEGACY_IDS_KEY, "[]")
        prefs.edit().putBoolean(KEY_LEGACY_DONE, true).apply()
    }

    fun canScheduleExactAlarms(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun stableId(seed: String): Int {
        var hash = 0x811c9dc5u
        seed.encodeToByteArray().forEach { byte ->
            hash = hash xor byte.toUByte().toUInt()
            hash *= 0x01000193u
        }
        val id = (hash and 0x7fff_ffffu).toInt()
        return if (id == 0) 1 else id
    }

    private fun report(enabled: Boolean, future: Int, scheduled: Int, cancelled: Int, skipped: Int, estimated: Int, problem: String?) = JSONObject()
        .put("enabled", enabled)
        .put("futureCount", future)
        .put("scheduledCount", scheduled)
        .put("cancelledCount", cancelled)
        .put("skippedCount", skipped)
        .put("estimatedCount", estimated)
        .put("horizonDays", HORIZON_DAYS)
        .put("problem", problem ?: JSONObject.NULL)
        .put("exactAlarmGranted", canScheduleExactAlarms())

    private data class Reminder(val id: Int, val triggerAtMs: Long, val title: String, val body: String)

    companion object {
        private val LOCK = Any()
        const val HORIZON_DAYS = 14L
        private const val MAX_ALARMS = 160
        private const val MAINTENANCE_DAYS = 2L
        private const val MAINTENANCE_REQUEST = -4100
        const val TEST_ID = -1002
        private const val PREFS_NAME = "luma_reminder_engine"
        private const val KEY_IDS = "scheduled_ids"
        private const val KEY_LEGACY_DONE = "legacy_cleanup_done"
        private const val LEGACY_PREFS = "luma_reminders"
        private const val LEGACY_IDS_KEY = "reminders.course.native_ids"
    }
}
