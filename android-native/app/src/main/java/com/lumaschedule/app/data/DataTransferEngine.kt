package com.lumaschedule.app.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DataTransferEngine(private val dbFile: File) {
    fun exportCanonicalJson(): String = withDatabase(readOnly = true) { db ->
        val schedule = latestSchedule(db) ?: error("还没有可导出的课表。")
        val courses = JSONArray()
        db.rawQuery(
            "SELECT c.name,COALESCE(c.teacher,''),COALESCE(m.location,''),m.weekday,m.start_section,m.end_section,m.weeks_mask,COALESCE(m.start_time,''),COALESCE(m.end_time,'') " +
                "FROM courses c JOIN course_meetings m ON m.course_id=c.id WHERE c.schedule_id=? ORDER BY m.weekday,m.start_section,c.name",
            arrayOf(schedule.id)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                courses.put(
                    JSONObject()
                        .put("name", cursor.getString(0))
                        .put("teacher", cursor.getString(1).ifBlank { JSONObject.NULL })
                        .put("location", cursor.getString(2).ifBlank { JSONObject.NULL })
                        .put("weekday", cursor.getInt(3))
                        .put("startSection", cursor.getInt(4))
                        .put("endSection", cursor.getInt(5))
                        .put("weeks", JSONArray(weeksFromMask(cursor.getLong(6), schedule.weekCount)))
                        .put("startTime", cursor.getString(7).ifBlank { JSONObject.NULL })
                        .put("endTime", cursor.getString(8).ifBlank { JSONObject.NULL })
                )
            }
        }
        JSONObject()
            .put("source", "LumaSchedule")
            .put("termName", schedule.termName.ifBlank { JSONObject.NULL })
            .put("termStart", schedule.termStart.ifBlank { JSONObject.NULL })
            .put("courses", courses)
            .put("metadata", JSONObject())
            .toString(2)
    }

    fun exportIcs(): String = withDatabase(readOnly = true) { db ->
        val schedule = latestSchedule(db) ?: error("还没有可导出的课表。")
        val termStart = parseDate(schedule.termStart) ?: error("课表缺少有效开学日期，无法生成系统日历。")
        val slots = parseSlots(schedule.sectionsJson)
        val zone = ZoneId.of(schedule.timezone.ifBlank { "Asia/Shanghai" })
        val out = StringBuilder()
        out.append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//LumaSchedule//Native//CN\r\nCALSCALE:GREGORIAN\r\n")
        db.rawQuery(
            "SELECT m.id,c.name,COALESCE(c.teacher,''),COALESCE(m.location,''),m.weekday,m.start_section,m.end_section,m.weeks_mask,COALESCE(m.start_time,''),COALESCE(m.end_time,'') " +
                "FROM courses c JOIN course_meetings m ON m.course_id=c.id WHERE c.schedule_id=? ORDER BY m.weekday,m.start_section,c.name",
            arrayOf(schedule.id)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val meetingId = cursor.getString(0)
                val name = cursor.getString(1)
                val teacher = cursor.getString(2)
                val room = cursor.getString(3)
                val weekday = cursor.getInt(4)
                val startSection = cursor.getInt(5)
                val endSection = cursor.getInt(6)
                val weeks = weeksFromMask(cursor.getLong(7), schedule.weekCount)
                val startTime = parseTime(cursor.getString(8).ifBlank { resolveSlot(slots, startSection, true).orEmpty() })
                val endTime = parseTime(cursor.getString(9).ifBlank { resolveSlot(slots, endSection, false).orEmpty() })
                if (weekday !in 1..7 || startTime == null || endTime == null) continue
                for (week in weeks) {
                    val date = termStart.plusDays((week - 1L) * 7L + weekday - 1L)
                    val start = LocalDateTime.of(date, startTime).atZone(zone)
                    val end = LocalDateTime.of(date, endTime).atZone(zone)
                    out.append("BEGIN:VEVENT\r\n")
                    out.append("UID:").append(escapeIcs("$meetingId-$week@lumaschedule")).append("\r\n")
                    out.append("DTSTAMP:").append(ICS_UTC.format(Instant.now())).append("\r\n")
                    out.append("DTSTART:").append(ICS_LOCAL.format(start.toLocalDateTime())).append("\r\n")
                    out.append("DTEND:").append(ICS_LOCAL.format(end.toLocalDateTime())).append("\r\n")
                    out.append("SUMMARY:").append(escapeIcs(name)).append("\r\n")
                    if (room.isNotBlank()) out.append("LOCATION:").append(escapeIcs(room)).append("\r\n")
                    if (teacher.isNotBlank()) out.append("DESCRIPTION:").append(escapeIcs("教师：$teacher · 第 $week 周")).append("\r\n")
                    out.append("END:VEVENT\r\n")
                }
            }
        }
        out.append("END:VCALENDAR\r\n")
        out.toString()
    }

    fun exportFullBackup(appVersion: String): String = withDatabase(readOnly = true) { db ->
        val tables = JSONObject()
        var rowCount = 0
        for (table in INSERT_ORDER) {
            val rows = dumpTable(db, table)
            rowCount += rows.length()
            tables.put(table, rows)
        }
        JSONObject()
            .put("format", BACKUP_FORMAT)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("generatedAtUnixMs", System.currentTimeMillis())
            .put("appVersion", appVersion)
            .put("tables", tables)
            .put("summary", summary(db, rowCount))
            .toString(2)
    }

    fun restoreFullBackup(raw: String): JSONObject {
        val root = JSONObject(raw)
        require(root.optString("format") == BACKUP_FORMAT) { "不是 LumaSchedule 完整备份。" }
        require(root.optInt("schemaVersion") == SCHEMA_VERSION) { "不支持的备份版本。" }
        val tables = root.optJSONObject("tables") ?: error("备份缺少 tables。")
        return withDatabase(readOnly = false) { db ->
            db.setForeignKeyConstraintsEnabled(true)
            db.beginTransaction()
            try {
                for (table in DELETE_ORDER) db.delete(table, null, null)
                for (table in INSERT_ORDER) {
                    val rows = tables.optJSONArray(table) ?: JSONArray()
                    for (index in 0 until rows.length()) {
                        val row = rows.optJSONObject(index) ?: continue
                        db.insertOrThrow(table, null, contentValues(row))
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            val rowCount = INSERT_ORDER.sumOf { count(db, it) }
            summary(db, rowCount)
        }
    }

    fun backupSummary(raw: String): JSONObject {
        val root = JSONObject(raw)
        val tables = root.optJSONObject("tables") ?: JSONObject()
        var rowCount = 0
        INSERT_ORDER.forEach { rowCount += tables.optJSONArray(it)?.length() ?: 0 }
        return JSONObject()
            .put("schemaVersion", root.optInt("schemaVersion", SCHEMA_VERSION))
            .put("generatedAtUnixMs", root.optLong("generatedAtUnixMs", 0))
            .put("tableCount", tables.length())
            .put("rowCount", rowCount)
            .put("scheduleCount", tables.optJSONArray("schedules")?.length() ?: 0)
            .put("courseCount", tables.optJSONArray("courses")?.length() ?: 0)
            .put("meetingCount", tables.optJSONArray("course_meetings")?.length() ?: 0)
    }

    private fun latestSchedule(db: SQLiteDatabase): ScheduleInfo? = db.rawQuery(
        "SELECT s.id,t.name,t.start_date,t.week_count,COALESCE(t.timezone,'Asia/Shanghai'),COALESCE(ts.sections_json,'') " +
            "FROM schedules s JOIN terms t ON t.id=s.term_id LEFT JOIN time_schemes ts ON ts.id=s.time_scheme_id ORDER BY s.rowid DESC LIMIT 1",
        null
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else ScheduleInfo(
            id = cursor.getString(0),
            termName = cursor.getString(1),
            termStart = cursor.getString(2),
            weekCount = cursor.getInt(3).coerceIn(1, 64),
            timezone = cursor.getString(4),
            sectionsJson = cursor.getString(5)
        )
    }

    private fun dumpTable(db: SQLiteDatabase, table: String): JSONArray {
        val array = JSONArray()
        db.rawQuery("SELECT * FROM $table", null).use { cursor ->
            while (cursor.moveToNext()) {
                val row = JSONObject()
                for (column in 0 until cursor.columnCount) {
                    row.put(cursor.getColumnName(column), cursorValue(cursor, column))
                }
                array.put(row)
            }
        }
        return array
    }

    private fun cursorValue(cursor: Cursor, column: Int): Any = when (cursor.getType(column)) {
        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
        Cursor.FIELD_TYPE_BLOB -> JSONObject().put("$blob", Base64.encodeToString(cursor.getBlob(column), Base64.NO_WRAP))
        else -> cursor.getString(column)
    }

    private fun contentValues(row: JSONObject): ContentValues {
        val values = ContentValues(row.length())
        val keys = row.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = row.opt(key)
            when (value) {
                null, JSONObject.NULL -> values.putNull(key)
                is Int -> values.put(key, value)
                is Long -> values.put(key, value)
                is Double -> values.put(key, value)
                is Boolean -> values.put(key, if (value) 1 else 0)
                is JSONObject -> {
                    if (value.has("$blob")) values.put(key, Base64.decode(value.getString("$blob"), Base64.DEFAULT))
                    else values.put(key, value.toString())
                }
                else -> values.put(key, value.toString())
            }
        }
        return values
    }

    private fun summary(db: SQLiteDatabase, rowCount: Int): JSONObject = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("generatedAtUnixMs", System.currentTimeMillis())
        .put("tableCount", INSERT_ORDER.size)
        .put("rowCount", rowCount)
        .put("scheduleCount", count(db, "schedules"))
        .put("courseCount", count(db, "courses"))
        .put("meetingCount", count(db, "course_meetings"))

    private fun count(db: SQLiteDatabase, table: String): Int = db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
    }

    private fun weeksFromMask(mask: Long, weekCount: Int): List<Int> {
        if (mask == 0L) return (1..weekCount).toList()
        return (1..weekCount).filter { week -> (mask and (1L shl (week - 1))) != 0L }
    }

    private fun parseDate(raw: String): LocalDate? {
        val text = raw.trim().take(10).replace('/', '-').replace('.', '-')
        return runCatching { LocalDate.parse(text) }.getOrNull()
    }

    private fun parseTime(raw: String): LocalTime? = runCatching {
        raw.trim().takeIf { it.length >= 5 }?.take(5)?.let(LocalTime::parse)
    }.getOrNull()

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

    private fun escapeIcs(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")

    private inline fun <T> withDatabase(readOnly: Boolean, block: (SQLiteDatabase) -> T): T {
        val flags = if (readOnly) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, flags)
        return try { block(db) } finally { db.close() }
    }

    private data class ScheduleInfo(
        val id: String,
        val termName: String,
        val termStart: String,
        val weekCount: Int,
        val timezone: String,
        val sectionsJson: String
    )

    companion object {
        const val BACKUP_FORMAT = "lumaschedule-backup"
        const val SCHEMA_VERSION = 1

        val INSERT_ORDER = listOf(
            "terms",
            "time_schemes",
            "schedules",
            "courses",
            "course_meetings",
            "course_exceptions",
            "adapter_sources",
            "reminder_rules",
            "course_automations",
            "calendar_bindings",
            "sync_profiles",
            "settings",
            "import_audit",
            "grade_records"
        )
        val DELETE_ORDER = INSERT_ORDER.asReversed()

        private val ICS_LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
        private val ICS_UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneId.of("UTC"))
        private const val blob = "__luma_blob_base64"
    }
}
