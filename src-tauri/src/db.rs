use rusqlite::{params, Connection, OptionalExtension};
use schedule_core::{ImportBundle, WeekMask};
use serde::Serialize;
use std::hash::{Hash, Hasher};
use std::{collections::HashMap, path::Path, sync::Mutex};
use uuid::Uuid;

pub struct AppDb(pub Mutex<Connection>);

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportCommitResult {
    pub term_id: String,
    pub schedule_id: String,
    pub course_count: usize,
    pub meeting_count: usize,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CourseView {
    pub id: String,
    pub name: String,
    pub teacher: String,
    pub room: String,
    pub start: String,
    pub end: String,
    pub day: u8,
    pub color: String,
    pub start_section: u8,
    pub end_section: u8,
    pub weeks: Vec<u8>,
}

impl AppDb {
    pub fn open(path: &Path) -> Result<Self, rusqlite::Error> {
        let conn = Connection::open(path)?;
        conn.execute_batch(
            r#"
            PRAGMA journal_mode=WAL;
            PRAGMA foreign_keys=ON;

            CREATE TABLE IF NOT EXISTS terms (
              id TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              start_date TEXT NOT NULL,
              week_count INTEGER NOT NULL,
              timezone TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS time_schemes (
              id TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              sections_json TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS schedules (
              id TEXT PRIMARY KEY,
              term_id TEXT NOT NULL REFERENCES terms(id) ON DELETE CASCADE,
              name TEXT NOT NULL,
              week_starts_on INTEGER NOT NULL DEFAULT 1,
              time_scheme_id TEXT REFERENCES time_schemes(id)
            );

            CREATE TABLE IF NOT EXISTS courses (
              id TEXT PRIMARY KEY,
              schedule_id TEXT NOT NULL REFERENCES schedules(id) ON DELETE CASCADE,
              name TEXT NOT NULL,
              code TEXT,
              teacher TEXT,
              color_token TEXT NOT NULL,
              note TEXT
            );

            CREATE TABLE IF NOT EXISTS course_meetings (
              id TEXT PRIMARY KEY,
              course_id TEXT NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
              weekday INTEGER NOT NULL,
              start_section INTEGER NOT NULL,
              end_section INTEGER NOT NULL,
              start_time TEXT,
              end_time TEXT,
              weeks_mask INTEGER NOT NULL,
              location TEXT
            );

            CREATE TABLE IF NOT EXISTS course_exceptions (
              id TEXT PRIMARY KEY,
              meeting_id TEXT NOT NULL REFERENCES course_meetings(id) ON DELETE CASCADE,
              week INTEGER NOT NULL,
              kind TEXT NOT NULL,
              payload_json TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS adapter_sources (
              id TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              version TEXT NOT NULL,
              manifest_json TEXT NOT NULL,
              enabled INTEGER NOT NULL DEFAULT 1,
              installed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE IF NOT EXISTS reminder_rules (
              id TEXT PRIMARY KEY,
              schedule_id TEXT NOT NULL REFERENCES schedules(id) ON DELETE CASCADE,
              course_id TEXT REFERENCES courses(id) ON DELETE CASCADE,
              moment TEXT NOT NULL,
              offset_minutes INTEGER NOT NULL DEFAULT 0,
              enabled INTEGER NOT NULL DEFAULT 1,
              sound INTEGER NOT NULL DEFAULT 1,
              vibration INTEGER NOT NULL DEFAULT 1,
              respect_holidays INTEGER NOT NULL DEFAULT 1
            );

            CREATE TABLE IF NOT EXISTS course_automations (
              id TEXT PRIMARY KEY,
              schedule_id TEXT NOT NULL REFERENCES schedules(id) ON DELETE CASCADE,
              action TEXT NOT NULL,
              enabled INTEGER NOT NULL DEFAULT 1
            );

            CREATE TABLE IF NOT EXISTS calendar_bindings (
              id TEXT PRIMARY KEY,
              schedule_id TEXT NOT NULL REFERENCES schedules(id) ON DELETE CASCADE,
              provider TEXT NOT NULL,
              external_calendar_id TEXT,
              direction TEXT NOT NULL DEFAULT 'export',
              enabled INTEGER NOT NULL DEFAULT 1
            );

            CREATE TABLE IF NOT EXISTS sync_profiles (
              id TEXT PRIMARY KEY,
              kind TEXT NOT NULL,
              display_name TEXT NOT NULL,
              config_json TEXT NOT NULL,
              enabled INTEGER NOT NULL DEFAULT 1,
              last_sync_at TEXT
            );

            CREATE TABLE IF NOT EXISTS settings (
              key TEXT PRIMARY KEY,
              value_json TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS import_audit (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              source TEXT NOT NULL,
              summary_json TEXT NOT NULL,
              created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
            "#,
        )?;
        Ok(Self(Mutex::new(conn)))
    }

    pub fn commit_import(&self, bundle: &ImportBundle) -> Result<ImportCommitResult, String> {
        let mut conn = self.0.lock().map_err(|_| "database lock poisoned".to_string())?;
        let tx = conn.transaction().map_err(|e| e.to_string())?;

        let term_id = Uuid::new_v4().to_string();
        let schedule_id = Uuid::new_v4().to_string();
        let term_name = bundle.term_name.clone().unwrap_or_else(|| "导入学期".to_string());
        let term_start = bundle.term_start.clone().unwrap_or_default();
        let config = bundle
            .metadata
            .get("courseConfig")
            .and_then(|raw| serde_json::from_str::<serde_json::Value>(raw).ok());
        let week_count = config
            .as_ref()
            .and_then(|value| value.get("semesterTotalWeeks"))
            .and_then(|value| value.as_u64())
            .map(|value| value.clamp(1, 64) as u8)
            .unwrap_or_else(|| {
                bundle
                    .courses
                    .iter()
                    .flat_map(|course| course.weeks.iter().copied())
                    .max()
                    .unwrap_or(20)
            });
        let week_starts_on = config
            .as_ref()
            .and_then(|value| value.get("firstDayOfWeek"))
            .and_then(|value| value.as_u64())
            .map(|value| value.clamp(1, 7) as i64)
            .unwrap_or(1);

        tx.execute(
            "INSERT INTO terms(id, name, start_date, week_count, timezone) VALUES(?1, ?2, ?3, ?4, ?5)",
            params![&term_id, &term_name, &term_start, week_count as i64, "Asia/Shanghai"],
        )
        .map_err(|e| e.to_string())?;

        let time_scheme_id = if let Some(raw) = bundle.metadata.get("timeScheme") {
            let value: serde_json::Value = serde_json::from_str(raw).map_err(|e| e.to_string())?;
            if value.as_array().is_some_and(|sections| !sections.is_empty()) {
                let id = Uuid::new_v4().to_string();
                tx.execute(
                    "INSERT INTO time_schemes(id, name, sections_json) VALUES(?1, ?2, ?3)",
                    params![&id, format!("{} · 导入作息", bundle.source), value.to_string()],
                )
                .map_err(|e| e.to_string())?;
                Some(id)
            } else {
                None
            }
        } else {
            None
        };

        tx.execute(
            "INSERT INTO schedules(id, term_id, name, week_starts_on, time_scheme_id) VALUES(?1, ?2, ?3, ?4, ?5)",
            params![
                &schedule_id,
                &term_id,
                format!("{} · {}", bundle.source, "导入课表"),
                week_starts_on,
                time_scheme_id.as_deref()
            ],
        )
        .map_err(|e| e.to_string())?;

        let mut course_ids: HashMap<String, String> = HashMap::new();
        for imported in &bundle.courses {
            let key = format!("{}\u{1f}{}", imported.name, imported.teacher.as_deref().unwrap_or(""));
            let course_id = if let Some(existing) = course_ids.get(&key) {
                existing.clone()
            } else {
                let id = Uuid::new_v4().to_string();
                tx.execute(
                    "INSERT INTO courses(id, schedule_id, name, teacher, color_token) VALUES(?1, ?2, ?3, ?4, ?5)",
                    params![&id, &schedule_id, &imported.name, imported.teacher.as_deref(), "auto"],
                )
                .map_err(|e| e.to_string())?;
                course_ids.insert(key, id.clone());
                id
            };

            let meeting_id = Uuid::new_v4().to_string();
            let weeks_mask = WeekMask::from_weeks(imported.weeks.iter().copied()).0 as i64;
            tx.execute(
                "INSERT INTO course_meetings(id, course_id, weekday, start_section, end_section, start_time, end_time, weeks_mask, location) VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
                params![
                    &meeting_id,
                    &course_id,
                    imported.weekday as i64,
                    imported.start_section as i64,
                    imported.end_section as i64,
                    imported.start_time.as_deref(),
                    imported.end_time.as_deref(),
                    weeks_mask,
                    imported.location.as_deref()
                ],
            )
            .map_err(|e| e.to_string())?;
        }

        let audit = serde_json::json!({
            "termId": term_id.clone(),
            "scheduleId": schedule_id.clone(),
            "courseCount": course_ids.len(),
            "meetingCount": bundle.courses.len()
        });
        tx.execute(
            "INSERT INTO import_audit(source, summary_json) VALUES(?1, ?2)",
            params![&bundle.source, audit.to_string()],
        )
        .map_err(|e| e.to_string())?;
        tx.commit().map_err(|e| e.to_string())?;

        Ok(ImportCommitResult {
            term_id,
            schedule_id,
            course_count: course_ids.len(),
            meeting_count: bundle.courses.len(),
        })
    }

    pub fn list_latest_schedule_courses(&self) -> Result<Vec<CourseView>, String> {
        let conn = self.0.lock().map_err(|_| "database lock poisoned".to_string())?;
        let schedule_id: Option<String> = conn
            .query_row(
                "SELECT id FROM schedules ORDER BY rowid DESC LIMIT 1",
                [],
                |row| row.get(0),
            )
            .optional()
            .map_err(|e| e.to_string())?;
        let Some(schedule_id) = schedule_id else { return Ok(Vec::new()); };

        let mut stmt = conn.prepare(
            "SELECT c.id, c.name, COALESCE(c.teacher, ''), COALESCE(m.location, ''), COALESCE(m.start_time, ''), COALESCE(m.end_time, ''), m.weekday, c.color_token, m.start_section, m.end_section, m.weeks_mask \
             FROM courses c JOIN course_meetings m ON m.course_id=c.id WHERE c.schedule_id=?1 ORDER BY m.weekday, m.start_section, c.name"
        ).map_err(|e| e.to_string())?;
        let rows = stmt.query_map(params![&schedule_id], |row| {
            let course_id: String = row.get(0)?;
            let name: String = row.get(1)?;
            let color_token: String = row.get(7)?;
            let weekday: i64 = row.get(6)?;
            let start_section: i64 = row.get(8)?;
            let end_section: i64 = row.get(9)?;
            let mask: i64 = row.get(10)?;
            Ok(CourseView {
                id: format!("{}:{}:{}", course_id, weekday, start_section),
                name: name.clone(),
                teacher: row.get(2)?,
                room: row.get(3)?,
                start: row.get(4)?,
                end: row.get(5)?,
                day: weekday as u8,
                color: if color_token == "auto" { color_for(&name) } else { color_token },
                start_section: start_section as u8,
                end_section: end_section as u8,
                weeks: WeekMask(mask as u64).weeks(),
            })
        }).map_err(|e| e.to_string())?;
        rows.collect::<Result<Vec<_>, _>>().map_err(|e| e.to_string())
    }

    pub fn set_json(&self, key: &str, value: &serde_json::Value) -> Result<(), String> {
        let conn = self.0.lock().map_err(|_| "database lock poisoned".to_string())?;
        conn.execute(
            "INSERT INTO settings(key, value_json) VALUES(?1, ?2) ON CONFLICT(key) DO UPDATE SET value_json=excluded.value_json",
            params![key, value.to_string()],
        )
        .map_err(|e| e.to_string())?;
        Ok(())
    }

    pub fn get_json(&self, key: &str) -> Result<Option<serde_json::Value>, String> {
        let conn = self.0.lock().map_err(|_| "database lock poisoned".to_string())?;
        let mut stmt = conn.prepare("SELECT value_json FROM settings WHERE key=?1").map_err(|e| e.to_string())?;
        let mut rows = stmt.query(params![key]).map_err(|e| e.to_string())?;
        if let Some(row) = rows.next().map_err(|e| e.to_string())? {
            let raw: String = row.get(0).map_err(|e| e.to_string())?;
            serde_json::from_str(&raw).map(Some).map_err(|e| e.to_string())
        } else {
            Ok(None)
        }
    }
}

fn color_for(name: &str) -> String {
    const COLORS: [&str; 7] = ["violet", "cyan", "amber", "blue", "green", "pink", "orange"];
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    name.hash(&mut hasher);
    COLORS[(hasher.finish() as usize) % COLORS.len()].to_string()
}
