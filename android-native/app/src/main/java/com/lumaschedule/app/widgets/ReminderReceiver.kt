package com.lumaschedule.app.widgets

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lumaschedule.app.DiagnosticLog
import com.lumaschedule.app.MainActivity
import com.lumaschedule.app.R
import com.lumaschedule.app.reminders.CourseReminderEngine

/** Shows a course reminder, then tops up the rolling reminder window in the background. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MAINTAIN) {
            val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 1001)
            notify(
                context,
                id,
                intent.getStringExtra(EXTRA_TITLE) ?: "上课提醒",
                intent.getStringExtra(EXTRA_BODY) ?: "下一节课即将开始"
            )
            DiagnosticLog.record(context, "INFO", "reminders.fired", "id=$id")
        }
        val pending = goAsync()
        Thread {
            try {
                CourseReminderEngine(context).sync()
                NextCourseWidgetProvider.updateAll(context)
            } catch (error: Throwable) {
                DiagnosticLog.record(context, "WARN", "reminders.refill_failed", error.message.orEmpty())
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val CHANNEL_ID = "luma_course_reminders"
        const val ACTION_REMIND = "com.lumaschedule.app.action.COURSE_REMINDER"
        const val ACTION_MAINTAIN = "com.lumaschedule.app.action.REMINDER_MAINTENANCE"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"

        fun ensureChannel(context: Context): NotificationManager {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "上课提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "上课前的提醒通知"
                        enableVibration(true)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    }
                )
            }
            return manager
        }

        fun notify(context: Context, id: Int, title: String, body: String) {
            val manager = ensureChannel(context)
            val launch = PendingIntent.getActivity(
                context,
                0,
                MainActivity.launcherIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_luma)
                .setColor(context.getColor(R.color.luma_accent))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(launch)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(true)
                .build()
            runCatching { manager.notify(id, notification) }
        }
    }
}
