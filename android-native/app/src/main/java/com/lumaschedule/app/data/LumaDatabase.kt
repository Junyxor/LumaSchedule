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
        db.execSQL("CREATE TABLE IF NOT EXISTS grade_records (id TEXT PRIMARY KEY, source TEXT NOT NULL, institution TEXT NOT NULL DEFAULT '', term_label TEXT NOT NULL, course_code TEXT NOT NULL DEFAULT '', course_name TEXT NOT NULL, course_type TEXT NOT NULL DEFAULT '', credit REAL, score_text TEXT NOT NULL DEFAULT '', numeric_score REAL, grade_point REAL, elective INTEGER NOT NULL DEFAULT 0, attempt INTEGER NOT NULL DEFAULT 1, imported_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_courses_schedule ON courses(schedule_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_meetings_course_day ON course_meetings(course_id, weekday, start_section)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_schedules_term ON schedules(term_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_grades_term_name ON grade_records(term_label, course_name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_grades_source_term ON grade_records(source, institution, term_label)")
    }

    fun getSettingRaw(key: String): String? = synchronized(lock) { getSettingRawUnlocked(key) }

    fun setSettingRaw(key: String, rawJson: String) = synchronized(lock) { setSettingRawUnlocked(key, rawJson) }

    fun getScheduleSnapshot(): JSONObject = synchronized(lock) {
        val scheduleId = latestScheduleId() ?: return@synchronized JSONObject()
            .put("courses", JSONArray())
            .put("hasSchedule", false)

        val courses = listScheduleCourses(scheduleId)
        var termName: String? = null
        var termStart: String? = null
        var weekCount: Int? = null
        var timezone = DEFAULT_TIMEZONE
        var sectionsRaw: String? = null

        db.rawQuery(
            "SELECT t.name, t.start_date, t.week_count, t.timezone, COALESCE(ts.sections_json, '') FROM schedules s JOIN terms t ON t.id=s.term_id LEFT JOIN time_schemes ts ON ts.id=s.time_scheme_id WHERE s.id=? LIMIT 1",
            arrayOf(scheduleId)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                termName = cursor.getString(0)
                termStart = cursor.getString(1)
                weekCount = cursor.getInt(2).coerceIn(1, 64)
                timezone = cursor.getString(3).takeIf { it.isNotBlank() } ?: DEFAULT_TIMEZONE
                sectionsRaw = cursor.getString(4)
            }
        }

        val currentWeek = academicWeek(termStart.orEmpty(), weekCount ?: 20, timezone)
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

    fun getSchedulePreferences(): JSONObject = synchronized(lock) { schedulePreferencesUnlocked() }

    fun saveSchedulePreferences(input: JSONObject): JSONObject = synchronized(lock) {
        val display = JSONObject()
            .put("showWeekend", input.optBoolean("showWeekend", true))
            .put("showTeacher", input.optBoolean("showTeacher", true))
            .put("showRoom", input.optBoolean("showRoom", true))
            .put("showTime", input.optBoolean("showTime", true))
            .put("compactMode", input.optBoolean("compactMode", false))
            .put("defaultSections", input.optInt("defaultSections", 12).coerceIn(8, 30))
        setSettingRawUnlocked(SCHEDULE_DISPLAY_KEY, display.toString())

        val scheduleId = latestScheduleId()
        if (scheduleId != null) {
            val current = schedulePreferencesUnlocked()
            val termName = stringValue(input, "termName").ifBlank { current.optString("termName", "当前学期") }
            val termStart = stringValue(input, "termStart")
            if (termStart.isNotBlank()) require(runCatching { LocalDate.parse(termStart) }.isSuccess) { "开学日期格式应为 YYYY-MM-DD" }
            val weekCount = input.optInt("weekCount", current.optInt("weekCount", 20)).coerceIn(1, 64)
            val weekStartsOn = input.optInt("weekStartsOn", current.optInt("weekStartsOn", 1)).coerceIn(1, 7)
            val timezone = stringValue(input, "timezone").ifBlank { current.optString("timezone", DEFAULT_TIMEZONE) }
            require(runCatching { ZoneId.of(timezone) }.isSuccess) { "无效时区：$timezone" }

            db.rawQuery("SELECT term_id FROM schedules WHERE id=? LIMIT 1", arrayOf(scheduleId)).use { cursor ->
                if (cursor.moveToFirst()) {
                    val termId = cursor.getString(0)
                    db.execSQL(
                        "UPDATE terms SET name=?, start_date=?, week_count=?, timezone=? WHERE id=?",
                        arrayOf(termName, termStart, weekCount, timezone, termId)
                    )
                }
            }
            db.execSQL("UPDATE schedules SET week_starts_on=? WHERE id=?", arrayOf(weekStartsOn, scheduleId))
        }
        schedulePreferencesUnlocked()
    }

    fun getGradeSnapshot(): JSONObject = synchronized(lock) {
        val records = JSONArray()
        val terms = linkedSetOf<String>()
        val institutions = linkedSetOf<String>()
        var updatedAt: String? = null
        db.rawQuery(
            "SELECT id, source, institution, term_label, course_code, course_name, course_type, credit, score_text, numeric_score, grade_point, elective, attempt, imported_at FROM grade_records ORDER BY term_label DESC, course_name COLLATE NOCASE, attempt",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val term = cursor.getString(3)
                val institution = cursor.getString(2)
                terms += term
                if (institution.isNotBlank()) institutions += institution
                val importedAt = cursor.getString(13)
                if (updatedAt == null || importedAt > updatedAt!!) updatedAt = importedAt
                records.put(
                    JSONObject()
                        .put("id", cursor.getString(0))
                        .put("source", cursor.getString(1))
                        .put("institution", institution)
                        .put("term", term)
                        .put("courseCode", cursor.getString(4))
                        .put("courseName", cursor.getString(5))
                        .put("courseType", cursor.getString(6))
                        .put("credit", if (cursor.isNull(7)) JSONObject.NULL else cursor.getDouble(7))
                        .put("scoreText", cursor.getString(8))
                        .put("numericScore", if (cursor.isNull(9)) JSONObject.NULL else cursor.getDouble(9))
                        .put("gradePoint", if (cursor.isNull(10)) JSONObject.NULL else cursor.getDouble(10))
                        .put("elective", cursor.getInt(11) != 0)
                        .put("attempt", cursor.getInt(12))
                        .put("importedAt", importedAt)
                )
            }
        }
        JSONObject()
            .put("records", records)
            .put("terms", JSONArray(terms.toList()))
            .put("institutions", JSONArray(institutions.toList()))
            .put("updatedAt", updatedAt ?: JSONObject.NULL)
    }

    fun commitGradeBundle(bundle: JSONObject): JSONObject = synchronized(lock) {
        val source = stringValue(bundle, "source").ifBlank { "教务成绩" }
        val institution = stringValue(bundle, "institution")
        val defaultTerm = stringValue(bundle, "termName").ifBlank { "未分组" }
        val rows = bundle.optJSONArray("records") ?: JSONArray()
        require(rows.length() > 0) { "没有可保存的成绩记录" }

        val normalized = ArrayList<JSONObject>(rows.length())
        val terms = linkedSetOf<String>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val courseName = stringValue(row, "courseName").ifBlank { stringValue(row, "name") }
            if (courseName.isBlank()) continue
            val term = stringValue(row, "term").ifBlank { defaultTerm }
            terms += term
            normalized += row.deepCopy().put("courseName", courseName).put("term", term)
        }
        require(normalized.isNotEmpty()) { "成绩记录缺少课程名称" }

        var replacedCount = 0
        db.beginTransaction()
        try {
            terms.forEach { term ->
                replacedCount += db.rawQuery(
                    "SELECT COUNT(*) FROM grade_records WHERE source=? AND institution=? AND term_label=?",
                    arrayOf(source, institution, term)
                ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
                db.execSQL(
                    "DELETE FROM grade_records WHERE source=? AND institution=? AND term_label=?",
                    arrayOf(source, institution, term)
                )
            }

            normalized.forEach { row ->
                val courseType = stringValue(row, "courseType").ifBlank { stringValue(row, "type") }
                val scoreText = stringValue(row, "scoreText").ifBlank { stringValue(row, "score") }
                val numericScore = nullableDouble(row, "numericScore") ?: scoreText.toDoubleOrNull()
                val credit = nullableDouble(row, "credit")?.takeIf { it >= 0.0 }
                val gradePoint = nullableDouble(row, "gradePoint")?.takeIf { it >= 0.0 }
                val elective = when {
                    row.has("elective") && !row.isNull("elective") -> row.optBoolean("elective", false)
                    else -> courseType.contains("选修") || courseType.contains("任选") || courseType.contains("限选") || courseType.contains("公选")
                }
                db.execSQL(
                    "INSERT INTO grade_records(id, source, institution, term_label, course_code, course_name, course_type, credit, score_text, numeric_score, grade_point, elective, attempt) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(
                        UUID.randomUUID().toString(),
                        source,
                        institution,
                        stringValue(row, "term"),
                        stringValue(row, "courseCode").ifBlank { stringValue(row, "code") },
                        stringValue(row, "courseName"),
                        courseType,
                        credit,
                        scoreText,
                        numericScore,
                        gradePoint,
                        if (elective) 1 else 0,
                        row.optInt("attempt", 1).coerceIn(1, 20)
                    )
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        JSONObject()
            .put("recordCount", normalized.size)
            .put("termCount", terms.size)
            .put("replacedCount", replacedCount)
    }

    fun saveScheduleCourse(input: JSONObject): String = synchronized(lock) {
        val name = stringValue(input, "name")
        require(name.isNotEmpty()) { "课程名称不能为空" }
        val day = input.optInt("day")
        require(day in 1..7) { "星期必须在 1 到 7 之间" }
        val startSection = input.optInt("startSection")
        val endSection = input.optInt("endSection")
        require(startSection > 0 && endSection >= startSection && endSection <= 30) { "课程节次范围无效" }

        val teacher = stringValue(input, "teacher")
        val room = stringValue(input, "room")
        val start = stringValue(input, "start")
        val end = stringValue(input, "end")
        val weeks = normalizeWeeks(input.optJSONArray("weeks"))
        val weeksMask = maskFromWeeks(weeks)

        db.beginTransaction()
        try {
            val scheduleId = latestScheduleId() ?: createManualSchedule()
            val incomingId = stringValue(input, "id").takeIf { it.isNotEmpty() }
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

    fun previewImport(bundle: JSONObject): JSONObject = synchronized(lock) {
        val incoming = importItems(bundle)
        val scheduleId = latestScheduleId()
        val existing = scheduleId?.let(::listScheduleCourses).orEmpty()
        val existingSignatures = existing.map(::meetingSignature).toSet()
        val incomingSignatures = linkedSetOf<String>()
        var newCount = 0
        var duplicateCount = 0
        var conflictCount = 0

        incoming.forEach { item ->
            val signature = meetingSignature(item)
            if (!incomingSignatures.add(signature) || signature in existingSignatures) {
                duplicateCount++
            } else {
                newCount++
                if (existing.any { other -> meetingSignature(other) != signature && meetingsConflict(item, other) }) conflictCount++
            }
        }
        val removeCount = existingSignatures.count { it !in incomingSignatures }
        JSONObject()
            .put("hasExistingSchedule", scheduleId != null)
            .put("existingCourseCount", scheduleId?.let(::countCourses) ?: 0)
            .put("existingMeetingCount", existing.size)
            .put("incomingCourseCount", incoming.map(::courseKey).toSet().size)
            .put("incomingMeetingCount", incoming.size)
            .put("newCount", newCount)
            .put("duplicateCount", duplicateCount)
            .put("conflictCount", conflictCount)
            .put("removeCount", removeCount)
    }

    fun commitImport(bundle: JSONObject, requestedMode: String = "new"): JSONObject = synchronized(lock) {
        val mode = requestedMode.lowercase().takeIf { it in setOf("new", "merge", "overwrite") } ?: "new"
        val existingScheduleId = latestScheduleId()
        db.beginTransaction()
        try {
            val result = when {
                existingScheduleId == null || mode == "new" -> createImportedSchedule(bundle, mode)
                mode == "merge" -> mergeImportedSchedule(existingScheduleId, bundle)
                else -> overwriteImportedSchedule(existingScheduleId, bundle)
            }
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    private fun createImportedSchedule(bundle: JSONObject, requestedMode: String): JSONObject {
        val meta = importMeta(bundle)
        val termId = UUID.randomUUID().toString()
        val scheduleId = UUID.randomUUID().toString()
        db.execSQL(
            "INSERT INTO terms(id, name, start_date, week_count, timezone) VALUES(?, ?, ?, ?, ?)",
            arrayOf(termId, meta.termName, meta.termStart, meta.weekCount, DEFAULT_TIMEZONE)
        )
        val timeSchemeId = createTimeScheme(meta.source, meta.timeSchemeRaw)
        db.execSQL(
            "INSERT INTO schedules(id, term_id, name, week_starts_on, time_scheme_id) VALUES(?, ?, ?, ?, ?)",
            arrayOf(scheduleId, termId, "${meta.source} · 导入课表", meta.weekStartsOn, timeSchemeId)
        )
        val stats = insertImportedMeetings(scheduleId, importItems(bundle), emptySet())
        val result = importResult(requestedMode, termId, scheduleId, stats.added, stats.skipped, 0)
        writeImportAudit(meta.source, result)
        return result
    }

    private fun mergeImportedSchedule(scheduleId: String, bundle: JSONObject): JSONObject {
        val meta = importMeta(bundle)
        val existing = listScheduleCourses(scheduleId)
        val signatures = existing.map(::meetingSignature).toSet()
        var termId = ""
        var existingName = ""
        var existingStart = ""
        var existingWeeks = 20
        var currentSchemeId: String? = null
        db.rawQuery(
            "SELECT t.id, t.name, t.start_date, t.week_count, s.time_scheme_id FROM schedules s JOIN terms t ON t.id=s.term_id WHERE s.id=? LIMIT 1",
            arrayOf(scheduleId)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                termId = cursor.getString(0)
                existingName = cursor.getString(1)
                existingStart = cursor.getString(2)
                existingWeeks = cursor.getInt(3)
                currentSchemeId = if (cursor.isNull(4)) null else cursor.getString(4)
            }
        }
        val mergedName = if (existingName.isBlank() || existingName == "手动课表" || existingName == "导入学期") meta.termName else existingName
        val mergedStart = existingStart.ifBlank { meta.termStart }
        db.execSQL(
            "UPDATE terms SET name=?, start_date=?, week_count=? WHERE id=?",
            arrayOf(mergedName, mergedStart, maxOf(existingWeeks, meta.weekCount).coerceIn(1, 64), termId)
        )
        if (currentSchemeId == null && meta.timeSchemeRaw != null) {
            val schemeId = createTimeScheme(meta.source, meta.timeSchemeRaw)
            db.execSQL("UPDATE schedules SET time_scheme_id=? WHERE id=?", arrayOf(schemeId, scheduleId))
        }
        val stats = insertImportedMeetings(scheduleId, importItems(bundle), signatures)
        val result = importResult("merge", termId, scheduleId, stats.added, stats.skipped, 0)
        writeImportAudit(meta.source, result)
        return result
    }

    private fun overwriteImportedSchedule(scheduleId: String, bundle: JSONObject): JSONObject {
        val meta = importMeta(bundle)
        var termId = ""
        var oldSchemeId: String? = null
        db.rawQuery("SELECT term_id, time_scheme_id FROM schedules WHERE id=? LIMIT 1", arrayOf(scheduleId)).use { cursor ->
            if (cursor.moveToFirst()) {
                termId = cursor.getString(0)
                oldSchemeId = if (cursor.isNull(1)) null else cursor.getString(1)
            }
        }
        val removedCount = countMeetings(scheduleId)
        db.execSQL("UPDATE schedules SET time_scheme_id=NULL WHERE id=?", arrayOf(scheduleId))
        db.execSQL("DELETE FROM courses WHERE schedule_id=?", arrayOf(scheduleId))
        oldSchemeId?.let { db.execSQL("DELETE FROM time_schemes WHERE id=?", arrayOf(it)) }
        db.execSQL(
            "UPDATE terms SET name=?, start_date=?, week_count=?, timezone=? WHERE id=?",
            arrayOf(meta.termName, meta.termStart, meta.weekCount, DEFAULT_TIMEZONE, termId)
        )
        val schemeId = createTimeScheme(meta.source, meta.timeSchemeRaw)
        db.execSQL(
            "UPDATE schedules SET name=?, week_starts_on=?, time_scheme_id=? WHERE id=?",
            arrayOf("${meta.source} · 导入课表", meta.weekStartsOn, schemeId, scheduleId)
        )
        val stats = insertImportedMeetings(scheduleId, importItems(bundle), emptySet())
        val result = importResult("overwrite", termId, scheduleId, stats.added, stats.skipped, removedCount)
        writeImportAudit(meta.source, result)
        return result
    }

    private fun importResult(mode: String, termId: String, scheduleId: String, added: Int, skipped: Int, removed: Int): JSONObject = JSONObject()
        .put("mode", mode)
        .put("termId", termId)
        .put("scheduleId", scheduleId)
        .put("courseCount", countCourses(scheduleId))
        .put("meetingCount", countMeetings(scheduleId))
        .put("addedCount", added)
        .put("skippedCount", skipped)
        .put("removedCount", removed)

    private fun writeImportAudit(source: String, result: JSONObject) {
        db.execSQL("INSERT INTO import_audit(source, summary_json) VALUES(?, ?)", arrayOf(source, result.toString()))
    }

    private fun insertImportedMeetings(scheduleId: String, items: List<JSONObject>, skipSignatures: Set<String>): InsertStats {
        val courseIds = LinkedHashMap<String, String>()
        db.rawQuery("SELECT id, name, COALESCE(teacher,'') FROM courses WHERE schedule_id=?", arrayOf(scheduleId)).use { cursor ->
            while (cursor.moveToNext()) courseIds[courseKey(cursor.getString(1), cursor.getString(2))] = cursor.getString(0)
        }
        val seen = linkedSetOf<String>()
        var added = 0
        var skipped = 0
        items.forEach { item ->
            val signature = meetingSignature(item)
            if (!seen.add(signature) || signature in skipSignatures) {
                skipped++
                return@forEach
            }
            val name = stringValue(item, "name")
            if (name.isBlank()) return@forEach
            val teacher = stringValue(item, "teacher")
            val key = courseKey(name, teacher)
            val courseId = courseIds[key] ?: UUID.randomUUID().toString().also { id ->
                db.execSQL(
                    "INSERT INTO courses(id, schedule_id, name, teacher, color_token) VALUES(?, ?, ?, ?, 'auto')",
                    arrayOf(id, scheduleId, name, teacher.takeIf(String::isNotEmpty))
                )
                courseIds[key] = id
            }
            val startSection = item.optInt("startSection", 1).coerceIn(1, 30)
            val endSection = item.optInt("endSection", startSection).coerceIn(startSection, 30)
            db.execSQL(
                "INSERT INTO course_meetings(id, course_id, weekday, start_section, end_section, start_time, end_time, weeks_mask, location) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf(
                    UUID.randomUUID().toString(),
                    courseId,
                    item.optInt("weekday", item.optInt("day", 1)).coerceIn(1, 7),
                    startSection,
                    endSection,
                    stringValue(item, "startTime").ifBlank { stringValue(item, "start") }.takeIf(String::isNotEmpty),
                    stringValue(item, "endTime").ifBlank { stringValue(item, "end") }.takeIf(String::isNotEmpty),
                    maskFromWeeks(normalizeWeeks(item.optJSONArray("weeks"))),
                    stringValue(item, "location").ifBlank { stringValue(item, "room") }.takeIf(String::isNotEmpty)
                )
            )
            added++
        }
        return InsertStats(added, skipped)
    }

    private fun importMeta(bundle: JSONObject): ImportMeta {
        val source = stringValue(bundle, "source").ifBlank { "import" }
        val termName = stringValue(bundle, "termName").ifBlank { "导入学期" }
        val termStart = stringValue(bundle, "termStart")
        val courses = importItems(bundle)
        val metadata = bundle.optJSONObject("metadata") ?: JSONObject()
        val configRaw = stringValue(metadata, "courseConfig")
        val config = configRaw.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
        var weekCount = config?.optInt("semesterTotalWeeks", 0)?.takeIf { it > 0 } ?: 0
        if (weekCount == 0) {
            courses.forEach { item ->
                val weeks = item.optJSONArray("weeks") ?: return@forEach
                for (index in 0 until weeks.length()) weekCount = maxOf(weekCount, weeks.optInt(index))
            }
        }
        if (weekCount <= 0) weekCount = 20
        val weekStartsOn = config?.optInt("firstDayOfWeek", 1)?.coerceIn(1, 7) ?: 1
        val timeSchemeRaw = stringValue(metadata, "timeScheme").takeIf { it.isNotBlank() && parseSlots(it) != null }
        return ImportMeta(source, termName, termStart, weekCount.coerceIn(1, 64), weekStartsOn, timeSchemeRaw)
    }

    private fun importItems(bundle: JSONObject): List<JSONObject> {
        val array = bundle.optJSONArray("courses") ?: JSONArray()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.takeIf { stringValue(it, "name").isNotBlank() }
        }
    }

    private fun meetingSignature(item: JSONObject): String {
        val name = stringValue(item, "name").lowercase()
        val teacher = stringValue(item, "teacher").lowercase()
        val location = stringValue(item, "location").ifBlank { stringValue(item, "room") }.lowercase()
        val day = item.optInt("weekday", item.optInt("day", 1)).coerceIn(1, 7)
        val startSection = item.optInt("startSection", 1).coerceIn(1, 30)
        val endSection = item.optInt("endSection", startSection).coerceIn(startSection, 30)
        val weeks = normalizeWeeks(item.optJSONArray("weeks")).joinToString(",")
        return listOf(name, teacher, location, day, startSection, endSection, weeks).joinToString("\u001f")
    }

    private fun meetingsConflict(left: JSONObject, right: JSONObject): Boolean {
        val leftDay = left.optInt("weekday", left.optInt("day", 1)).coerceIn(1, 7)
        val rightDay = right.optInt("weekday", right.optInt("day", 1)).coerceIn(1, 7)
        if (leftDay != rightDay) return false
        val leftStart = left.optInt("startSection", 1).coerceIn(1, 30)
        val leftEnd = left.optInt("endSection", leftStart).coerceIn(leftStart, 30)
        val rightStart = right.optInt("startSection", 1).coerceIn(1, 30)
        val rightEnd = right.optInt("endSection", rightStart).coerceIn(rightStart, 30)
        if (leftStart > rightEnd || rightStart > leftEnd) return false
        val leftMask = maskFromWeeks(normalizeWeeks(left.optJSONArray("weeks")))
        val rightMask = maskFromWeeks(normalizeWeeks(right.optJSONArray("weeks")))
        return (leftMask and rightMask) != 0L
    }

    private fun courseKey(item: JSONObject): String = courseKey(stringValue(item, "name"), stringValue(item, "teacher"))
    private fun courseKey(name: String, teacher: String): String = "${name.trim().lowercase()}\u001f${teacher.trim().lowercase()}"

    private fun createTimeScheme(source: String, raw: String?): String? {
        if (raw.isNullOrBlank() || parseSlots(raw) == null) return null
        val id = UUID.randomUUID().toString()
        db.execSQL("INSERT INTO time_schemes(id, name, sections_json) VALUES(?, ?, ?)", arrayOf(id, "$source · 导入作息", raw))
        return id
    }

    private fun schedulePreferencesUnlocked(): JSONObject {
        val display = getSettingRawUnlocked(SCHEDULE_DISPLAY_KEY)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val result = JSONObject()
            .put("hasSchedule", false)
            .put("termName", "")
            .put("termStart", "")
            .put("weekCount", 20)
            .put("timezone", DEFAULT_TIMEZONE)
            .put("weekStartsOn", 1)
            .put("showWeekend", display.optBoolean("showWeekend", true))
            .put("showTeacher", display.optBoolean("showTeacher", true))
            .put("showRoom", display.optBoolean("showRoom", true))
            .put("showTime", display.optBoolean("showTime", true))
            .put("compactMode", display.optBoolean("compactMode", false))
            .put("defaultSections", display.optInt("defaultSections", 12).coerceIn(8, 30))
        val scheduleId = latestScheduleId() ?: return result
        db.rawQuery(
            "SELECT t.name, t.start_date, t.week_count, t.timezone, s.week_starts_on FROM schedules s JOIN terms t ON t.id=s.term_id WHERE s.id=? LIMIT 1",
            arrayOf(scheduleId)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                result.put("hasSchedule", true)
                    .put("termName", cursor.getString(0))
                    .put("termStart", cursor.getString(1))
                    .put("weekCount", cursor.getInt(2).coerceIn(1, 64))
                    .put("timezone", cursor.getString(3).takeIf { it.isNotBlank() } ?: DEFAULT_TIMEZONE)
                    .put("weekStartsOn", cursor.getInt(4).coerceIn(1, 7))
            }
        }
        return result
    }

    private fun getSettingRawUnlocked(key: String): String? = db.rawQuery("SELECT value_json FROM settings WHERE key=? LIMIT 1", arrayOf(key)).use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    private fun setSettingRawUnlocked(key: String, rawJson: String) {
        db.execSQL(
            "INSERT INTO settings(key, value_json) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value_json=excluded.value_json",
            arrayOf(key, rawJson)
        )
    }

    private fun latestScheduleId(): String? = db.rawQuery("SELECT id FROM schedules ORDER BY rowid DESC LIMIT 1", null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    private fun createManualSchedule(): String {
        val termId = UUID.randomUUID().toString()
        val scheduleId = UUID.randomUUID().toString()
        db.execSQL("INSERT INTO terms(id, name, start_date, week_count, timezone) VALUES(?, '手动课表', '', 20, ?)", arrayOf(termId, DEFAULT_TIMEZONE))
        db.execSQL("INSERT INTO schedules(id, term_id, name, week_starts_on) VALUES(?, ?, '手动课表', 1)", arrayOf(scheduleId, termId))
        return scheduleId
    }

    private fun countCourses(scheduleId: String): Int = db.rawQuery("SELECT COUNT(*) FROM courses WHERE schedule_id=?", arrayOf(scheduleId)).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    private fun countMeetings(scheduleId: String): Int = db.rawQuery(
        "SELECT COUNT(*) FROM course_meetings m JOIN courses c ON c.id=m.course_id WHERE c.schedule_id=?",
        arrayOf(scheduleId)
    ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

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

    private fun academicWeek(rawStart: String, weekCount: Int, timezone: String): Int {
        if (rawStart.isBlank()) return UNKNOWN_WEEK
        val normalized = rawStart.trim().take(10).replace('/', '-').replace('.', '-')
        val start = runCatching { LocalDate.parse(normalized) }.getOrNull() ?: return UNKNOWN_WEEK
        val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of(DEFAULT_TIMEZONE))
        val today = LocalDate.now(zone)
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

    private fun nullableDouble(obj: JSONObject, key: String): Double? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return when (val value = obj.opt(key)) {
            is Number -> value.toDouble().takeIf { it.isFinite() }
            else -> value?.toString()?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }
        }
    }

    private fun stringValue(obj: JSONObject, key: String): String {
        val value = obj.opt(key)
        if (value == null || value == JSONObject.NULL) return ""
        return value.toString().trim()
    }

    private fun colorFor(name: String): String {
        val colors = arrayOf("violet", "cyan", "amber", "blue", "green", "pink", "orange")
        return colors[(name.hashCode() and Int.MAX_VALUE) % colors.size]
    }

    private fun JSONObject.deepCopy(): JSONObject = JSONObject(toString())

    override fun close() = synchronized(lock) { db.close() }

    private data class ImportMeta(
        val source: String,
        val termName: String,
        val termStart: String,
        val weekCount: Int,
        val weekStartsOn: Int,
        val timeSchemeRaw: String?
    )

    private data class InsertStats(val added: Int, val skipped: Int)

    companion object {
        private const val UNKNOWN_WEEK = 0
        private const val OUTSIDE_TERM = -1
        private const val DEFAULT_TIMEZONE = "Asia/Shanghai"
        private const val SCHEDULE_DISPLAY_KEY = "schedule.display"
    }
}
