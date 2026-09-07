package com.lumaschedule.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.lumaschedule.app.data.DataTransferEngine
import com.lumaschedule.app.data.LumaDatabase
import com.lumaschedule.app.reminders.CourseReminderEngine
import com.lumaschedule.app.shiguang.ShiguangRepository
import com.lumaschedule.app.sync.WebDavClient
import com.lumaschedule.app.widgets.BootReceiver
import com.lumaschedule.app.widgets.NextCourseWidgetProvider
import com.lumaschedule.app.widgets.ReminderReceiver
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService

class LumaBridge(
    private val activity: MainActivity,
    private val webView: WebView,
    private val database: LumaDatabase,
    private val executor: ExecutorService,
    private val launchStarted: Long
) {
    @Volatile private var uiReadyMs: Long = -1
    private val reminderEngine by lazy(LazyThreadSafetyMode.NONE) {
        CourseReminderEngine(activity.applicationContext)
    }
    private val shiguang by lazy(LazyThreadSafetyMode.NONE) {
        ShiguangRepository(activity.applicationContext)
    }
    private val transfer by lazy(LazyThreadSafetyMode.NONE) {
        DataTransferEngine(File(activity.filesDir, "lumaschedule.sqlite"))
    }
    private val webDav by lazy(LazyThreadSafetyMode.NONE) { WebDavClient() }

    @JavascriptInterface
    fun request(id: String, command: String, payload: String?) {
        if (id.length > 80 || command.length > 80) return
        executor.execute {
            runCatching { route(command, payload.orEmpty()) }
                .onSuccess { resolve(id, true, it) }
                .onFailure {
                    resolve(
                        id,
                        false,
                        JSONObject().put("message", it.message ?: "Native command failed").toString()
                    )
                }
        }
    }

    fun markUiReady() {
        uiReadyMs = SystemClock.elapsedRealtime() - launchStarted
        activity.runOnUiThread {
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('luma-native-ready',{detail:{startupMs:$uiReadyMs}}));",
                null
            )
        }
    }

    private fun route(command: String, raw: String): String {
        val args = raw.takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject()
        return when (command) {
            "get_bootstrap" -> {
                val glass = database.getSettingRaw("appearance.glass")
                JSONObject()
                    .put("appVersion", BuildConfig.VERSION_NAME)
                    .put(
                        "glassSettings",
                        glass?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject.NULL
                    )
                    .put("dbReady", true)
                    .put("nativeCore", true)
                    .put("startupMs", uiReadyMs)
                    .toString()
            }

            "get_schedule_snapshot" -> database.getScheduleSnapshot().toString()

            "save_schedule_course" -> {
                val id = database.saveScheduleCourse(args.getJSONObject("course"))
                resyncRemindersIfEnabled()
                JSONObject().put("value", id).toString()
            }

            "delete_schedule_course" -> {
                database.deleteScheduleCourse(args.getString("id"))
                resyncRemindersIfEnabled()
                "null"
            }

            "save_glass_settings" -> {
                database.setSettingRaw("appearance.glass", args.getJSONObject("settings").toString())
                "null"
            }

            "commit_import_bundle" -> {
                val result = database.commitImport(args.getJSONObject("bundle"))
                resyncRemindersIfEnabled()
                result.toString()
            }

            "update_widget_snapshot" -> JSONObject()
                .put("value", updateWidget(args.getJSONObject("snapshot")))
                .toString()

            "test_notification" -> JSONObject()
                .put("value", sendTestNotification())
                .toString()

            "request_notification_permission" -> JSONObject()
                .put("value", activity.ensureNotificationPermissionBlocking())
                .toString()

            "schedule_native_reminder" -> JSONObject()
                .put("value", scheduleReminder(args.getJSONObject("reminder")))
                .toString()

            "cancel_native_reminder" -> JSONObject()
                .put("value", cancelReminder(args.getJSONObject("reminder").getInt("id")))
                .toString()

            "get_course_reminder_settings" -> {
                database.getSettingRaw("reminders.course.default")
                    ?: JSONObject()
                        .put("enabled", false)
                        .put("offsetMinutes", 15)
                        .toString()
            }

            "save_course_reminder_settings" -> {
                val input = args.getJSONObject("settings")
                val normalized = JSONObject()
                    .put("enabled", input.optBoolean("enabled", false))
                    .put("offsetMinutes", input.optInt("offsetMinutes", 15).coerceIn(0, 180))
                database.setSettingRaw("reminders.course.default", normalized.toString())
                normalized.toString()
            }

            "sync_course_reminders" -> {
                val settings = database.getSettingRaw("reminders.course.default")
                    ?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (settings?.optBoolean("enabled", false) == true &&
                    !activity.ensureNotificationPermissionBlocking()
                ) {
                    error("没有获得系统通知权限，无法安排课程提醒。")
                }
                reminderEngine.sync().toString()
            }

            "shiguang_list_schools" -> shiguang
                .listSchools(args.optString("query").takeIf { it.isNotBlank() })
                .toString()

            "shiguang_list_adapters" -> shiguang
                .listAdapters(args.getString("schoolId"))
                .toString()

            "shiguang_start_import" -> shiguang
                .startImport(
                    activity,
                    args.getString("schoolId"),
                    args.getString("adapterId")
                )
                .toString()

            "shiguang_get_session" -> shiguang
                .session(args.getString("sessionId"))
                .toString()

            "shiguang_close_session" -> {
                shiguang.closeSession(args.getString("sessionId"))
                "null"
            }

            "export_latest_schedule_json_to_file" -> saveTextDocument(
                "application/json",
                "lumaschedule-schedule.json",
                transfer.exportCanonicalJson()
            ).toString()

            "export_latest_schedule_ics_to_file" -> saveTextDocument(
                "text/calendar",
                "lumaschedule-schedule.ics",
                transfer.exportIcs()
            ).toString()

            "export_full_backup_to_file" -> saveTextDocument(
                "application/json",
                "lumaschedule-latest.luma.json",
                transfer.exportFullBackup(BuildConfig.VERSION_NAME)
            ).toString()

            "restore_full_backup_from_file" -> {
                val uri = activity.openDocumentBlocking(
                    arrayOf("application/json", "application/octet-stream", "text/plain")
                ) ?: return "null"
                val text = activity.contentResolver.openInputStream(uri)
                    ?.bufferedReader(StandardCharsets.UTF_8)
                    ?.use { it.readText() }
                    ?: error("无法读取备份文件。")
                val summary = transfer.restoreFullBackup(text)
                resyncRemindersIfEnabled()
                summary.toString()
            }

            "get_webdav_profile" -> {
                database.getSettingRaw(WEB_DAV_PROFILE_KEY)
                    ?: JSONObject()
                        .put("baseUrl", "")
                        .put("username", "")
                        .put("remotePath", "LumaSchedule/lumaschedule-latest.luma.json")
                        .toString()
            }

            "save_webdav_profile" -> {
                val profile = sanitizeWebDavProfile(args.getJSONObject("profile"))
                database.setSettingRaw(WEB_DAV_PROFILE_KEY, profile.toString())
                "null"
            }

            "webdav_test" -> webDav
                .test(args.getJSONObject("credentials"))
                .toString()

            "webdav_upload_backup" -> {
                val credentials = args.getJSONObject("credentials")
                val backup = transfer.exportFullBackup(BuildConfig.VERSION_NAME)
                val result = webDav.upload(credentials, backup)
                result.put("backup", transfer.backupSummary(backup)).toString()
            }

            "webdav_restore_backup" -> {
                val credentials = args.getJSONObject("credentials")
                val (result, backup) = webDav.download(credentials)
                if (!result.optBoolean("ok") || backup == null) return result.toString()
                val summary = transfer.restoreFullBackup(backup)
                resyncRemindersIfEnabled()
                result
                    .put("message", "云端完整备份已恢复。")
                    .put("backup", summary)
                    .toString()
            }

            else -> error("Native command not implemented yet: $command")
        }
    }

    private fun saveTextDocument(mimeType: String, suggestedName: String, text: String): Boolean {
        val uri = activity.createDocumentBlocking(mimeType, suggestedName) ?: return false
        activity.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.write(text.toByteArray(StandardCharsets.UTF_8))
            stream.flush()
        } ?: error("无法写入选择的文件。")
        return true
    }

    private fun sanitizeWebDavProfile(input: JSONObject): JSONObject = JSONObject()
        .put("baseUrl", input.optString("baseUrl").trim())
        .put("username", input.optString("username").trim())
        .put(
            "remotePath",
            input.optString("remotePath", "LumaSchedule/lumaschedule-latest.luma.json").trim()
                .ifBlank { "LumaSchedule/lumaschedule-latest.luma.json" }
        )

    private fun resyncRemindersIfEnabled() {
        val enabled = database.getSettingRaw("reminders.course.default")
            ?.let { runCatching { JSONObject(it).optBoolean("enabled", false) }.getOrDefault(false) }
            ?: false
        if (enabled) runCatching { reminderEngine.sync() }
    }

    private fun updateWidget(snapshot: JSONObject): Boolean {
        val prefs = activity.getSharedPreferences("luma_widget", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("next_course_name", snapshot.optString("courseName", "暂无课程"))
            .putString(
                "next_course_meta",
                snapshot.optString("courseMeta", "打开 LumaSchedule 查看课表")
            )
            .putString("next_course_countdown", snapshot.optString("countdown", "--"))
            .apply()

        val manager = AppWidgetManager.getInstance(activity)
        val component = ComponentName(activity, NextCourseWidgetProvider::class.java)
        val ids = manager.getAppWidgetIds(component)
        if (ids.isNotEmpty()) {
            activity.sendBroadcast(
                Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    this.component = component
                }
            )
        }
        return true
    }

    private fun sendTestNotification(): Boolean {
        if (!activity.ensureNotificationPermissionBlocking()) return false
        ReminderReceiver().onReceive(
            activity,
            Intent(activity, ReminderReceiver::class.java).apply {
                putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, 1001)
                putExtra(ReminderReceiver.EXTRA_TITLE, "LumaSchedule")
                putExtra(
                    ReminderReceiver.EXTRA_BODY,
                    "测试通知发送成功。课程提醒可以正常显示。"
                )
            }
        )
        return true
    }

    private fun scheduleReminder(reminder: JSONObject): Boolean {
        if (!activity.ensureNotificationPermissionBlocking()) return false
        val id = reminder.getInt("id")
        val triggerAt = reminder.getLong("triggerAtEpochMs")
        if (triggerAt <= System.currentTimeMillis()) return false
        val title = reminder.optString("title", "课程提醒")
        val body = reminder.optString("body", "下一节课即将开始")
        val pending = PendingIntent.getBroadcast(
            activity,
            id,
            Intent(activity, ReminderReceiver::class.java).apply {
                putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, id)
                putExtra(ReminderReceiver.EXTRA_TITLE, title)
                putExtra(ReminderReceiver.EXTRA_BODY, body)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val manager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        val stored = JSONObject()
            .put("id", id)
            .put("triggerAtEpochMs", triggerAt)
            .put("title", title)
            .put("body", body)
        activity.getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(BootReceiver.key(id), stored.toString())
            .apply()
        return true
    }

    private fun cancelReminder(id: Int): Boolean {
        val pending = PendingIntent.getBroadcast(
            activity,
            id,
            Intent(activity, ReminderReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pending != null) {
            val manager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            manager.cancel(pending)
            pending.cancel()
        }
        activity.getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(BootReceiver.key(id))
            .apply()
        return true
    }

    private fun resolve(id: String, ok: Boolean, payload: String) {
        val script = "window.__lumaNativeResolve(${JSONObject.quote(id)},${if (ok) "true" else "false"},${JSONObject.quote(payload)});"
        activity.runOnUiThread { webView.evaluateJavascript(script, null) }
    }

    companion object {
        private const val WEB_DAV_PROFILE_KEY = "sync.webdav.profile"
    }
}
