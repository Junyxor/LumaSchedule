package com.lumaschedule.app.widgets

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lumaschedule.app.DiagnosticLog
import com.lumaschedule.app.reminders.CourseReminderEngine

/**
 * Alarms are wiped on reboot and become wrong after clock or time-zone changes.
 * Rebuild the reminder window and refresh the widget from SQLite instead of
 * replaying stale copies.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        val pending = goAsync()
        Thread {
            try {
                val report = CourseReminderEngine(context).sync()
                NextCourseWidgetProvider.updateAll(context)
                DiagnosticLog.record(context, "INFO", "reminders.restore", "action=${intent.action} future=${report.optInt("futureCount")}")
            } catch (error: Throwable) {
                DiagnosticLog.record(context, "WARN", "reminders.restore_failed", "action=${intent.action} ${error.message.orEmpty()}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        )
    }
}
