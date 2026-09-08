package com.lumaschedule.app.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val editor = prefs.edit()
        prefs.all.forEach { (key, raw) ->
            val value = raw as? String ?: return@forEach
            runCatching {
                val json = JSONObject(value)
                val id = json.getInt("id")
                val triggerAt = json.getLong("triggerAtEpochMs")
                if (triggerAt <= System.currentTimeMillis()) {
                    editor.remove(key)
                    return@runCatching
                }
                val pending = PendingIntent.getBroadcast(
                    context,
                    id,
                    Intent(context, ReminderReceiver::class.java).apply {
                        putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, id)
                        putExtra(ReminderReceiver.EXTRA_TITLE, json.optString("title", "课程提醒"))
                        putExtra(ReminderReceiver.EXTRA_BODY, json.optString("body", "下一节课即将开始"))
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }.onFailure { editor.remove(key) }
        }
        editor.apply()
    }

    companion object {
        const val PREFS_NAME = "luma_reminders"
        fun key(id: Int) = "reminder_$id"
    }
}
