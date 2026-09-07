package com.lumaschedule.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

class LumaDatabase(context: Context) : Closeable {
    private val lock = Any()
    private val dbFile = File(context.filesDir, "lumaschedule.sqlite")
    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(dbFile, null)

    init {
        synchronized(lock) {
            db.enableWriteAheadLogging()
            db.setForeignKeyConstraintsEnabled(true)
            createSchema()
        }
    }

    private fun createSchema() {
        db.execSQL("CREATE TABLE IF NOT EXISTS terms (id TEXT PRIMARY KEY, name TEXT NOT NULL, start_date TEXT NOT NULL, week_count INTEGER NOT NULL, timezone TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS time_schemes (id TEXT PRIMARY KEY, name TEXT NOT NULL, sections_json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS schedules (id TEXT PRIMARY KEY, term_id TEXT NOT NULL REFERENCES terms(id) ON DELETE CASCADE, name TEXT NOT NULL, week_starts_on INTEGER NOT NULL DEFAULT 1, time_scheme_id TEXT REFERENCES time_schemes(id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS courses (id TEXT PRIMARY KEY, schedule_id TEXT NOT NULL REFERENCES schedules(id) ON DELETE CASCADE, name TEXT NOT NULL, code TEXT, teacher TEXT, color_token TEXT NOT NULL, note TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS course_meetings (id TEXT PRIMARY KEY, course_id TEXT NOT NULL REFERENCES courses(id) ON DELETE CASCADE, weekday INTEGER NOT NULL, start_section INTEGER NOT NULL, end_section INTEGER NOT NULL, start_time TEXT, end_time TEXT, weeks_mask INTEGER NOT NULL, location TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS course_exceptions (id TEXT PRIMARY KEY, meeting_id TEXT NOT NULL REFERENCES course_meetings(id) ON DELETE CASCADE, week INTEGER NOT NULL, kind TEXT NOT NULL, payload_json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS adapter_sources (id TEXT PRIMARY KEY, name TEXT NOT NULL, version TEXT NOT NULL, manifest_json TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, installed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)")
        db.execSQL("CREATE TABLE IF NOT EXISTS reminder_rules (id TEXT PRIMARY KEY, schedule_id TEXT NOT NULL REFERENCES schedules(id) ON DELETE CASCADE, course_id TEXT REFERENCES courses(id) ON DELETE CASCADE, moment TEXT NOT NULL, offset_minutes INTEGER NOT NULL DEFAULT 0, enabled INTEGER NOT NULL DEFAULT 1, sound INTEGER NOT NULL DEFAULT 1, vibration INTEGER NOT NULL DEFAULT 1, respect_holidays INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE TABLE IF NOT EXISTS course_automations (id TEXT PRIMARY KEY, schedule_id TEXT NOT NULL REFERENCES schedules(id) ON DELETE CASCADE, action TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE TABLE IF NOT EXISTS calendar_bindings (id TEXT PRIMARY KEY, schedule_id TEXT NOT NULL REFERENCES schedules(id) ON DELETE CASCADE, provider TEXT NOT NULL, external_calendar_id TEXT, direction TEXT NOT NULL DEFAULT 'export', enabled INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_profiles (id TEXT PRIMARY KEY, kind TEXT NOT NULL, display_name TEXT NOT NULL, config_json TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, last_sync_at TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value_json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS import_audit (id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL, summary_json TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_courses_schedule ON courses(schedule_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_meetings_course_day ON course_meetings(course_id, weekday, start_section)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_schedules_term ON schedules(term_id)")
    }

