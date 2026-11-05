package com.lumaschedule.app

import android.os.Build
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.lumaschedule.app.data.DataTransferEngine
import com.lumaschedule.app.data.LumaDatabase
import com.lumaschedule.app.reminders.CourseReminderEngine
import com.lumaschedule.app.shiguang.ShiguangRepository
import com.lumaschedule.app.sync.WebDavClient
import com.lumaschedule.app.widgets.NextCourseWidgetProvider
import com.lumaschedule.app.widgets.ReminderReceiver
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService

/**
 * `window.LumaNative` for the bundled web UI. `request` is asynchronous: work
 * runs on [executor] and the result is delivered through
 * `window.__lumaNativeResolve(id, ok, json)`. The other methods are small
 * synchronous helpers for system chrome.
 */
class LumaBridge(
    private val activity: MainActivity,
    private val webView: WebView,
    private val executor: ExecutorService,
    private val launchStarted: Long
) {
    @Volatile private var uiReadyMs: Long = -1
    private val database = LumaDatabase.get(activity)
    private val reminders by lazy { CourseReminderEngine(activity) }
    private val shiguang by lazy { ShiguangRepository(activity.applicationContext) }
    private val transfer by lazy { DataTransferEngine(database) }
    private val webDav by lazy { WebDavClient() }

    @JavascriptInterface
    fun request(id: String, command: String, payload: String?) {
        if (id.length > 80 || command.length > 80) return
        executor.execute {
            val started = SystemClock.elapsedRealtime()
            runCatching { route(command, payload.orEmpty()) }
                .onSuccess { result ->
                    DiagnosticLog.record(activity, "INFO", "bridge.$command", "ok durationMs=${SystemClock.elapsedRealtime() - started}")
                    resolve(id, true, result)
                }
                .onFailure { error ->
                    DiagnosticLog.record(
                        activity,
                        "ERROR",
                        "bridge.$command",
                        "${error.javaClass.simpleName}: ${error.message.orEmpty()} durationMs=${SystemClock.elapsedRealtime() - started}"
                    )
                    resolve(id, false, JSONObject().put("message", error.message ?: "操作失败").toString())
                }
        }
    }

    @JavascriptInterface
    fun setBackEnabled(enabled: Boolean) = activity.setWebBackEnabled(enabled)

    @JavascriptInterface
    fun setSystemBarsDark(dark: Boolean) = activity.setSystemBarsDark(dark)

    @JavascriptInterface
    fun isSystemDark(): Boolean = activity.isSystemDark()

    @JavascriptInterface
    fun getInsets(): String = activity.insetsJson()

    fun markUiReady() {
        uiReadyMs = SystemClock.elapsedRealtime() - launchStarted
        DiagnosticLog.record(activity, "INFO", "ui.ready", "startupMs=$uiReadyMs")
    }

    /** Called when the app returns to the foreground. */
    fun onResume() {
        executor.execute {
            runCatching {
                if (database.remindersEnabled()) reminders.sync()
                NextCourseWidgetProvider.updateAll(activity)
            }.onFailure { DiagnosticLog.record(activity, "WARN", "lifecycle.refresh_failed", it.message.orEmpty()) }
        }
    }

    private fun route(command: String, raw: String): String {
        val args = raw.takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject()
        return when (command) {
            "get_bootstrap" -> JSONObject()
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("appearance", database.appearance() ?: JSONObject.NULL)
                .put("dbReady", true)
                .toString()

            "check_update" -> checkUpdate().toString()

            // Schedule
            "get_semester" -> database.semester().toString()
            "get_display_preferences" -> database.displayPreferences().toString()
            "save_display_preferences" -> database.saveDisplayPreferences(args.getJSONObject("preferences")).toString()
            "save_term_settings" -> changed { database.saveTermSettings(args.getJSONObject("term")) }.toString()
            "save_time_slots" -> changed { database.saveTimeSlots(args.getJSONArray("slots")) }.toString()
            "list_schedules" -> database.listSchedules().toString()
            "activate_schedule" -> changed { database.activateSchedule(args.getString("id")) }.let { NULL }
            "create_schedule" -> changed { database.createSchedule(args.getString("name")) }.let { NULL }
            "rename_schedule" -> database.renameSchedule(args.getString("id"), args.getString("name")).let { NULL }
            "delete_schedule" -> changed { database.deleteSchedule(args.getString("id")) }.let { NULL }
            "save_course" -> JSONObject().put("value", changed { database.saveCourse(args.getJSONObject("course")) }).toString()
            "delete_course" -> changed { database.deleteCourse(args.getString("id")) }.let { NULL }
            "save_appearance" -> database.saveAppearance(args.getJSONObject("appearance")).let { NULL }
            "migrate_legacy_credits" -> JSONObject().put("value", database.migrateLegacyCredits(args.optJSONObject("byName") ?: JSONObject())).toString()

            // Import
            "preview_import_bundle" -> database.previewImport(args.getJSONObject("bundle")).toString()
            "commit_import_bundle" -> changed { database.commitImport(args.getJSONObject("bundle"), args.optString("mode", "new")) }.toString()

            // Grades
            "get_grade_snapshot" -> database.gradeSnapshot().toString()
            "commit_grade_bundle" -> database.commitGradeBundle(args.getJSONObject("bundle")).toString()
            "delete_grade_records" -> JSONObject()
                .put("value", database.deleteGradeRecords(args.optString("term").takeIf { args.has("term") && !args.isNull("term") && it.isNotBlank() }))
                .toString()

            // Reminders & widget
            "get_reminder_settings" -> database.reminderSettings().toString()
            "save_reminder_settings" -> database.saveReminderSettings(args.getJSONObject("settings")).toString()
            "sync_reminders" -> {
                if (database.remindersEnabled() && !activity.ensureNotificationPermissionBlocking()) error("没有通知权限，无法安排上课提醒。")
                reminders.sync().toString()
            }
            "request_notification_permission" -> JSONObject().put("value", activity.ensureNotificationPermissionBlocking()).toString()
            "test_notification" -> JSONObject().put("value", sendTestNotification()).toString()
            "schedule_test_reminder" -> JSONObject()
                .put("value", activity.ensureNotificationPermissionBlocking() && reminders.scheduleTest(args.optLong("delayMs", 60_000L)))
                .toString()
            "get_exact_alarm_status" -> JSONObject()
                .put("required", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                .put("granted", reminders.canScheduleExactAlarms())
                .toString()
            "request_exact_alarm_access" -> JSONObject().put("value", activity.requestExactAlarmAccess()).toString()
            "refresh_widgets" -> NextCourseWidgetProvider.updateAll(activity).let { NULL }

            // University adapters
            "shiguang_list_schools" -> shiguang.listSchools(args.optString("query").takeIf { it.isNotBlank() }).toString()
            "shiguang_list_adapters" -> shiguang.listAdapters(args.getString("schoolId")).toString()
            "shiguang_start_import" -> shiguang.startImport(activity, args.getString("schoolId"), args.getString("adapterId")).toString()
            "shiguang_start_smart_import" -> shiguang.startSmartImport(activity, args.getString("url"), args.optString("schoolName")).toString()
            "shiguang_start_custom_import" -> shiguang.startCustomImport(activity, args.getString("url"), args.getString("family"), args.optString("schoolName")).toString()
            "shiguang_start_grade_capture" -> shiguang.startGradeCapture(activity, args.getString("url"), args.optString("institution")).toString()
            "shiguang_get_session" -> shiguang.session(args.getString("sessionId")).toString()
            "shiguang_close_session" -> shiguang.closeSession(args.getString("sessionId")).let { NULL }

            // Files
            "export_schedule_json" -> saveTextDocument("application/json", "lumaschedule-schedule.json", transfer.exportScheduleJson()).toString()
            "export_schedule_ics" -> saveTextDocument("text/calendar", "lumaschedule-schedule.ics", transfer.exportIcs()).toString()
            "export_full_backup" -> saveTextDocument("application/json", "lumaschedule-${backupDate()}.luma.json", transfer.exportFullBackup(BuildConfig.VERSION_NAME)).toString()
            "export_diagnostics" -> saveTextDocument("text/plain", DiagnosticLog.suggestedFileName(), diagnosticReport()).toString()
            "restore_full_backup" -> {
                val uri = activity.openDocumentBlocking(arrayOf("application/json", "application/octet-stream", "text/plain")) ?: return NULL
                val text = activity.contentResolver.openInputStream(uri)?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                    ?: error("无法读取备份文件。")
                changed { transfer.restoreFullBackup(text) }.toString()
            }

            // WebDAV
            "get_webdav_profile" -> (database.getSettingRaw(WEB_DAV_PROFILE_KEY)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: sanitizeWebDavProfile(JSONObject())).toString()
            "save_webdav_profile" -> database.setSettingRaw(WEB_DAV_PROFILE_KEY, sanitizeWebDavProfile(args.getJSONObject("profile")).toString()).let { NULL }
            "webdav_test" -> webDav.test(args.getJSONObject("credentials")).toString()
            "webdav_upload" -> {
                val backup = transfer.exportFullBackup(BuildConfig.VERSION_NAME)
                webDav.upload(args.getJSONObject("credentials"), backup).put("backup", transfer.backupSummary(backup)).toString()
            }
            "webdav_restore" -> {
                val (result, backup) = webDav.download(args.getJSONObject("credentials"))
                if (!result.optBoolean("ok") || backup == null) return result.toString()
                val summary = changed { transfer.restoreFullBackup(backup) }
                result.put("message", "已从云端恢复：${summary.optInt("courseCount")} 门课程。").put("backup", summary).toString()
            }

            else -> error("未知的原生命令：$command")
        }
    }

    /** Runs a schedule mutation, then keeps reminders and the widget in step with it. */
    private fun <T> changed(block: () -> T): T {
        val result = block()
        runCatching { if (database.remindersEnabled()) reminders.sync() }
            .onFailure { DiagnosticLog.record(activity, "WARN", "reminders.resync_failed", it.message.orEmpty()) }
        runCatching { NextCourseWidgetProvider.updateAll(activity) }
        return result
    }

    private fun diagnosticReport(): String =
        DiagnosticLog.buildReport(activity, BuildConfig.VERSION_NAME, uiReadyMs, runCatching { database.diagnosticSummary() }.getOrElse { JSONObject() })

    private fun sendTestNotification(): Boolean {
        if (!activity.ensureNotificationPermissionBlocking()) return false
        ReminderReceiver.notify(activity, 1001, "LumaSchedule · 测试通知", "通知工作正常，上课提醒会以这样的方式出现。")
        return true
    }

    private fun saveTextDocument(mimeType: String, suggestedName: String, text: String): Boolean {
        val uri = activity.createDocumentBlocking(mimeType, suggestedName) ?: return false
        activity.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.write(text.toByteArray(StandardCharsets.UTF_8))
            stream.flush()
        } ?: error("无法写入所选文件。")
        return true
    }

    private fun backupDate(): String = java.time.LocalDate.now().toString()

    private fun sanitizeWebDavProfile(input: JSONObject): JSONObject = JSONObject()
        .put("baseUrl", input.optString("baseUrl").trim())
        .put("username", input.optString("username").trim())
        .put("remotePath", input.optString("remotePath").trim().ifBlank { "LumaSchedule/lumaschedule-latest.luma.json" })

    // ------------------------------------------------------------------ updates

    private fun checkUpdate(): JSONObject {
        val current = BuildConfig.VERSION_NAME.removePrefix("v").trim()
        val includePrerelease = current.contains('-')
        val connection = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 10_000
            useCaches = false
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "LumaSchedule/$current")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("检查更新失败（HTTP $code）")
            val releases = JSONArray(connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() })
            var best: JSONObject? = null
            var bestVersion = ""
            for (index in 0 until releases.length()) {
                val release = releases.optJSONObject(index) ?: continue
                if (release.optBoolean("draft")) continue
                if (release.optBoolean("prerelease") && !includePrerelease) continue
                val version = release.optString("tag_name").removePrefix("v").trim()
                if (!version.matches(VERSION_PATTERN)) continue
                if (best == null || compareVersions(version, bestVersion) > 0) {
                    best = release
                    bestVersion = version
                }
            }
            val latest = best ?: error("GitHub 上还没有发布版本")
            return JSONObject()
                .put("currentVersion", current)
                .put("latestVersion", bestVersion)
                .put("updateAvailable", compareVersions(bestVersion, current) > 0)
                .put("releaseUrl", latest.optString("html_url"))
                .put("name", latest.optString("name"))
                .put("publishedAt", latest.optString("published_at"))
        } finally {
            connection.disconnect()
        }
    }

    /** Semantic-version comparison: 0.1.5 > 0.1.5-beta.4 > 0.1.5-beta.3. */
    private fun compareVersions(left: String, right: String): Int {
        fun split(value: String): Pair<List<Int>, List<String>> {
            val core = value.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val pre = if ('-' in value) value.substringAfter('-').split('.') else emptyList()
            return core to pre
        }
        val (leftCore, leftPre) = split(left)
        val (rightCore, rightPre) = split(right)
        for (index in 0 until maxOf(leftCore.size, rightCore.size, 3)) {
            val difference = leftCore.getOrElse(index) { 0 }.compareTo(rightCore.getOrElse(index) { 0 })
            if (difference != 0) return difference
        }
        if (leftPre.isEmpty() || rightPre.isEmpty()) return rightPre.size.compareTo(0) - leftPre.size.compareTo(0)
        for (index in 0 until maxOf(leftPre.size, rightPre.size)) {
            val a = leftPre.getOrNull(index) ?: return -1
            val b = rightPre.getOrNull(index) ?: return 1
            val difference = when {
                a.toIntOrNull() != null && b.toIntOrNull() != null -> a.toInt().compareTo(b.toInt())
                a.toIntOrNull() != null -> -1
                b.toIntOrNull() != null -> 1
                else -> a.compareTo(b)
            }
            if (difference != 0) return difference
        }
        return 0
    }

    private fun resolve(id: String, ok: Boolean, payload: String) {
        val script = "window.__lumaNativeResolve&&window.__lumaNativeResolve(${JSONObject.quote(id)},$ok,${JSONObject.quote(payload)});"
        activity.runOnUiThread { if (!activity.isDestroyed) webView.evaluateJavascript(script, null) }
    }

    companion object {
        private const val NULL = "null"
        private const val WEB_DAV_PROFILE_KEY = "sync.webdav.profile"
        private const val RELEASES_API = "https://api.github.com/repos/Junyxor/LumaSchedule/releases?per_page=20"
        private val VERSION_PATTERN = Regex("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?$")
    }
}
