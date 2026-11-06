package com.lumaschedule.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Process-wide SQLite access. Every caller (bridge, reminders, widget, backup)
 * shares one connection and one lock, so writes can no longer race each other.
 */
class LumaDatabase private constructor(context: Context) {
    private val lock = Any()
    private val dbFile = File(context.filesDir, DB_NAME)
    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(dbFile, null)

    init {
        synchronized(lock) {
            db.enableWriteAheadLogging()
            db.setForeignKeyConstraintsEnabled(true)
            migrate()
        }
    }

    /** Runs [block] under the database lock. */
    fun <T> read(block: (SQLiteDatabase) -> T): T = synchronized(lock) { block(db) }

    /** Runs [block] under the database lock inside a transaction. */
    fun <T> write(block: (SQLiteDatabase) -> T): T = synchronized(lock) { transaction { block(db) } }

    private inline fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    // ------------------------------------------------------------------ schema

    private fun createSchema() {
        db.execSQL("CREATE TABLE IF NOT EXISTS terms (id TEXT PRIMARY KEY, name TEXT NOT NULL, start_date TEXT NOT NULL, week_count INTEGER NOT NULL, timezone TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS time_schemes (id TEXT PRIMARY KEY, name TEXT NOT NULL, sections_json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS schedules (id TEXT PRIMARY KEY, term_id TEXT NOT NULL REFERENCES terms(id) ON DELETE CASCADE, name TEXT NOT NULL, week_starts_on INTEGER NOT NULL DEFAULT 1, time_scheme_id TEXT REFERENCES time_schemes(id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS courses (id TEXT PRIMARY KEY, schedule_id TEXT NOT NULL REFERENCES schedules(id) ON DELETE CASCADE, name TEXT NOT NULL, code TEXT, teacher TEXT, color_token TEXT NOT NULL, note TEXT, credit REAL)")
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

    private fun migrate() = transaction {
        createSchema()
        if (!columnExists("courses", "credit")) db.execSQL("ALTER TABLE courses ADD COLUMN credit REAL")
        if (db.version < SCHEMA_VERSION) {
            migrateLegacyDataUnlocked(fromVersion = db.version)
            db.version = SCHEMA_VERSION
        }
    }

    private fun columnExists(table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(nameIndex) == column) found = true
            found
        }

    /**
     * Folds data written by older builds into the current schema: in place on
     * upgrade, and after restoring a backup written with schema [fromVersion].
     * Safe to run repeatedly.
     */
    fun runLegacyMigrations(fromVersion: Int) = synchronized(lock) { transaction { migrateLegacyDataUnlocked(fromVersion) } }

    private fun migrateLegacyDataUnlocked(fromVersion: Int) {
        getSettingRawUnlocked(LEGACY_CREDIT_INDEX_KEY)?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()?.let { index -> applyCreditIndexUnlocked(index) }
        }
        db.execSQL("DELETE FROM settings WHERE key=? OR key LIKE ?", arrayOf(LEGACY_CREDIT_INDEX_KEY, "$LEGACY_CREDIT_META_PREFIX%"))

        val legacyWeekend = getSettingRawUnlocked(LEGACY_WEEKEND_KEY)?.trim()?.removeSurrounding("\"")
        if (legacyWeekend != null) {
            val display = getSettingRawUnlocked(DISPLAY_KEY)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            if (!display.has("weekendMode") && legacyWeekend in WEEKEND_MODES) display.put("weekendMode", legacyWeekend)
            setSettingRawUnlocked(DISPLAY_KEY, display.toString())
            deleteSettingUnlocked(LEGACY_WEEKEND_KEY)
        }
        deleteSettingUnlocked("appearance.glass")