    fun getSettingRaw(key: String): String? = synchronized(lock) {
        db.rawQuery("SELECT value_json FROM settings WHERE key=? LIMIT 1", arrayOf(key)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun setSettingRaw(key: String, rawJson: String) = synchronized(lock) {
        db.execSQL(
            "INSERT INTO settings(key, value_json) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value_json=excluded.value_json",
            arrayOf(key, rawJson)
        )
    }

    fun getScheduleSnapshot(): JSONObject = synchronized(lock) {
        val scheduleId = latestScheduleId() ?: return@synchronized JSONObject()
            .put("courses", JSONArray())
            .put("hasSchedule", false)

        val courses = listScheduleCourses(scheduleId)
        var termName: String? = null
        var termStart: String? = null
        var weekCount: Int? = null
        var sectionsRaw: String? = null

        db.rawQuery(
            "SELECT t.name, t.start_date, t.week_count, COALESCE(ts.sections_json, '') FROM schedules s JOIN terms t ON t.id=s.term_id LEFT JOIN time_schemes ts ON ts.id=s.time_scheme_id WHERE s.id=? LIMIT 1",
            arrayOf(scheduleId)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                termName = cursor.getString(0)
                termStart = cursor.getString(1)
                weekCount = cursor.getInt(2).coerceIn(1, 64)
                sectionsRaw = cursor.getString(3)
            }
        }

        val currentWeek = academicWeek(termStart.orEmpty(), weekCount ?: 20)
        val visible = when {
            currentWeek == OUTSIDE_TERM -> emptyList()
            currentWeek > 0 -> courses.filter { course ->
                val weeks = course.getJSONArray("weeks")
                weeks.length() == 0 || (0 until weeks.length()).any { weeks.optInt(it) == currentWeek }
            }
            else -> courses
        }.map { it.deepCopy() }.toMutableList()

        val slots = parseSlots(sectionsRaw)
        if (slots != null) {
            visible.forEach { course ->
                if (course.optString("start").isBlank()) resolveSlot(slots, course.optInt("startSection"), true)?.let { course.put("start", it) }
                if (course.optString("end").isBlank()) resolveSlot(slots, course.optInt("endSection"), false)?.let { course.put("end", it) }
            }
        }

        val result = JSONObject()
            .put("courses", JSONArray(visible))
            .put("hasSchedule", true)
            .put("currentWeek", if (currentWeek > 0) currentWeek else JSONObject.NULL)
            .put("weekCount", weekCount ?: JSONObject.NULL)

        if (!termStart.isNullOrBlank()) result.put("termStart", termStart)
        if (!termName.isNullOrBlank() && termName != "导入学期") result.put("termName", termName)
        result
    }

    fun saveScheduleCourse(input: JSONObject): String = synchronized(lock) {
        val name = input.optString("name").trim()
        require(name.isNotEmpty()) { "课程名称不能为空" }
        val day = input.optInt("day")
        require(day in 1..7) { "星期必须在 1 到 7 之间" }
        val startSection = input.optInt("startSection")
        val endSection = input.optInt("endSection")
        require(startSection > 0 && endSection >= startSection && endSection <= 30) { "课程节次范围无效" }

        val teacher = input.optString("teacher").trim()
        val room = input.optString("room").trim()
        val start = input.optString("start").trim()
        val end = input.optString("end").trim()
        val weeks = normalizeWeeks(input.optJSONArray("weeks"))
        val weeksMask = maskFromWeeks(weeks)

        db.beginTransaction()
        try {
            val scheduleId = latestScheduleId() ?: createManualSchedule()
            val incomingId = input.optString("id").trim().takeIf { it.isNotEmpty() }
            val meetingId: String

            if (incomingId != null) {
                val courseId = db.rawQuery(
                    "SELECT c.id FROM course_meetings m JOIN courses c ON c.id=m.course_id WHERE m.id=? AND c.schedule_id=? LIMIT 1",
                    arrayOf(incomingId, scheduleId)
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?: error("找不到要编辑的课程时段")

                db.execSQL("UPDATE courses SET name=?, teacher=? WHERE id=?", arrayOf(name, teacher.takeIf(String::isNotEmpty), courseId))
                db.execSQL(
                    "UPDATE course_meetings SET weekday=?, start_section=?, end_section=?, start_time=?, end_time=?, weeks_mask=?, location=? WHERE id=?",
                    arrayOf(day, startSection, endSection, start.takeIf(String::isNotEmpty), end.takeIf(String::isNotEmpty), weeksMask, room.takeIf(String::isNotEmpty), incomingId)
                )
                meetingId = incomingId
            } else {
                val courseId = UUID.randomUUID().toString()
                meetingId = UUID.randomUUID().toString()
                db.execSQL(
                    "INSERT INTO courses(id, schedule_id, name, teacher, color_token) VALUES(?, ?, ?, ?, 'auto')",
                    arrayOf(courseId, scheduleId, name, teacher.takeIf(String::isNotEmpty))
                )
                db.execSQL(
                    "INSERT INTO course_meetings(id, course_id, weekday, start_section, end_section, start_time, end_time, weeks_mask, location) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(meetingId, courseId, day, startSection, endSection, start.takeIf(String::isNotEmpty), end.takeIf(String::isNotEmpty), weeksMask, room.takeIf(String::isNotEmpty))
                )
            }
            db.setTransactionSuccessful()
            meetingId
        } finally {
            db.endTransaction()
        }
    }

    fun deleteScheduleCourse(meetingId: String) = synchronized(lock) {
        db.beginTransaction()
        try {
            val courseId = db.rawQuery("SELECT course_id FROM course_meetings WHERE id=? LIMIT 1", arrayOf(meetingId)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: error("找不到要删除的课程时段")
            db.execSQL("DELETE FROM course_meetings WHERE id=?", arrayOf(meetingId))
            val remaining = db.rawQuery("SELECT COUNT(*) FROM course_meetings WHERE course_id=?", arrayOf(courseId)).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
            if (remaining == 0) db.execSQL("DELETE FROM courses WHERE id=?", arrayOf(courseId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun commitImport(bundle: JSONObject): JSONObject = synchronized(lock) {
        val source = bundle.optString("source", "import").ifBlank { "import" }
        val termId = UUID.randomUUID().toString()
        val scheduleId = UUID.randomUUID().toString()
        val termName = bundle.optString("termName").ifBlank { "导入学期" }
        val termStart = bundle.optString("termStart")
        val courses = bundle.optJSONArray("courses") ?: JSONArray()
        val metadata = bundle.optJSONObject("metadata") ?: JSONObject()
        val config = metadata.optString("courseConfig").takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }

        var weekCount = config?.optInt("semesterTotalWeeks", 0)?.takeIf { it > 0 } ?: 0
        if (weekCount == 0) {
            for (i in 0 until courses.length()) {
                val weeks = courses.optJSONObject(i)?.optJSONArray("weeks") ?: continue
                for (j in 0 until weeks.length()) weekCount = maxOf(weekCount, weeks.optInt(j))
            }
        }
        if (weekCount <= 0) weekCount = 20
        weekCount = weekCount.coerceIn(1, 64)
        val weekStartsOn = config?.optInt("firstDayOfWeek", 1)?.coerceIn(1, 7) ?: 1

        db.beginTransaction()
        try {
            db.execSQL("INSERT INTO terms(id, name, start_date, week_count, timezone) VALUES(?, ?, ?, ?, 'Asia/Shanghai')", arrayOf(termId, termName, termStart, weekCount))

            val timeSchemeId = metadata.optString("timeScheme").takeIf { it.isNotBlank() }?.let { raw ->
                val parsed = runCatching { if (raw.trim().startsWith("[")) JSONArray(raw) else JSONObject(raw) }.getOrNull()
                if (parsed != null) {
                    val id = UUID.randomUUID().toString()
                    db.execSQL("INSERT INTO time_schemes(id, name, sections_json) VALUES(?, ?, ?)", arrayOf(id, "$source · 导入作息", raw))
                    id
                } else null
            }

            db.execSQL(
                "INSERT INTO schedules(id, term_id, name, week_starts_on, time_scheme_id) VALUES(?, ?, ?, ?, ?)",
                arrayOf(scheduleId, termId, "$source · 导入课表", weekStartsOn, timeSchemeId)
            )

            val courseIds = LinkedHashMap<String, String>()
            var meetingCount = 0
            for (i in 0 until courses.length()) {
                val item = courses.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                if (name.isEmpty()) continue
                val teacher = item.optString("teacher").trim()
                val key = "$name\u001f$teacher"
                val courseId = courseIds[key] ?: UUID.randomUUID().toString().also { id ->
                    db.execSQL("INSERT INTO courses(id, schedule_id, name, teacher, color_token) VALUES(?, ?, ?, ?, 'auto')", arrayOf(id, scheduleId, name, teacher.takeIf(String::isNotEmpty)))
                    courseIds[key] = id
                }
                val weeks = normalizeWeeks(item.optJSONArray("weeks"))
                val meetingId = UUID.randomUUID().toString()
                db.execSQL(
                    "INSERT INTO course_meetings(id, course_id, weekday, start_section, end_section, start_time, end_time, weeks_mask, location) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(
                        meetingId,
                        courseId,
                        item.optInt("weekday", item.optInt("day", 1)).coerceIn(1, 7),
                        item.optInt("startSection", 1).coerceAtLeast(1),
                        item.optInt("endSection", item.optInt("startSection", 1)).coerceAtLeast(1),
                        item.optString("startTime").takeIf(String::isNotBlank),
                        item.optString("endTime").takeIf(String::isNotBlank),
                        maskFromWeeks(weeks),
                        item.optString("location").takeIf(String::isNotBlank)
                    )
                )
                meetingCount++
            }

            val audit = JSONObject().put("termId", termId).put("scheduleId", scheduleId).put("courseCount", courseIds.size).put("meetingCount", meetingCount)
            db.execSQL("INSERT INTO import_audit(source, summary_json) VALUES(?, ?)", arrayOf(source, audit.toString()))
            db.setTransactionSuccessful()
            JSONObject().put("termId", termId).put("scheduleId", scheduleId).put("courseCount", courseIds.size).put("meetingCount", meetingCount)
        } finally {
            db.endTransaction()
        }
    }

    private fun latestScheduleId(): String? = db.rawQuery("SELECT id FROM schedules ORDER BY rowid DESC LIMIT 1", null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    private fun createManualSchedule(): String {
        val termId = UUID.randomUUID().toString()
        val scheduleId = UUID.randomUUID().toString()
        db.execSQL("INSERT INTO terms(id, name, start_date, week_count, timezone) VALUES(?, '手动课表', '', 20, 'Asia/Shanghai')", arrayOf(termId))
        db.execSQL("INSERT INTO schedules(id, term_id, name, week_starts_on) VALUES(?, ?, '手动课表', 1)", arrayOf(scheduleId, termId))
        return scheduleId
    }

    private fun listScheduleCourses(scheduleId: String): List<JSONObject> {
        val result = ArrayList<JSONObject>()
        db.rawQuery(
            "SELECT m.id, c.name, COALESCE(c.teacher,''), COALESCE(m.location,''), COALESCE(m.start_time,''), COALESCE(m.end_time,''), m.weekday, c.color_token, m.start_section, m.end_section, m.weeks_mask FROM courses c JOIN course_meetings m ON m.course_id=c.id WHERE c.schedule_id=? ORDER BY m.weekday, m.start_section, c.name",
            arrayOf(scheduleId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                val colorToken = cursor.getString(7)
                result += JSONObject()
                    .put("id", cursor.getString(0))
                    .put("name", name)
                    .put("teacher", cursor.getString(2))
                    .put("room", cursor.getString(3))
                    .put("start", cursor.getString(4))
                    .put("end", cursor.getString(5))
                    .put("day", cursor.getInt(6))
                    .put("color", if (colorToken == "auto") colorFor(name) else colorToken)
                    .put("startSection", cursor.getInt(8))
                    .put("endSection", cursor.getInt(9))
                    .put("weeks", JSONArray(weeksFromMask(cursor.getLong(10))))
            }
        }
        return result
    }

    private fun academicWeek(rawStart: String, weekCount: Int): Int {
        if (rawStart.isBlank()) return UNKNOWN_WEEK
        val normalized = rawStart.trim().take(10).replace('/', '-').replace('.', '-')
        val start = runCatching { LocalDate.parse(normalized) }.getOrNull() ?: return UNKNOWN_WEEK
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val days = ChronoUnit.DAYS.between(start, today)
        if (days < 0) return OUTSIDE_TERM
        val week = (days / 7 + 1).toInt()
        return if (week in 1..weekCount.coerceIn(1, 64)) week else OUTSIDE_TERM
    }

    private fun normalizeWeeks(array: JSONArray?): List<Int> {
        if (array == null || array.length() == 0) return (1..20).toList()
        return (0 until array.length()).map { array.optInt(it) }.filter { it in 1..64 }.distinct().sorted().ifEmpty { (1..20).toList() }
    }

    private fun maskFromWeeks(weeks: List<Int>): Long {
        var mask = 0L
        weeks.forEach { week -> if (week in 1..64) mask = mask or (1L shl (week - 1)) }
        return mask
    }

    private fun weeksFromMask(mask: Long): List<Int> = (1..64).filter { week -> (mask and (1L shl (week - 1))) != 0L }

    private fun parseSlots(raw: String?): Any? {
        if (raw.isNullOrBlank()) return null
        return runCatching { if (raw.trim().startsWith("[")) JSONArray(raw) else JSONObject(raw) }.getOrNull()
    }

    private fun resolveSlot(root: Any, section: Int, start: Boolean): String? {
        val slots = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("timeSlots") ?: root.optJSONArray("slots") ?: root.optJSONArray("sections")
            else -> null
        } ?: return null
        for (i in 0 until slots.length()) {
            val slot = slots.optJSONObject(i) ?: continue
            val number = slot.optInt("number", slot.optInt("section", slot.optInt("index", -1)))
            if (number != section) continue
            val keys = if (start) arrayOf("startTime", "start_time", "start") else arrayOf("endTime", "end_time", "end")
            keys.forEach { key -> slot.optString(key).trim().takeIf { it.isNotEmpty() }?.let { return it } }
        }
        return null
    }

    private fun colorFor(name: String): String {
        val colors = arrayOf("violet", "cyan", "amber", "blue", "green", "pink", "orange")
        return colors[(name.hashCode() and Int.MAX_VALUE) % colors.size]
    }

    private fun JSONObject.deepCopy(): JSONObject = JSONObject(toString())

    override fun close() = synchronized(lock) { db.close() }

    companion object {
        private const val UNKNOWN_WEEK = 0
        private const val OUTSIDE_TERM = -1
    }
}