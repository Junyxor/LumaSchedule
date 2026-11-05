package com.lumaschedule.app.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Exports (JSON / ICS / full backup) and backup restore, all through the shared [LumaDatabase] lock. */
class DataTransferEngine(private val database: LumaDatabase) {

    /** LumaSchedule JSON that the importer reads back with week numbers, times, credits and the timetable. */
    fun exportScheduleJson(): String {
        val semester = database.semester()
        require(semester.optBoolean("hasSchedule")) { "还没有可导出的课表。" }
        val courses = JSONArray()
        val source = semester.optJSONArray("courses") ?: JSONArray()
        for (index in 0 until source.length()) {
            val course = source.getJSONObject(index)
            courses.put(
                JSONObject()
                    .put("name", course.optString("name"))
                    .put("teacher", course.optString("teacher").ifBlank { null } ?: JSONObject.NULL)
                    .put("location", course.optString("room").ifBlank { null } ?: JSONObject.NULL)
                    .put("weekday", course.optInt("day"))
                    .put("startSection", course.optInt("startSection"))
                    .put("endSection", course.optInt("endSection"))
                    .put("weeks", course.optJSONArray("weeks") ?: JSONArray())
                    .put("startTime", course.optString("startTime").ifBlank { null } ?: JSONObject.NULL)
                    .put("endTime", course.optString("endTime").ifBlank { null } ?: JSONObject.NULL)
                    .put("credit", course.opt("credit") ?: JSONObject.NULL)
            )
        }
        val slots = semester.optJSONArray("timeSlots") ?: JSONArray()
        val metadata = JSONObject().put(
            "courseConfig",
            JSONObject()
                .put("semesterTotalWeeks", semester.optInt("weekCount", 20))
                .put("firstDayOfWeek", semester.optInt("weekStartsOn", 1))
                .put("semesterStartDate", semester.optString("termStart"))
                .toString()
        )
        if (slots.length() > 0) {
            val scheme = JSONArray()
            for (index in 0 until slots.length()) {
                val slot = slots.getJSONObject(index)
                scheme.put(JSONObject().put("number", slot.optInt("section")).put("startTime", slot.optString("start")).put("endTime", slot.optString("end")))
            }
            metadata.put("timeScheme", scheme.toString())
        }
        return JSONObject()
            .put("source", "LumaSchedule")
            .put("termName", semester.optString("termName").ifBlank { semester.optString("scheduleName") })
            .put("termStart", semester.optString("termStart").ifBlank { null } ?: JSONObject.NULL)
            .put("courses", courses)
            .put("metadata", metadata)
            .toString(2)
    }

    /** Every occurrence as its own VEVENT, in UTC so calendar apps place it correctly in any time zone. */
    fun exportIcs(): String {
        val source = database.calendarSource() ?: error("还没有可导出的课表。")
        val termStart = source.termStart ?: error("课表还没有设置开学日期，无法生成日历文件。")
        val resolver = ScheduleMath.TimeResolver(source.slots, source.meetings)
        val stamp = ICS_UTC.format(Instant.now())
        val out = StringBuilder()
        fun line(text: String) = out.append(fold(text)).append("\r\n")
        line("BEGIN:VCALENDAR")
        line("VERSION:2.0")
        line("PRODID:-//LumaSchedule//Android//CN")
        line("CALSCALE:GREGORIAN")
        line("METHOD:PUBLISH")
        line("X-WR-CALNAME:${escape(source.termName.ifBlank { source.scheduleName })}")
        line("X-WR-TIMEZONE:${source.zone.id}")
        var exported = 0
        for (meeting in source.meetings) {
            val range = resolver.resolve(meeting) ?: continue
            val end = range.end ?: range.start.plusMinutes(45L * (maxOf(meeting.endSection, meeting.startSection) - meeting.startSection + 1))
            for (week in 1..source.weekCount) {
                if (!ScheduleMath.inWeek(meeting.weeksMask, week)) continue
                val date = ScheduleMath.dateOf(termStart, source.weekStartsOn, week, meeting.day)
                val startAt = LocalDateTime.of(date, range.start).atZone(source.zone).toInstant()
                val endAt = LocalDateTime.of(date, end).atZone(source.zone).toInstant()
                val details = buildList {
                    if (meeting.teacher.isNotBlank()) add("教师：${meeting.teacher}")
                    add("第 $week 周")
                    add(if (meeting.startSection == meeting.endSection) "第 ${meeting.startSection} 节" else "第 ${meeting.startSection}–${meeting.endSection} 节")
                }.joinToString(" · ")
                line("BEGIN:VEVENT")
                line("UID:${escape("${meeting.id}-$week@lumaschedule")}")
                line("DTSTAMP:$stamp")
                line("DTSTART:${ICS_UTC.format(startAt)}")
                line("DTEND:${ICS_UTC.format(endAt)}")
                line("SUMMARY:${escape(meeting.name)}")
                if (meeting.room.isNotBlank()) line("LOCATION:${escape(meeting.room)}")
                line("DESCRIPTION:${escape(details)}")
                line("END:VEVENT")
                exported++
            }
        }
        line("END:VCALENDAR")
        require(exported > 0) { "没有可以导出的上课时间。请先在「作息时间」中设置每节课的时间。" }
        return out.toString()
    }

