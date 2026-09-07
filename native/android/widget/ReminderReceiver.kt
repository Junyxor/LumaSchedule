package com.lumaschedule.app.widgets

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lumaschedule.app.MainActivity

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 1001)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "课程提醒"
        val body = intent.getStringExtra(EXTRA_BODY) ?: "下一节课即将开始"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            CHANNEL_ID,
            "课程提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "上课、调课和课前提醒"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)

        val launch = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(android.app.Notification.BigTextStyle().bigText(body))
            .setContentIntent(launch)
            .setAutoCancel(true)
            .setCategory(android.app.Notification.CATEGORY_REMINDER)
            .build()

        manager.notify(id, notification)
        context.getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(BootReceiver.key(id))
            .apply()
    }

    companion object {
        const val CHANNEL_ID = "luma_course_reminders"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
    }
}
