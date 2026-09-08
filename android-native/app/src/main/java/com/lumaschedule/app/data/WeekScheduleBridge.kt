package com.lumaschedule.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File

/**
 * Read-only bridge used by the week view.
 *
 * The normal app bootstrap only exposes the current teaching week because Today
 * does not need the entire semester. The week screen, however, must switch weeks
 * without reloading the app. This bridge reads the latest schedule once when the
 * week screen opens and lets the Svelte side filter by week entirely in memory.
 */
class WeekScheduleBridge(context: Context) : Closeable {
    private val lock = Any()
    private val dbFile = File(context.filesDir, "lumaschedule.sqlite")
    private val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    @JavascriptInterface
    fun snapshot(): String = synchronized(lock) {
        val schedule = db.rawQuery(
            "SELECT s.id, t.name, t.start_date, t.week_count, COALESCE(ts.sections_json, '') " +
                "FROM schedules s JOIN terms t ON t.id=s.term_id " +
                "LEFT JOIN time_schemes ts ON ts.id=s.time_scheme_id " +
                "ORDER BY s.rowid DESC LIMIT 1",
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else ScheduleInfo(
                id = cursor.getString(0),
                termName = cursor.getString(1),
                termStart = cursor.getString(2),
                weekCount = cursor.getInt(3).coerceIn(1, 64),
                sectionsJson = cursor.getString(4)
            )
        }

        if (schedule == null) {
            return@synchronized JSONObject()
                .put("courses", JSONArray())
                .put("hasSchedule", false)
                .toString()
        }

        val slots = parseSlots(schedule.sectionsJson)
        val creditIndex = readCourseCreditIndex()
        val courses = JSONArray()
        db.rawQuery(
            "SELECT m.id, c.name, COALESCE(c.teacher,''), COALESCE(m.location,''), " +
                "COALESCE(m.start_time,''), COALESCE(m.end_time,''), m.weekday, c.color_token, " +
                "m.start_section, m.end_section, m.weeks_mask " +
                "FROM courses c JOIN course_meetings m ON m.course_id=c.id " +
                "WHERE c.schedule_id=? ORDER BY m.weekday, m.start_section, c.name",
            arrayOf(schedule.id)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                val startSection = cursor.getInt(8)
                val endSection = cursor.getInt(9)
                val start = cursor.getString(4).ifBlank { resolveSlot(slots, startSection, true).orEmpty() }
                val end = cursor.getString(5).ifBlank { resolveSlot(slots, endSection, false).orEmpty() }
                val colorToken = cursor.getString(7)
                val credit = creditIndex.optDouble(normalizedCourseName(name), Double.NaN)
                courses.put(
                    JSONObject()
                        .put("id", cursor.getString(0))
                        .put("name", name)
                        .put("teacher", cursor.getString(2))
                        .put("room", cursor.getString(3))
                        .put("start", start)
                        .put("end", end)
                        .put("day", cursor.getInt(6))
                        .put("color", if (colorToken == "auto") colorFor(name) else colorToken)
                        .put("startSection", startSection)
                        .put("endSection", endSection)
                        .put("weeks", JSONArray(weeksFromMask(cursor.getLong(10), schedule.weekCount)))
                        .put("credit", if (credit.isFinite()) credit else JSONObject.NULL)
                )
            }
        }

        JSONObject()
            .put("courses", courses)
            .put("hasSchedule", true)
            .put("termName", schedule.termName)
            .put("termStart", schedule.termStart)
            .put("weekCount", schedule.weekCount)
            .toString()
    }

    private fun readCourseCreditIndex(): JSONObject = db.rawQuery(
        "SELECT value_json FROM settings WHERE key=? LIMIT 1",
        arrayOf(COURSE_CREDIT_INDEX_KEY)
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use JSONObject()
        runCatching { JSONObject(cursor.getString(0)) }.getOrDefault(JSONObject())
    }

    private fun normalizedCourseName(raw: String): String = raw
        .trim()
        .filterNot { it.isWhitespace() }
        .lowercase()

    private fun weeksFromMask(mask: Long, weekCount: Int): List<Int> {
        if (mask == 0L) return (1..weekCount).toList()
        return (1..64).filter { week -> (mask and (1L shl (week - 1))) != 0L }
    }

    private fun parseSlots(raw: String): Any? {
        if (raw.isBlank()) return null
        return runCatching { if (raw.trimStart().startsWith("[")) JSONArray(raw) else JSONObject(raw) }.getOrNull()
    }

    private fun resolveSlot(root: Any?, section: Int, start: Boolean): String? {
        val slots = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("timeSlots") ?: root.optJSONArray("slots") ?: root.optJSONArray("sections")
            else -> null
        } ?: return null
        val keys = if (start) arrayOf("startTime", "start_time", "start") else arrayOf("endTime", "end_time", "end")
        for (index in 0 until slots.length()) {
            val slot = slots.optJSONObject(index) ?: continue
            val number = slot.optInt("number", slot.optInt("section", slot.optInt("index", -1)))
            if (number != section) continue
            for (key in keys) slot.optString(key).trim().takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    private fun colorFor(name: String): String {
        val colors = arrayOf("violet", "cyan", "amber", "blue", "green", "pink", "orange")
        return colors[(name.hashCode() and Int.MAX_VALUE) % colors.size]
    }

    override fun close() = synchronized(lock) {
        if (db.isOpen) db.close()
    }

    private data class ScheduleInfo(
        val id: String,
        val termName: String,
        val termStart: String,
        val weekCount: Int,
        val sectionsJson: String
    )

    companion object {
        private const val COURSE_CREDIT_INDEX_KEY = "course.credit.index"
    }
}