    fun exportFullBackup(appVersion: String): String = database.read { db ->
        val tables = JSONObject()
        var rowCount = 0
        for (table in TABLES) {
            val rows = dumpTable(db, table)
            rowCount += rows.length()
            tables.put(table, rows)
        }
        JSONObject()
            .put("format", BACKUP_FORMAT)
            .put("schemaVersion", LumaDatabase.SCHEMA_VERSION)
            .put("generatedAtUnixMs", System.currentTimeMillis())
            .put("appVersion", appVersion)
            .put("tables", tables)
            .put("summary", summary(db, rowCount))
            .toString(2)
    }

    /** Replaces all local data with a backup. Backups written by older schema versions are accepted. */
    fun restoreFullBackup(raw: String): JSONObject {
        val root = runCatching { JSONObject(raw) }.getOrElse { error("备份文件不是有效的 JSON。") }
        require(root.optString("format") == BACKUP_FORMAT) { "这不是 LumaSchedule 完整备份文件。" }
        val version = root.optInt("schemaVersion", 0)
        require(version in 1..LumaDatabase.SCHEMA_VERSION) { "备份来自更新版本的 LumaSchedule（格式 v$version），请先升级应用。" }
        val tables = root.optJSONObject("tables") ?: error("备份缺少数据表。")
        val result = database.write { db ->
            for (table in TABLES.asReversed()) db.delete(table, null, null)
            for (table in TABLES) {
                val rows = tables.optJSONArray(table) ?: continue
                val columns = tableColumns(db, table)
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    db.insertOrThrow(table, null, contentValues(row, columns))
                }
            }
            summary(db, TABLES.sumOf { count(db, it) })
        }
        database.runLegacyMigrations()
        return result
    }

    fun backupSummary(raw: String): JSONObject {
        val root = JSONObject(raw)
        val tables = root.optJSONObject("tables") ?: JSONObject()
        return JSONObject()
            .put("schemaVersion", root.optInt("schemaVersion", LumaDatabase.SCHEMA_VERSION))
            .put("generatedAtUnixMs", root.optLong("generatedAtUnixMs", 0))
            .put("tableCount", tables.length())
            .put("rowCount", TABLES.sumOf { tables.optJSONArray(it)?.length() ?: 0 })
            .put("scheduleCount", tables.optJSONArray("schedules")?.length() ?: 0)
            .put("courseCount", tables.optJSONArray("courses")?.length() ?: 0)
            .put("meetingCount", tables.optJSONArray("course_meetings")?.length() ?: 0)
    }

    private fun dumpTable(db: SQLiteDatabase, table: String): JSONArray {
        val array = JSONArray()
        db.rawQuery("SELECT * FROM $table", null).use { cursor ->
            while (cursor.moveToNext()) {
                val row = JSONObject()
                for (column in 0 until cursor.columnCount) row.put(cursor.getColumnName(column), cursorValue(cursor, column))
                array.put(row)
            }
        }
        return array
    }

    private fun tableColumns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            buildSet { while (cursor.moveToNext()) add(cursor.getString(nameIndex)) }
        }

    private fun cursorValue(cursor: Cursor, column: Int): Any = when (cursor.getType(column)) {
        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
        Cursor.FIELD_TYPE_BLOB -> JSONObject().put(BLOB_KEY, Base64.encodeToString(cursor.getBlob(column), Base64.NO_WRAP))
        else -> cursor.getString(column)
    }

    private fun contentValues(row: JSONObject, columns: Set<String>): ContentValues {
        val values = ContentValues(row.length())
        val keys = row.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in columns) continue
            when (val value = row.opt(key)) {
                null, JSONObject.NULL -> values.putNull(key)
                is Int -> values.put(key, value)
                is Long -> values.put(key, value)
                is Double -> values.put(key, value)
                is Boolean -> values.put(key, if (value) 1 else 0)
                is JSONObject -> if (value.has(BLOB_KEY)) values.put(key, Base64.decode(value.getString(BLOB_KEY), Base64.DEFAULT)) else values.put(key, value.toString())
                else -> values.put(key, value.toString())
            }
        }
        return values
    }

    private fun summary(db: SQLiteDatabase, rowCount: Int): JSONObject = JSONObject()
        .put("schemaVersion", LumaDatabase.SCHEMA_VERSION)
        .put("generatedAtUnixMs", System.currentTimeMillis())
        .put("tableCount", TABLES.size)
        .put("rowCount", rowCount)
        .put("scheduleCount", count(db, "schedules"))
        .put("courseCount", count(db, "courses"))
        .put("meetingCount", count(db, "course_meetings"))

    private fun count(db: SQLiteDatabase, table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private fun escape(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")

    /** RFC 5545 §3.1: lines longer than 75 octets are folded with CRLF + space. */
    private fun fold(line: String): String {
        val bytes = line.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= 75) return line
        val out = StringBuilder()
        var count = 0
        var limit = 75
        var index = 0
        while (index < line.length) {
            val piece = line.substring(index, index + Character.charCount(line.codePointAt(index)))
            val size = piece.toByteArray(StandardCharsets.UTF_8).size
            if (count + size > limit) {
                out.append("\r\n ")
                count = 0
                limit = 74
            }
            out.append(piece)
            count += size
            index += piece.length
        }
        return out.toString()
    }

    companion object {
        const val BACKUP_FORMAT = "lumaschedule-backup"
        private const val BLOB_KEY = "__luma_blob_base64"

        /** Insert order respects foreign keys; deletion runs in reverse. */
        val TABLES = listOf(
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

        private val ICS_UTC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    }
}