        if (fromVersion < 2) {
            // v1 named imported schedules "<term> · 导入课表" and the hand-made one "手动课表".
            db.execSQL(
                "UPDATE schedules SET name=(SELECT t.name FROM terms t WHERE t.id=schedules.term_id) " +
                    "WHERE name LIKE '% · 导入课表' AND (SELECT t.name FROM terms t WHERE t.id=schedules.term_id) NOT IN ('', '导入学期', '手动课表')"
            )
            db.execSQL("UPDATE schedules SET name='我的课表' WHERE name='手动课表'")
        }
    }

    /**
     * Applies a legacy name → credit index to courses that have no credit yet. Every
     * entry is also kept in [CREDIT_FALLBACK_KEY]: grade records are matched by course
     * name across terms, and the course an entry belonged to may already be gone
     * (an overwritten term) or be deleted later. Existing fallback values win, which
     * keeps the old precedence of the native index over the WebView copy.
     */
    private fun applyCreditIndexUnlocked(index: JSONObject): Int {
        val normalized = HashMap<String, Double>()
        index.keys().forEach { key ->
            val value = index.optDouble(key, Double.NaN)
            val name = ScheduleMath.normalizedName(key)
            if (name.isNotEmpty() && value.isFinite() && value in 0.0..30.0) normalized[name] = value
        }
        if (normalized.isEmpty()) return 0
        val fallback = creditFallbackUnlocked()
        var fallbackChanged = false
        normalized.forEach { (name, credit) -> if (fallback.putIfAbsent(name, credit) == null) fallbackChanged = true }
        if (fallbackChanged) setSettingRawUnlocked(CREDIT_FALLBACK_KEY, JSONObject(fallback.toMap()).toString())
        var updated = 0
        val targets = ArrayList<Pair<String, Double>>()
        db.rawQuery("SELECT id, name FROM courses WHERE credit IS NULL", null).use { cursor ->
            while (cursor.moveToNext()) {
                normalized[ScheduleMath.normalizedName(cursor.getString(1))]?.let { targets += cursor.getString(0) to it }
            }
        }
        targets.forEach { (id, credit) ->
            db.execSQL("UPDATE courses SET credit=? WHERE id=?", arrayOf(credit, id))
            updated++
        }
        return updated
    }

    /** Moves the WebView's legacy credit store into SQLite; returns only after everything is committed. */
    fun migrateLegacyCredits(byName: JSONObject): Int = synchronized(lock) { transaction { applyCreditIndexUnlocked(byName) } }

    private fun creditFallbackUnlocked(): HashMap<String, Double> {
        val result = HashMap<String, Double>()
        val json = getSettingRawUnlocked(CREDIT_FALLBACK_KEY)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return result
        json.keys().forEach { key ->
            val value = json.optDouble(key, Double.NaN)
            if (key.isNotEmpty() && value.isFinite() && value in 0.0..30.0) result[key] = value
        }
        return result
    }

    // ------------------------------------------------------------------ settings

    fun getSettingRaw(key: String): String? = synchronized(lock) { getSettingRawUnlocked(key) }

    fun setSettingRaw(key: String, rawJson: String) = synchronized(lock) { setSettingRawUnlocked(key, rawJson) }

    private fun getSettingRawUnlocked(key: String): String? =
        db.rawQuery("SELECT value_json FROM settings WHERE key=? LIMIT 1", arrayOf(key)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun setSettingRawUnlocked(key: String, rawJson: String) {
        db.execSQL(
            "INSERT INTO settings(key, value_json) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value_json=excluded.value_json",
            arrayOf(key, rawJson)
        )
    }

    private fun deleteSettingUnlocked(key: String) {
        db.execSQL("DELETE FROM settings WHERE key=?", arrayOf(key))
    }

    fun appearance(): JSONObject? = getSettingRaw(APPEARANCE_KEY)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun saveAppearance(input: JSONObject) {
        val theme = input.optString("theme").takeIf { it in setOf("system", "light", "dark") } ?: "system"
        val accent = input.optString("accent").takeIf { it.matches(Regex("^[a-z]{2,16}$")) } ?: "luma"
        setSettingRaw(
            APPEARANCE_KEY,
            JSONObject().put("theme", theme).put("accent", accent).put("colorfulCourses", input.optBoolean("colorfulCourses", false)).toString()
        )
    }

    fun reminderSettings(): JSONObject {
        val stored = getSettingRaw(REMINDER_KEY)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        return JSONObject()
            .put("enabled", stored.optBoolean("enabled", false))
            .put("offsetMinutes", stored.optInt("offsetMinutes", 15).coerceIn(0, 180))
    }

    fun saveReminderSettings(input: JSONObject): JSONObject {
        val normalized = JSONObject()
            .put("enabled", input.optBoolean("enabled", false))
            .put("offsetMinutes", input.optInt("offsetMinutes", 15).coerceIn(0, 180))
        setSettingRaw(REMINDER_KEY, normalized.toString())
        return normalized
    }

    fun remindersEnabled(): Boolean = reminderSettings().optBoolean("enabled", false)

    // ------------------------------------------------------------------ display preferences

    fun displayPreferences(): JSONObject = synchronized(lock) { displayPreferencesUnlocked() }

    private fun displayPreferencesUnlocked(): JSONObject {
        val stored = getSettingRawUnlocked(DISPLAY_KEY)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val legacyMode = if (stored.has("showWeekend") && !stored.optBoolean("showWeekend", true)) "weekdays" else "auto"
        return JSONObject()
            .put("weekendMode", stored.optString("weekendMode").takeIf { it in WEEKEND_MODES } ?: legacyMode)
            .put("showTeacher", stored.optBoolean("showTeacher", true))
            .put("showRoom", stored.optBoolean("showRoom", true))
            .put("showTime", stored.optBoolean("showTime", true))
            .put("compactMode", stored.optBoolean("compactMode", false))
            .put("defaultSections", stored.optInt("defaultSections", 12).coerceIn(1, ScheduleMath.MAX_SECTIONS))
    }

    fun saveDisplayPreferences(input: JSONObject): JSONObject = synchronized(lock) {
        val normalized = JSONObject()
            .put("weekendMode", input.optString("weekendMode").takeIf { it in WEEKEND_MODES } ?: "auto")
            .put("showTeacher", input.optBoolean("showTeacher", true))
            .put("showRoom", input.optBoolean("showRoom", true))
            .put("showTime", input.optBoolean("showTime", true))
            .put("compactMode", input.optBoolean("compactMode", false))
            .put("defaultSections", input.optInt("defaultSections", 12).coerceIn(1, ScheduleMath.MAX_SECTIONS))
        setSettingRawUnlocked(DISPLAY_KEY, normalized.toString())
        normalized
    }

    // ------------------------------------------------------------------ schedules

    private data class ScheduleRow(
        val id: String,
        val name: String,
        val termId: String,
        val termName: String,
        val termStart: String,
        val weekCount: Int,
        val timezone: String,
        val weekStartsOn: Int,
        val timeSchemeId: String?,
        val sectionsJson: String
    )

    private fun scheduleRow(id: String): ScheduleRow? = db.rawQuery(
        "SELECT s.id, s.name, t.id, t.name, t.start_date, t.week_count, t.timezone, s.week_starts_on, s.time_scheme_id, COALESCE(ts.sections_json, '') " +
            "FROM schedules s JOIN terms t ON t.id=s.term_id LEFT JOIN time_schemes ts ON ts.id=s.time_scheme_id WHERE s.id=? LIMIT 1",
        arrayOf(id)
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else ScheduleRow(
            id = cursor.getString(0),
            name = cursor.getString(1),
            termId = cursor.getString(2),
            termName = cursor.getString(3).takeUnless { it in PLACEHOLDER_TERM_NAMES }.orEmpty(),
            termStart = ScheduleMath.parseDate(cursor.getString(4))?.toString().orEmpty(),
            weekCount = cursor.getInt(5).coerceIn(1, ScheduleMath.MAX_WEEKS),
            timezone = cursor.getString(6).takeIf { zone -> runCatching { ZoneId.of(zone) }.isSuccess } ?: DEFAULT_TIMEZONE,
            weekStartsOn = ScheduleMath.normalizeWeekStart(cursor.getInt(7)),
            timeSchemeId = if (cursor.isNull(8)) null else cursor.getString(8),
            sectionsJson = cursor.getString(9)
        )
    }

    private fun scheduleExists(id: String): Boolean =
        db.rawQuery("SELECT 1 FROM schedules WHERE id=? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }

    private fun activeScheduleIdUnlocked(): String? {
        val stored = getSettingRawUnlocked(ACTIVE_SCHEDULE_KEY)?.trim()?.removeSurrounding("\"")
        if (!stored.isNullOrBlank() && scheduleExists(stored)) return stored
        return db.rawQuery("SELECT id FROM schedules ORDER BY rowid DESC LIMIT 1", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun setActiveUnlocked(id: String) = setSettingRawUnlocked(ACTIVE_SCHEDULE_KEY, JSONObject.quote(id))

    fun activeScheduleId(): String? = synchronized(lock) { activeScheduleIdUnlocked() }

    private fun createScheduleUnlocked(name: String, termName: String, termStart: String, weekCount: Int, weekStartsOn: Int, timezone: String, sectionsJson: String?): String {
        val termId = UUID.randomUUID().toString()
        val scheduleId = UUID.randomUUID().toString()
        db.execSQL(
            "INSERT INTO terms(id, name, start_date, week_count, timezone) VALUES(?, ?, ?, ?, ?)",
            arrayOf(termId, termName, termStart, weekCount.coerceIn(1, ScheduleMath.MAX_WEEKS), timezone)
        )
        val schemeId = sectionsJson?.takeIf { ScheduleMath.parseSlots(it).isNotEmpty() }?.let { insertTimeScheme(name, it) }
        db.execSQL(
            "INSERT INTO schedules(id, term_id, name, week_starts_on, time_scheme_id) VALUES(?, ?, ?, ?, ?)",
            arrayOf(scheduleId, termId, name.ifBlank { "我的课表" }, ScheduleMath.normalizeWeekStart(weekStartsOn), schemeId)
        )
        setActiveUnlocked(scheduleId)
        return scheduleId
    }

    private fun ensureActiveScheduleUnlocked(): String = activeScheduleIdUnlocked()
        ?: createScheduleUnlocked("我的课表", "", "", 20, 1, DEFAULT_TIMEZONE, null)

    private fun insertTimeScheme(name: String, sectionsJson: String): String {
        val id = UUID.randomUUID().toString()
        db.execSQL("INSERT INTO time_schemes(id, name, sections_json) VALUES(?, ?, ?)", arrayOf(id, "$name · 作息", sectionsJson))
        return id
    }

    private fun replaceTimeSchemeUnlocked(row: ScheduleRow, sectionsJson: String) {
        val sharedWithOthers = row.timeSchemeId != null && db.rawQuery(
            "SELECT COUNT(*) FROM schedules WHERE time_scheme_id=? AND id<>?",
            arrayOf(row.timeSchemeId, row.id)
        ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) > 0 }
        if (row.timeSchemeId != null && !sharedWithOthers) {
            db.execSQL("UPDATE time_schemes SET sections_json=? WHERE id=?", arrayOf(sectionsJson, row.timeSchemeId))
        } else {
            val schemeId = insertTimeScheme(row.name, sectionsJson)
            db.execSQL("UPDATE schedules SET time_scheme_id=? WHERE id=?", arrayOf(schemeId, row.id))
        }
    }

    fun listSchedules(): JSONArray = synchronized(lock) {
        val activeId = activeScheduleIdUnlocked()
        val result = JSONArray()
        db.rawQuery(
            "SELECT s.id, s.name, t.name, t.start_date, " +
                "(SELECT COUNT(*) FROM courses c WHERE c.schedule_id=s.id), " +
                "(SELECT COUNT(*) FROM course_meetings m JOIN courses c ON c.id=m.course_id WHERE c.schedule_id=s.id) " +
                "FROM schedules s JOIN terms t ON t.id=s.term_id ORDER BY s.rowid DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                result.put(
                    JSONObject()
                        .put("id", id)
                        .put("name", cursor.getString(1))
                        .put("termName", cursor.getString(2).takeUnless { it in PLACEHOLDER_TERM_NAMES }.orEmpty())
                        .put("termStart", ScheduleMath.parseDate(cursor.getString(3))?.toString().orEmpty())
                        .put("courseCount", cursor.getInt(4))
                        .put("meetingCount", cursor.getInt(5))
                        .put("active", id == activeId)
                )
            }
        }
        result
    }

    fun activateSchedule(id: String) = synchronized(lock) {
        require(scheduleExists(id)) { "找不到这份课表" }
        setActiveUnlocked(id)
    }

    fun renameSchedule(id: String, name: String) = synchronized(lock) {
        val trimmed = name.trim().take(60)
        require(trimmed.isNotEmpty()) { "名称不能为空" }
        require(scheduleExists(id)) { "找不到这份课表" }
        db.execSQL("UPDATE schedules SET name=? WHERE id=?", arrayOf(trimmed, id))
    }

    fun createSchedule(name: String): String = synchronized(lock) {
        transaction {
            val trimmed = name.trim().take(60)
            require(trimmed.isNotEmpty()) { "名称不能为空" }
            val current = activeScheduleIdUnlocked()?.let(::scheduleRow)
            createScheduleUnlocked(
                trimmed,
                trimmed,
                "",
                current?.weekCount ?: 20,
                current?.weekStartsOn ?: 1,
                current?.timezone ?: DEFAULT_TIMEZONE,
                current?.sectionsJson
            )
        }
    }

    fun deleteSchedule(id: String) = synchronized(lock) {
        transaction {
            val row = scheduleRow(id) ?: error("找不到这份课表")
            db.execSQL("DELETE FROM schedules WHERE id=?", arrayOf(id))
            db.execSQL("DELETE FROM terms WHERE id=? AND NOT EXISTS (SELECT 1 FROM schedules WHERE term_id=?)", arrayOf(row.termId, row.termId))
            row.timeSchemeId?.let { scheme ->
                db.execSQL("DELETE FROM time_schemes WHERE id=? AND NOT EXISTS (SELECT 1 FROM schedules WHERE time_scheme_id=?)", arrayOf(scheme, scheme))
            }
            val stored = getSettingRawUnlocked(ACTIVE_SCHEDULE_KEY)?.trim()?.removeSurrounding("\"")
            if (stored == id) deleteSettingUnlocked(ACTIVE_SCHEDULE_KEY)
        }
    }

    // ------------------------------------------------------------------ semester snapshot

    fun semester(): JSONObject = synchronized(lock) { semesterUnlocked() }

    private fun semesterUnlocked(): JSONObject {
        val row = activeScheduleIdUnlocked()?.let(::scheduleRow) ?: return JSONObject()
            .put("hasSchedule", false)
            .put("scheduleId", JSONObject.NULL)
            .put("scheduleName", "")
            .put("termName", "")
            .put("termStart", "")
            .put("weekCount", 20)
            .put("weekStartsOn", 1)
            .put("timezone", DEFAULT_TIMEZONE)
            .put("timeSlots", JSONArray())
            .put("courses", JSONArray())

        val slots = JSONArray()
        ScheduleMath.parseSlots(row.sectionsJson).forEach { slot ->
            slots.put(JSONObject().put("section", slot.section).put("start", slot.start).put("end", slot.end))
        }
        return JSONObject()
            .put("hasSchedule", true)
            .put("scheduleId", row.id)
            .put("scheduleName", row.name)
            .put("termName", row.termName)
            .put("termStart", row.termStart)
            .put("weekCount", row.weekCount)
            .put("weekStartsOn", row.weekStartsOn)
            .put("timezone", row.timezone)
            .put("timeSlots", slots)
            .put("courses", JSONArray(courseJson(row.id)))
    }

    private fun courseJson(scheduleId: String): List<JSONObject> {
        val result = ArrayList<JSONObject>()
        db.rawQuery(
            "SELECT m.id, c.id, c.name, COALESCE(c.teacher,''), COALESCE(m.location,''), COALESCE(m.start_time,''), COALESCE(m.end_time,''), " +
                "m.weekday, c.color_token, m.start_section, m.end_section, m.weeks_mask, c.credit " +
                "FROM courses c JOIN course_meetings m ON m.course_id=c.id WHERE c.schedule_id=? ORDER BY m.weekday, m.start_section, c.name",
            arrayOf(scheduleId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(2)
                val token = cursor.getString(8)
                val custom = token in ScheduleMath.PALETTE
                result += JSONObject()
                    .put("id", cursor.getString(0))
                    .put("courseId", cursor.getString(1))
                    .put("name", name)
                    .put("teacher", cursor.getString(3))
                    .put("room", cursor.getString(4))
                    .put("startTime", ScheduleMath.normalizeTime(cursor.getString(5)))
                    .put("endTime", ScheduleMath.normalizeTime(cursor.getString(6)))
                    .put("day", cursor.getInt(7).coerceIn(1, 7))
                    .put("color", if (custom) token else ScheduleMath.colorFor(name))
                    .put("customColor", custom)
                    .put("startSection", cursor.getInt(9))
                    .put("endSection", cursor.getInt(10))
                    .put("weeks", JSONArray(ScheduleMath.weeksFromMask(cursor.getLong(11))))
                    .put("credit", if (cursor.isNull(12)) JSONObject.NULL else cursor.getDouble(12))
            }
        }
        return result
    }

    /** Everything reminders, the widget and ICS export need about the active schedule. */
    data class CalendarSource(
        val scheduleName: String,
        val termName: String,
        val termStart: LocalDate?,
        val weekCount: Int,
        val weekStartsOn: Int,
        val zone: ZoneId,
        val slots: List<ScheduleMath.Slot>,
        val meetings: List<ScheduleMath.Meeting>
    )

    fun calendarSource(): CalendarSource? = synchronized(lock) {
        val row = activeScheduleIdUnlocked()?.let(::scheduleRow) ?: return@synchronized null
        val meetings = ArrayList<ScheduleMath.Meeting>()
        db.rawQuery(
            "SELECT m.id, c.name, COALESCE(c.teacher,''), COALESCE(m.location,''), m.weekday, m.start_section, m.end_section, " +
                "COALESCE(m.start_time,''), COALESCE(m.end_time,''), m.weeks_mask FROM courses c JOIN course_meetings m ON m.course_id=c.id " +
                "WHERE c.schedule_id=? ORDER BY m.weekday, m.start_section, c.name",
            arrayOf(row.id)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                meetings += ScheduleMath.Meeting(
                    id = cursor.getString(0),
                    name = cursor.getString(1),
                    teacher = cursor.getString(2),
                    room = cursor.getString(3),
                    day = cursor.getInt(4).coerceIn(1, 7),
                    startSection = cursor.getInt(5),
                    endSection = cursor.getInt(6),
                    startTime = cursor.getString(7),
                    endTime = cursor.getString(8),
                    weeksMask = cursor.getLong(9)
                )
            }
        }
        CalendarSource(
            scheduleName = row.name,
            termName = row.termName,
            termStart = ScheduleMath.parseDate(row.termStart),
            weekCount = row.weekCount,
            weekStartsOn = row.weekStartsOn,
            zone = ZoneId.of(row.timezone),
            slots = ScheduleMath.parseSlots(row.sectionsJson),
            meetings = meetings
        )
    }

    // ------------------------------------------------------------------ term & time slots

    fun saveTermSettings(input: JSONObject): JSONObject = synchronized(lock) {
        val termName = stringValue(input, "termName").take(60)
        val termStartRaw = stringValue(input, "termStart")
        val termStart = if (termStartRaw.isBlank()) "" else ScheduleMath.parseDate(termStartRaw)?.toString() ?: error("开学日期格式应为 YYYY-MM-DD")
        val weekCount = input.optInt("weekCount", 20)
        require(weekCount in 1..ScheduleMath.MAX_WEEKS) { "总周数需要在 1 到 64 之间" }
        val weekStartsOn = ScheduleMath.normalizeWeekStart(input.optInt("weekStartsOn", 1))
        val timezone = stringValue(input, "timezone").ifBlank { DEFAULT_TIMEZONE }
        require(runCatching { ZoneId.of(timezone) }.isSuccess) { "无效的时区：$timezone" }

        transaction {
            val id = activeScheduleIdUnlocked()
                ?: createScheduleUnlocked(termName.ifBlank { "我的课表" }, termName, termStart, weekCount, weekStartsOn, timezone, null)
            val row = scheduleRow(id) ?: error("找不到当前课表")
            db.execSQL(
                "UPDATE terms SET name=?, start_date=?, week_count=?, timezone=? WHERE id=?",
                arrayOf(termName, termStart, weekCount, timezone, row.termId)
            )
            db.execSQL("UPDATE schedules SET week_starts_on=? WHERE id=?", arrayOf(weekStartsOn, id))
        }
        semesterUnlocked()
    }

    fun saveTimeSlots(input: JSONArray): JSONObject = synchronized(lock) {
        val slots = ArrayList<ScheduleMath.Slot>()
        for (index in 0 until input.length()) {
            val item = input.optJSONObject(index) ?: continue
            val start = ScheduleMath.normalizeTime(item.optString("start"))
            val end = ScheduleMath.normalizeTime(item.optString("end"))
            require(start.isNotEmpty() && end.isNotEmpty()) { "第 ${index + 1} 节时间不完整" }
            require(end > start) { "第 ${index + 1} 节的结束时间需要晚于开始时间" }
            slots += ScheduleMath.Slot(index + 1, start, end)
        }
        require(slots.size in 1..ScheduleMath.MAX_SECTIONS) { "节数需要在 1 到 30 之间" }
        transaction {
            val id = ensureActiveScheduleUnlocked()
            val row = scheduleRow(id) ?: error("找不到当前课表")
            replaceTimeSchemeUnlocked(row, ScheduleMath.slotsJson(slots))
        }
        semesterUnlocked()
    }

    // ------------------------------------------------------------------ courses

    fun saveCourse(input: JSONObject): String = synchronized(lock) {
        val name = stringValue(input, "name").take(80)
        require(name.isNotEmpty()) { "课程名称不能为空" }
        val day = input.optInt("day")
        require(day in 1..7) { "星期必须在 1 到 7 之间" }
        val startSection = input.optInt("startSection")
        val endSection = input.optInt("endSection")
        require(startSection in 1..ScheduleMath.MAX_SECTIONS && endSection in startSection..ScheduleMath.MAX_SECTIONS) { "课程节次范围无效" }
        val teacher = stringValue(input, "teacher").take(80)
        val room = stringValue(input, "room").take(80)
        val start = ScheduleMath.normalizeTime(stringValue(input, "startTime").ifBlank { stringValue(input, "start") })
        val end = ScheduleMath.normalizeTime(stringValue(input, "endTime").ifBlank { stringValue(input, "end") })
        require((start.isEmpty() && end.isEmpty()) || (start.isNotEmpty() && end.isNotEmpty() && end > start)) { "开始和结束时间需要同时填写，且结束晚于开始" }
        val weeks = normalizeWeeks(input.optJSONArray("weeks"), allowEmpty = false)
        val credit = creditValue(input)
        val color = stringValue(input, "color").takeIf { it in ScheduleMath.PALETTE } ?: "auto"

        transaction {
            val scheduleId = ensureActiveScheduleUnlocked()
            val incomingId = stringValue(input, "id").takeIf { it.isNotEmpty() }
            if (incomingId != null) {
                val courseId = db.rawQuery(
                    "SELECT c.id FROM course_meetings m JOIN courses c ON c.id=m.course_id WHERE m.id=? AND c.schedule_id=? LIMIT 1",
                    arrayOf(incomingId, scheduleId)
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: error("找不到要编辑的课程时段")
                db.execSQL(
                    "UPDATE courses SET name=?, teacher=?, credit=?, color_token=? WHERE id=?",
                    arrayOf(name, teacher.takeIf(String::isNotEmpty), credit, color, courseId)
                )
                db.execSQL(
                    "UPDATE course_meetings SET weekday=?, start_section=?, end_section=?, start_time=?, end_time=?, weeks_mask=?, location=? WHERE id=?",
                    arrayOf(day, startSection, endSection, start.takeIf(String::isNotEmpty), end.takeIf(String::isNotEmpty), ScheduleMath.maskFromWeeks(weeks), room.takeIf(String::isNotEmpty), incomingId)
                )
                incomingId
            } else {
                val existingCourse = db.rawQuery(
                    "SELECT id FROM courses WHERE schedule_id=? AND lower(trim(name))=? AND lower(trim(COALESCE(teacher,'')))=? LIMIT 1",
                    arrayOf(scheduleId, name.lowercase(), teacher.lowercase())
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                val courseId = existingCourse ?: UUID.randomUUID().toString().also { id ->
                    db.execSQL(
                        "INSERT INTO courses(id, schedule_id, name, teacher, color_token, credit) VALUES(?, ?, ?, ?, ?, ?)",
                        arrayOf(id, scheduleId, name, teacher.takeIf(String::isNotEmpty), color, credit)
                    )
                }
                if (existingCourse != null) {
                    // The new meeting joins an existing course: keep its credit and colour unless new ones were chosen.
                    db.execSQL(
                        "UPDATE courses SET credit=COALESCE(?, credit), color_token=CASE WHEN ?='auto' THEN color_token ELSE ? END WHERE id=?",
                        arrayOf(credit, color, color, courseId)
                    )
                }
                val meetingId = UUID.randomUUID().toString()
                db.execSQL(
                    "INSERT INTO course_meetings(id, course_id, weekday, start_section, end_section, start_time, end_time, weeks_mask, location) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(meetingId, courseId, day, startSection, endSection, start.takeIf(String::isNotEmpty), end.takeIf(String::isNotEmpty), ScheduleMath.maskFromWeeks(weeks), room.takeIf(String::isNotEmpty))
                )
                meetingId
            }
        }
    }

    fun deleteCourse(meetingId: String) = synchronized(lock) {
        transaction {
            val courseId = db.rawQuery("SELECT course_id FROM course_meetings WHERE id=? LIMIT 1", arrayOf(meetingId)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: error("找不到要删除的课程时段")
            db.execSQL("DELETE FROM course_meetings WHERE id=?", arrayOf(meetingId))
            db.execSQL("DELETE FROM courses WHERE id=? AND NOT EXISTS (SELECT 1 FROM course_meetings WHERE course_id=?)", arrayOf(courseId, courseId))
        }
    }

    // ------------------------------------------------------------------ import

    fun previewImport(bundle: JSONObject): JSONObject = synchronized(lock) {
        val incoming = importItems(bundle)
        val scheduleId = activeScheduleIdUnlocked()
        val existing = scheduleId?.let(::courseJson).orEmpty()
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
                if (existing.any { other -> meetingsConflict(item, other) }) conflictCount++
            }
        }
        JSONObject()
            .put("hasExistingSchedule", scheduleId != null)
            .put("existingCourseCount", scheduleId?.let(::countCourses) ?: 0)
            .put("existingMeetingCount", existing.size)
            .put("incomingCourseCount", incoming.map(::courseKey).toSet().size)
            .put("incomingMeetingCount", incoming.size)
            .put("newCount", newCount)
            .put("duplicateCount", duplicateCount)
            .put("conflictCount", conflictCount)
            .put("removeCount", existingSignatures.count { it !in incomingSignatures })
    }

    fun commitImport(bundle: JSONObject, requestedMode: String): JSONObject = synchronized(lock) {
        val mode = requestedMode.lowercase().takeIf { it in setOf("new", "merge", "overwrite") } ?: "new"
        require(importItems(bundle).isNotEmpty()) { "没有可以导入的课程" }
        transaction {
            val existing = activeScheduleIdUnlocked()?.let(::scheduleRow)
            val result = when {
                existing == null || mode == "new" -> createImported(bundle)
                mode == "merge" -> mergeImported(existing, bundle)
                else -> overwriteImported(existing, bundle)
            }
            db.execSQL("INSERT INTO import_audit(source, summary_json) VALUES(?, ?)", arrayOf(importMeta(bundle).source, result.toString()))
            result
        }
    }

    private data class ImportMeta(
        val source: String,
        val termName: String,
        val termStart: String,
        val weekCount: Int,
        val weekStartsOn: Int?,
        val timeScheme: String?
    )

    private fun importMeta(bundle: JSONObject): ImportMeta {
        val source = stringValue(bundle, "source").ifBlank { "导入" }.take(60)
        val metadata = bundle.optJSONObject("metadata") ?: JSONObject()
        val config = stringValue(metadata, "courseConfig").takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
        val termName = stringValue(bundle, "termName").takeUnless { it in PLACEHOLDER_TERM_NAMES }.orEmpty().take(60)
        val termStart = (ScheduleMath.parseDate(stringValue(bundle, "termStart")) ?: ScheduleMath.parseDate(config?.optString("semesterStartDate")))?.toString().orEmpty()
        var weekCount = config?.optInt("semesterTotalWeeks", 0)?.takeIf { it in 1..ScheduleMath.MAX_WEEKS } ?: 0
        if (weekCount == 0) {
            importItems(bundle).forEach { item ->
                val weeks = item.optJSONArray("weeks") ?: return@forEach
                for (index in 0 until weeks.length()) weekCount = maxOf(weekCount, weeks.optInt(index).coerceIn(0, ScheduleMath.MAX_WEEKS))
            }
        }
        val weekStartsOn = config?.takeIf { it.has("firstDayOfWeek") }?.optInt("firstDayOfWeek", 1)?.let(ScheduleMath::normalizeWeekStart)
        val slots = ScheduleMath.parseSlots(stringValue(metadata, "timeScheme"))
        return ImportMeta(source, termName, termStart, weekCount, weekStartsOn, slots.takeIf { it.isNotEmpty() }?.let(ScheduleMath::slotsJson))
    }

    private fun createImported(bundle: JSONObject): JSONObject {
        val meta = importMeta(bundle)
        val name = meta.termName.ifBlank { "${meta.source.removePrefix("Luma · ")} 课表" }
        val scheduleId = createScheduleUnlocked(
            name,
            meta.termName,
            meta.termStart,
            meta.weekCount.takeIf { it > 0 } ?: 20,
            meta.weekStartsOn ?: 1,
            DEFAULT_TIMEZONE,
            meta.timeScheme
        )
        val stats = insertImportedMeetings(scheduleId, importItems(bundle), emptySet())
        return importResult("new", scheduleId, stats, 0)
    }

    private fun mergeImported(row: ScheduleRow, bundle: JSONObject): JSONObject {
        val meta = importMeta(bundle)
        db.execSQL(
            "UPDATE terms SET name=?, start_date=?, week_count=? WHERE id=?",
            arrayOf(
                row.termName.ifBlank { meta.termName },
                row.termStart.ifBlank { meta.termStart },
                maxOf(row.weekCount, meta.weekCount).coerceIn(1, ScheduleMath.MAX_WEEKS),
                row.termId
            )
        )
        if (ScheduleMath.parseSlots(row.sectionsJson).isEmpty() && meta.timeScheme != null) replaceTimeSchemeUnlocked(row, meta.timeScheme)
        val signatures = courseJson(row.id).map(::meetingSignature).toSet()
        val stats = insertImportedMeetings(row.id, importItems(bundle), signatures)
        return importResult("merge", row.id, stats, 0)
    }

    private fun overwriteImported(row: ScheduleRow, bundle: JSONObject): JSONObject {
        val meta = importMeta(bundle)
        val removed = countMeetings(row.id)
        db.execSQL("DELETE FROM courses WHERE schedule_id=?", arrayOf(row.id))
        // Only replace term facts the import actually carries; a plain CSV must not wipe the start date.
        db.execSQL(
            "UPDATE terms SET name=?, start_date=?, week_count=? WHERE id=?",
            arrayOf(
                meta.termName.ifBlank { row.termName },
                meta.termStart.ifBlank { row.termStart },
                (meta.weekCount.takeIf { it > 0 } ?: row.weekCount).coerceIn(1, ScheduleMath.MAX_WEEKS),
                row.termId
            )
        )
        meta.weekStartsOn?.let { db.execSQL("UPDATE schedules SET week_starts_on=? WHERE id=?", arrayOf(it, row.id)) }
        meta.timeScheme?.let { replaceTimeSchemeUnlocked(row, it) }
        val stats = insertImportedMeetings(row.id, importItems(bundle), emptySet())
        return importResult("overwrite", row.id, stats, removed)
    }

    private data class InsertStats(val added: Int, val skipped: Int)

    private fun importResult(mode: String, scheduleId: String, stats: InsertStats, removed: Int): JSONObject {
        val termId = db.rawQuery("SELECT term_id FROM schedules WHERE id=?", arrayOf(scheduleId)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else ""
        }
        return JSONObject()
            .put("mode", mode)
            .put("termId", termId)
            .put("scheduleId", scheduleId)
            .put("courseCount", countCourses(scheduleId))
            .put("meetingCount", countMeetings(scheduleId))
            .put("addedCount", stats.added)
            .put("skippedCount", stats.skipped)
            .put("removedCount", removed)
    }

    private fun insertImportedMeetings(scheduleId: String, items: List<JSONObject>, skipSignatures: Set<String>): InsertStats {
        val courseIds = HashMap<String, String>()
        db.rawQuery("SELECT id, name, COALESCE(teacher,'') FROM courses WHERE schedule_id=?", arrayOf(scheduleId)).use { cursor ->
            while (cursor.moveToNext()) courseIds[courseKey(cursor.getString(1), cursor.getString(2))] = cursor.getString(0)
        }
        val seen = HashSet<String>()
        var added = 0
        var skipped = 0
        items.forEach { item ->
            val signature = meetingSignature(item)
            if (!seen.add(signature) || signature in skipSignatures) {
                skipped++
                return@forEach
            }
            val name = stringValue(item, "name").take(80)
            val teacher = stringValue(item, "teacher").take(80)
            val credit = creditValue(item)
            val key = courseKey(name, teacher)
            val courseId = courseIds[key] ?: UUID.randomUUID().toString().also { id ->
                db.execSQL(
                    "INSERT INTO courses(id, schedule_id, name, teacher, color_token, credit) VALUES(?, ?, ?, ?, 'auto', ?)",
                    arrayOf(id, scheduleId, name, teacher.takeIf(String::isNotEmpty), credit)
                )
                courseIds[key] = id
            }
            if (credit != null) db.execSQL("UPDATE courses SET credit=? WHERE id=? AND credit IS NULL", arrayOf(credit, courseId))
            val startSection = item.optInt("startSection", 1).coerceIn(1, ScheduleMath.MAX_SECTIONS)
            val endSection = item.optInt("endSection", startSection).coerceIn(startSection, ScheduleMath.MAX_SECTIONS)
            val start = ScheduleMath.normalizeTime(stringValue(item, "startTime").ifBlank { stringValue(item, "start") })
            val end = ScheduleMath.normalizeTime(stringValue(item, "endTime").ifBlank { stringValue(item, "end") })
            db.execSQL(
                "INSERT INTO course_meetings(id, course_id, weekday, start_section, end_section, start_time, end_time, weeks_mask, location) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf(
                    UUID.randomUUID().toString(),
                    courseId,
                    item.optInt("weekday", item.optInt("day", 1)).coerceIn(1, 7),
                    startSection,
                    endSection,
                    start.takeIf { it.isNotEmpty() && end.isNotEmpty() && end > start },
                    end.takeIf { it.isNotEmpty() && start.isNotEmpty() && end > start },
                    ScheduleMath.maskFromWeeks(normalizeWeeks(item.optJSONArray("weeks"), allowEmpty = false)),
                    stringValue(item, "location").ifBlank { stringValue(item, "room") }.take(80).takeIf(String::isNotEmpty)
                )
            )
            added++
        }
        return InsertStats(added, skipped)
    }

    private fun importItems(bundle: JSONObject): List<JSONObject> {
        val array = bundle.optJSONArray("courses") ?: JSONArray()
        return (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.takeIf { stringValue(it, "name").isNotBlank() } }
    }

    private fun meetingSignature(item: JSONObject): String {
        val name = stringValue(item, "name").lowercase()
        val teacher = stringValue(item, "teacher").lowercase()
        val location = stringValue(item, "location").ifBlank { stringValue(item, "room") }.lowercase()
        val day = item.optInt("weekday", item.optInt("day", 1)).coerceIn(1, 7)
        val startSection = item.optInt("startSection", 1).coerceIn(1, ScheduleMath.MAX_SECTIONS)
        val endSection = item.optInt("endSection", startSection).coerceIn(startSection, ScheduleMath.MAX_SECTIONS)
        val weeks = normalizeWeeks(item.optJSONArray("weeks"), allowEmpty = false).joinToString(",")
        return listOf(name, teacher, location, day, startSection, endSection, weeks).joinToString("\u001f")
    }

    private fun meetingsConflict(left: JSONObject, right: JSONObject): Boolean {
        val leftDay = left.optInt("weekday", left.optInt("day", 1))
        val rightDay = right.optInt("weekday", right.optInt("day", 1))
        if (leftDay != rightDay) return false
        val leftStart = left.optInt("startSection", 1)
        val leftEnd = left.optInt("endSection", leftStart).coerceAtLeast(leftStart)
        val rightStart = right.optInt("startSection", 1)
        val rightEnd = right.optInt("endSection", rightStart).coerceAtLeast(rightStart)
        if (leftStart > rightEnd || rightStart > leftEnd) return false
        val leftMask = ScheduleMath.maskFromWeeks(normalizeWeeks(left.optJSONArray("weeks"), allowEmpty = false))
        val rightMask = ScheduleMath.maskFromWeeks(normalizeWeeks(right.optJSONArray("weeks"), allowEmpty = false))
        return (leftMask and rightMask) != 0L
    }

    private fun courseKey(item: JSONObject): String = courseKey(stringValue(item, "name"), stringValue(item, "teacher"))
    private fun courseKey(name: String, teacher: String): String = "${name.trim().lowercase()}\u001f${teacher.trim().lowercase()}"

    private fun countCourses(scheduleId: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM courses WHERE schedule_id=?", arrayOf(scheduleId)).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private fun countMeetings(scheduleId: String): Int = db.rawQuery(
        "SELECT COUNT(*) FROM course_meetings m JOIN courses c ON c.id=m.course_id WHERE c.schedule_id=?",
        arrayOf(scheduleId)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    // ------------------------------------------------------------------ grades

    fun gradeSnapshot(): JSONObject = synchronized(lock) {
        // Grade records without a credit borrow one by course name: legacy values first, then courses of
        // every schedule (newer schedules override older ones), and the active schedule last so it wins.
        val credits = creditFallbackUnlocked()
        db.rawQuery(
            "SELECT c.name, c.credit FROM courses c JOIN schedules s ON s.id=c.schedule_id WHERE c.credit IS NOT NULL " +
                "ORDER BY (c.schedule_id=?), s.rowid, c.rowid",
            arrayOf(activeScheduleIdUnlocked().orEmpty())
        ).use { cursor ->
            while (cursor.moveToNext()) credits[ScheduleMath.normalizedName(cursor.getString(0))] = cursor.getDouble(1)
        }
        val records = JSONArray()
        val terms = linkedSetOf<String>()
        val institutions = linkedSetOf<String>()
        var updatedAt: String? = null
        db.rawQuery(
            "SELECT id, source, institution, term_label, course_code, course_name, course_type, credit, score_text, numeric_score, grade_point, elective, attempt, imported_at " +
                "FROM grade_records ORDER BY term_label DESC, course_name COLLATE NOCASE, attempt",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val term = cursor.getString(3)
                val institution = cursor.getString(2)
                val courseName = cursor.getString(5)
                terms += term
                if (institution.isNotBlank()) institutions += institution
                val importedAt = cursor.getString(13)
                if (updatedAt == null || importedAt > updatedAt!!) updatedAt = importedAt
                val credit: Any = if (!cursor.isNull(7)) cursor.getDouble(7) else credits[ScheduleMath.normalizedName(courseName)] ?: JSONObject.NULL
                records.put(
                    JSONObject()
                        .put("id", cursor.getString(0))
                        .put("source", cursor.getString(1))
                        .put("institution", institution)
                        .put("term", term)
                        .put("courseCode", cursor.getString(4))
                        .put("courseName", courseName)
                        .put("courseType", cursor.getString(6))
                        .put("credit", credit)
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
            normalized += JSONObject(row.toString()).put("courseName", courseName).put("term", term)
        }
        require(normalized.isNotEmpty()) { "成绩记录缺少课程名称" }

        var replacedCount = 0
        transaction {
            terms.forEach { term ->
                replacedCount += db.rawQuery(
                    "SELECT COUNT(*) FROM grade_records WHERE source=? AND institution=? AND term_label=?",
                    arrayOf(source, institution, term)
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
                db.execSQL("DELETE FROM grade_records WHERE source=? AND institution=? AND term_label=?", arrayOf(source, institution, term))
            }
            normalized.forEach { row ->
                val courseType = stringValue(row, "courseType").ifBlank { stringValue(row, "type") }
                val scoreText = stringValue(row, "scoreText").ifBlank { stringValue(row, "score") }
                val numericScore = nullableDouble(row, "numericScore") ?: scoreText.toDoubleOrNull()
                val elective = if (row.has("elective") && !row.isNull("elective")) {
                    row.optBoolean("elective", false)
                } else {
                    listOf("选修", "任选", "限选", "公选").any { courseType.contains(it) }
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
                        nullableDouble(row, "credit")?.takeIf { it >= 0.0 },
                        scoreText,
                        numericScore,
                        nullableDouble(row, "gradePoint")?.takeIf { it >= 0.0 },
                        if (elective) 1 else 0,
                        row.optInt("attempt", 1).coerceIn(1, 20)
                    )
                )
            }
        }
        JSONObject().put("recordCount", normalized.size).put("termCount", terms.size).put("replacedCount", replacedCount)
    }

    fun deleteGradeRecords(term: String?): Int = synchronized(lock) {
        transaction {
            val count = if (term == null) {
                db.rawQuery("SELECT COUNT(*) FROM grade_records", null).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            } else {
                db.rawQuery("SELECT COUNT(*) FROM grade_records WHERE term_label=?", arrayOf(term)).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            }
            if (term == null) db.execSQL("DELETE FROM grade_records") else db.execSQL("DELETE FROM grade_records WHERE term_label=?", arrayOf(term))
            count
        }
    }

    // ------------------------------------------------------------------ diagnostics

    fun diagnosticSummary(): JSONObject = synchronized(lock) {
        val scheduleId = activeScheduleIdUnlocked()
        JSONObject()
            .put("schemaVersion", db.version)
            .put("scheduleCount", db.rawQuery("SELECT COUNT(*) FROM schedules", null).use { if (it.moveToFirst()) it.getInt(0) else 0 })
            .put("activeMeetingCount", scheduleId?.let(::countMeetings) ?: 0)
            .put("hasTermStart", scheduleId?.let(::scheduleRow)?.termStart?.isNotEmpty() ?: false)
            .put("gradeRecordCount", db.rawQuery("SELECT COUNT(*) FROM grade_records", null).use { if (it.moveToFirst()) it.getInt(0) else 0 })
            .put("reminderEnabled", reminderSettings().optBoolean("enabled", false))
    }

    // ------------------------------------------------------------------ helpers

    private fun normalizeWeeks(array: JSONArray?, allowEmpty: Boolean): List<Int> {
        val weeks = if (array == null) emptyList() else (0 until array.length()).map { array.optInt(it) }.filter { it in 1..ScheduleMath.MAX_WEEKS }.distinct().sorted()
        return if (weeks.isEmpty() && !allowEmpty) (1..20).toList() else weeks
    }

    private fun creditValue(input: JSONObject): Double? = nullableDouble(input, "credit")?.takeIf { it in 0.0..30.0 }

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

    companion object {
        const val DB_NAME = "lumaschedule.sqlite"
        const val SCHEMA_VERSION = 2
        const val DEFAULT_TIMEZONE = "Asia/Shanghai"
        const val APPEARANCE_KEY = "appearance.v2"
        const val REMINDER_KEY = "reminders.course.default"
        private const val ACTIVE_SCHEDULE_KEY = "schedule.active_id"
        private const val DISPLAY_KEY = "schedule.display"
        private const val LEGACY_WEEKEND_KEY = "schedule.weekend_mode"
        private const val LEGACY_CREDIT_INDEX_KEY = "course.credit.index"
        private const val LEGACY_CREDIT_META_PREFIX = "course.credit.meta."
        /** Normalised course name → credit carried over from older builds, used only to fill grade records. */
        private const val CREDIT_FALLBACK_KEY = "course.credit.fallback"
        private val WEEKEND_MODES = setOf("auto", "weekdays", "sat", "sun", "both")
        private val PLACEHOLDER_TERM_NAMES = setOf("导入学期", "手动课表")

        @Volatile private var instance: LumaDatabase? = null

        fun get(context: Context): LumaDatabase = instance ?: synchronized(this) {
            instance ?: LumaDatabase(context.applicationContext).also { instance = it }
        }
    }
}
