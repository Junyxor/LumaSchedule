package com.lumaschedule.app

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import com.lumaschedule.app.widgets.NextCourseWidgetProvider
import com.lumaschedule.app.widgets.BootReceiver
import com.lumaschedule.app.widgets.ReminderReceiver
import com.lumaschedule.app.shiguang.ShiguangImportActivity
import org.json.JSONObject

@InvokeArg
class WidgetSnapshotArgs {
    var courseName: String? = null
    var courseMeta: String? = null
    var countdown: String? = null
}

@InvokeArg
class ReminderArgs {
    var id: Int = 0
    var triggerAtEpochMs: Long = 0
    var title: String? = null
    var body: String? = null
}

@InvokeArg
class CancelReminderArgs { var id: Int = 0 }

@InvokeArg
class ShiguangStartArgs {
    var sessionId: String? = null
    var importUrl: String? = null
    var adapterScript: String? = null
    var allowedHostsJson: String? = null
    var adapterName: String? = null
    var schoolName: String? = null
    var insecureTransport: Boolean = false
}

@InvokeArg
class ShiguangResultArgs { var sessionId: String? = null }

@TauriPlugin
class ScheduleNativePlugin(private val activity: Activity) : Plugin(activity) {
    @Command
    fun updateWidget(invoke: Invoke) {
        val args = invoke.parseArgs(WidgetSnapshotArgs::class.java)
        activity.getSharedPreferences("luma_widget", Context.MODE_PRIVATE).edit()
            .putString("next_course_name", args.courseName ?: "下一节课程")
            .putString("next_course_meta", args.courseMeta ?: "打开 LumaSchedule 查看课表")
            .putString("next_course_countdown", args.countdown ?: "--").apply()
        val manager = AppWidgetManager.getInstance(activity)
        val component = ComponentName(activity, NextCourseWidgetProvider::class.java)
        val widgetIds = manager.getAppWidgetIds(component)
        val updateIntent = Intent(activity, NextCourseWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds)
        }
        activity.sendBroadcast(updateIntent)
        val result = JSObject(); result.put("updated", true); result.put("widgetCount", widgetIds.size); invoke.resolve(result)
    }

    @Command
    fun scheduleReminder(invoke: Invoke) {
        val args = invoke.parseArgs(ReminderArgs::class.java)
        if (args.triggerAtEpochMs <= System.currentTimeMillis()) { invoke.reject("triggerAtEpochMs must be in the future"); return }
        val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = reminderPendingIntent(args.id, args.title, args.body)
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, args.triggerAtEpochMs, pendingIntent)
        val stored = JSONObject().put("id", args.id).put("triggerAtEpochMs", args.triggerAtEpochMs).put("title", args.title ?: "课程提醒").put("body", args.body ?: "下一节课即将开始")
        activity.getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE).edit().putString(BootReceiver.key(args.id), stored.toString()).apply()
        val result = JSObject(); result.put("scheduled", true); result.put("id", args.id); invoke.resolve(result)
    }

    @Command
    fun cancelReminder(invoke: Invoke) {
        val args = invoke.parseArgs(CancelReminderArgs::class.java)
        val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(reminderPendingIntent(args.id, null, null))
        activity.getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE).edit().remove(BootReceiver.key(args.id)).apply()
        val result = JSObject(); result.put("cancelled", true); result.put("id", args.id); invoke.resolve(result)
    }

    @Command
    fun startShiguangImport(invoke: Invoke) {
        val args = invoke.parseArgs(ShiguangStartArgs::class.java)
        val sessionId = args.sessionId.orEmpty(); val importUrl = args.importUrl.orEmpty(); val adapterScript = args.adapterScript.orEmpty(); val allowedHostsJson = args.allowedHostsJson.orEmpty()
        if (sessionId.isBlank() || importUrl.isBlank() || adapterScript.isBlank()) { invoke.reject("Missing Shiguang import parameters"); return }
        val prefs = activity.getSharedPreferences(ShiguangImportActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(ShiguangImportActivity.key(sessionId, "status"), "running").putString(ShiguangImportActivity.key(sessionId, "message"), "正在打开教务系统登录页。").putString(ShiguangImportActivity.key(sessionId, "adapter_name"), args.adapterName.orEmpty()).putString(ShiguangImportActivity.key(sessionId, "school_name"), args.schoolName.orEmpty()).apply()
        val intent = Intent(activity, ShiguangImportActivity::class.java).apply {
            putExtra(ShiguangImportActivity.EXTRA_SESSION_ID, sessionId); putExtra(ShiguangImportActivity.EXTRA_IMPORT_URL, importUrl); putExtra(ShiguangImportActivity.EXTRA_ADAPTER_SCRIPT, adapterScript); putExtra(ShiguangImportActivity.EXTRA_ALLOWED_HOSTS_JSON, allowedHostsJson); putExtra(ShiguangImportActivity.EXTRA_ADAPTER_NAME, args.adapterName.orEmpty()); putExtra(ShiguangImportActivity.EXTRA_SCHOOL_NAME, args.schoolName.orEmpty()); putExtra(ShiguangImportActivity.EXTRA_INSECURE_TRANSPORT, args.insecureTransport)
        }
        activity.startActivity(intent)
        val result = JSObject(); result.put("started", true); invoke.resolve(result)
    }

    @Command
    fun clearShiguangSession(invoke: Invoke) {
        val args = invoke.parseArgs(ShiguangResultArgs::class.java); val sessionId = args.sessionId.orEmpty()
        if (sessionId.isBlank()) { invoke.reject("Missing Shiguang session id"); return }
        val prefs = activity.getSharedPreferences(ShiguangImportActivity.PREFS_NAME, Context.MODE_PRIVATE); val prefix = "$sessionId."; val editor = prefs.edit(); prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove); editor.apply()
        val result = JSObject(); result.put("cleared", true); invoke.resolve(result)
    }

    @Command
    fun getShiguangResult(invoke: Invoke) {
        val args = invoke.parseArgs(ShiguangResultArgs::class.java); val sessionId = args.sessionId.orEmpty()
        if (sessionId.isBlank()) { invoke.reject("Missing Shiguang session id"); return }
        val prefs = activity.getSharedPreferences(ShiguangImportActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val result = JSObject()
        result.put("status", prefs.getString(ShiguangImportActivity.key(sessionId, "status"), "running")); result.put("message", prefs.getString(ShiguangImportActivity.key(sessionId, "message"), null)); result.put("coursesJson", prefs.getString(ShiguangImportActivity.key(sessionId, "courses"), null)); result.put("timeSlotsJson", prefs.getString(ShiguangImportActivity.key(sessionId, "time_slots"), null)); result.put("configJson", prefs.getString(ShiguangImportActivity.key(sessionId, "config"), null)); result.put("adapterName", prefs.getString(ShiguangImportActivity.key(sessionId, "adapter_name"), null)); result.put("schoolName", prefs.getString(ShiguangImportActivity.key(sessionId, "school_name"), null)); invoke.resolve(result)
    }

    private fun reminderPendingIntent(id: Int, title: String?, body: String?): PendingIntent {
        val intent = Intent(activity, ReminderReceiver::class.java).apply { putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, id); if (title != null) putExtra(ReminderReceiver.EXTRA_TITLE, title); if (body != null) putExtra(ReminderReceiver.EXTRA_BODY, body) }
        return PendingIntent.getBroadcast(activity, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
