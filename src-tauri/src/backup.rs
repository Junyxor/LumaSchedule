use crate::db::AppDb;
use chrono::{Duration, NaiveDate};
use rusqlite::{params_from_iter, types::{Value as SqlValue, ValueRef}};
use schedule_core::{ImportBundle, ImportedCourse, WeekMask};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::{BTreeMap, HashMap};
use std::time::{SystemTime, UNIX_EPOCH};

const BACKUP_FORMAT: &str = "lumaschedule-backup";
const BACKUP_SCHEMA_VERSION: u32 = 1;

const INSERT_ORDER: &[&str] = &[
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
];

const DELETE_ORDER: &[&str] = &[
    "import_audit",
    "settings",
    "sync_profiles",
    "calendar_bindings",
    "course_automations",
    "reminder_rules",
    "adapter_sources",
    "course_exceptions",
    "course_meetings",
    "courses",
    "schedules",
    "time_schemes",
    "terms",
];

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BackupEnvelope {
    pub format: String,
    pub schema_version: u32,
    pub generated_at_unix_ms: u64,
    pub app_version: String,
    pub tables: BTreeMap<String, Vec<BTreeMap<String, Value>>>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BackupSummary {
    pub schema_version: u32,
    pub generated_at_unix_ms: u64,
    pub table_count: usize,
    pub row_count: usize,
    pub schedule_count: usize,
    pub course_count: usize,
    pub meeting_count: usize,
}

impl BackupEnvelope {
    pub fn summary(&self) -> BackupSummary {
        let count = |name: &str| self.tables.get(name).map_or(0, Vec::len);
        BackupSummary {
            schema_version: self.schema_version,
            generated_at_unix_ms: self.generated_at_unix_ms,
            table_count: self.tables.len(),
            row_count: self.tables.values().map(Vec::len).sum(),
            schedule_count: count("schedules"),
            course_count: count("courses"),
            meeting_count: count("course_meetings"),
        }
    }

    pub fn validate(&self) -> Result<(), String> {
        if self.format != BACKUP_FORMAT {
            return Err("不是 LumaSchedule 完整备份文件".into());
        }
        if self.schema_version != BACKUP_SCHEMA_VERSION {
            return Err(format!(
                "不支持的备份版本 {}（当前支持 {}）",
                self.schema_version, BACKUP_SCHEMA_VERSION
            ));
        }
        for table in self.tables.keys() {
            if !INSERT_ORDER.contains(&table.as_str()) {
                return Err(format!("备份包含未知数据表: {table}"));
            }
        }
        Ok(())
    }
}

impl AppDb {
    pub fn export_backup(&self) -> Result<BackupEnvelope, String> {
        let conn = self.0.lock().map_err(|_| "database lock poisoned".to_string())?;
        let mut tables = BTreeMap::new();
        for table in INSERT_ORDER {
            tables.insert((*table).to_string(), dump_table(&conn, table)?);
        }
        let generated_at_unix_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(|e| e.to_string())?
            .as_millis() as u64;
        Ok(BackupEnvelope {
            format: BACKUP_FORMAT.into(),
            schema_version: BACKUP_SCHEMA_VERSION,
            generated_at_unix_ms,
            app_version: env!("CARGO_PKG_VERSION").to_string(),
            tables,
        })
    }

    pub fn export_backup_json(&self) -> Result<String, String> {
        serde_json::to_string_pretty(&self.export_backup()?).map_err(|e| e.to_string())
    }

    pub fn restore_backup(&self, backup: &BackupEnvelope) -> Result<BackupSummary, String> {
        backup.validate()?;
        let mut conn = self.0.lock().map_err(|_| "database lock poisoned".to_string())?;
        let tx = conn.transaction().map_err(|e| e.to_string())?;

        for table in DELETE_ORDER {
            tx.execute(&format!("DELETE FROM {table}"), [])
                .map_err(|e| format!("清理 {table} 失败: {e}"))?;
        }

        for table in INSERT_ORDER {
            let Some(rows) = backup.tables.get(*table) else { continue };
            for row in rows {
                if row.is_empty() {
                    continue;
                }
                let columns = row.keys().cloned().collect::<Vec<_>>();
                let placeholders = (1..=columns.len())
                    .map(|index| format!("?{index}"))
                    .collect::<Vec<_>>()
                    .join(",");
                let sql = format!(
                    "INSERT INTO {table} ({}) VALUES ({placeholders})",
                    columns.join(",")
                );
                let values = columns
                    .iter()
                    .map(|column| json_to_sql(row.get(column).unwrap_or(&Value::Null)))
                    .collect::<Result<Vec<_>, _>>()?;
                tx.execute(&sql, params_from_iter(values))
                    .map_err(|e| format!("恢复 {table} 失败: {e}"))?;
            }
        }

        tx.commit().map_err(|e| e.to_string())?;
        Ok(backup.summary())
    }

    pub fn restore_backup_json(&self, raw: &str) -> Result<BackupSummary, String> {
        let backup: BackupEnvelope = serde_json::from_str(raw)
            .map_err(|e| format!("备份 JSON 解析失败: {e}"))?;
        self.restore_backup(&backup)
    }

    pub fn export_latest_bundle(&self) -> Result<ImportBundle, String> {
        let conn = self.0.lock().map_err(|_| "database lock poisoned".to_string())?;
        let (schedule_id, term_name, term_start, week_count, week_starts_on, time_scheme): (String, String, String, i64, i64, Option<String>) = conn
            .query_row(
                "SELECT s.id, t.name, t.start_date, t.week_count, s.week_starts_on, ts.sections_json \
                 FROM schedules s JOIN terms t ON t.id=s.term_id \
                 LEFT JOIN time_schemes ts ON ts.id=s.time_scheme_id \
                 ORDER BY s.rowid DESC LIMIT 1",
                [],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?, row.get(4)?, row.get(5)?)),
            )
            .map_err(|e| format!("没有可导出的课表: {e}"))?;

        let mut stmt = conn.prepare(
            "SELECT c.name, c.teacher, m.location, m.weekday, m.start_section, m.end_section, \
                    m.weeks_mask, m.start_time, m.end_time \
             FROM courses c JOIN course_meetings m ON m.course_id=c.id \
             WHERE c.schedule_id=?1 ORDER BY m.weekday, m.start_section, c.name",
        ).map_err(|e| e.to_string())?;
        let courses = stmt.query_map([&schedule_id], |row| {
            let mask: i64 = row.get(6)?;
            Ok(ImportedCourse {
                name: row.get(0)?,
                teacher: row.get(1)?,
                location: row.get(2)?,
                weekday: row.get::<_, i64>(3)? as u8,
                start_section: row.get::<_, i64>(4)? as u8,
                end_section: row.get::<_, i64>(5)? as u8,
                weeks: WeekMask(mask as u64).weeks(),
                start_time: row.get(7)?,
                end_time: row.get(8)?,
            })
        }).map_err(|e| e.to_string())?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string())?;

        let mut metadata = HashMap::new();
        metadata.insert(
            "courseConfig".into(),
            json!({
                "semesterStartDate": term_start.clone(),
                "semesterTotalWeeks": week_count.clamp(1, 64),
                "firstDayOfWeek": week_starts_on.clamp(1, 7)
            }).to_string(),
        );
        if let Some(time_scheme) = time_scheme {
            metadata.insert("timeScheme".into(), time_scheme);
        }
        Ok(ImportBundle {
            source: "lumaschedule".into(),
            term_name: Some(term_name),
            term_start: (!term_start.is_empty()).then_some(term_start),
            courses,
            metadata,
        })
    }

    pub fn export_latest_json(&self) -> Result<String, String> {
        serde_json::to_string_pretty(&self.export_latest_bundle()?).map_err(|e| e.to_string())
    }

    pub fn export_latest_ics(&self) -> Result<String, String> {
        let bundle = self.export_latest_bundle()?;
        let term_start = bundle.term_start.as_deref().ok_or_else(|| "课表没有学期开始日期，无法生成 ICS 日期".to_string())?;
        let base = NaiveDate::parse_from_str(term_start, "%Y-%m-%d")
            .map_err(|_| format!("学期开始日期不是 YYYY-MM-DD: {term_start}"))?;
        let section_times = parse_section_times(bundle.metadata.get("timeScheme"));

        let mut out = String::from("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//LumaSchedule//Course Schedule//CN\r\nCALSCALE:GREGORIAN\r\nMETHOD:PUBLISH\r\n");
        let now_stamp = chrono::Utc::now().format("%Y%m%dT%H%M%SZ").to_string();

        for (course_index, course) in bundle.courses.iter().enumerate() {
            let start_time = course.start_time.clone().or_else(|| section_times.get(&course.start_section).map(|x| x.0.clone()))
                .ok_or_else(|| format!("课程「{}」缺少第 {} 节开始时间", course.name, course.start_section))?;
            let end_time = course.end_time.clone().or_else(|| section_times.get(&course.end_section).map(|x| x.1.clone()))
                .ok_or_else(|| format!("课程「{}」缺少第 {} 节结束时间", course.name, course.end_section))?;
            let start_hm = compact_time(&start_time)?;
            let end_hm = compact_time(&end_time)?;

            for week in &course.weeks {
                if !(1..=64).contains(week) || !(1..=7).contains(&course.weekday) {
                    continue;
                }
                let date = base + Duration::days(((*week as i64 - 1) * 7) + (course.weekday as i64 - 1));
                let date_text = date.format("%Y%m%d").to_string();
                let uid = format!("{}-{}-{}-{}@lumaschedule", course_index, week, course.weekday, course.start_section);
                out.push_str("BEGIN:VEVENT\r\n");
                out.push_str(&format!("UID:{}\r\n", uid));
                out.push_str(&format!("DTSTAMP:{}\r\n", now_stamp));
                out.push_str(&format!("DTSTART:{}T{}00\r\n", date_text, start_hm));
                out.push_str(&format!("DTEND:{}T{}00\r\n", date_text, end_hm));
                out.push_str(&format!("SUMMARY:{}\r\n", ics_escape(&course.name)));
                if let Some(location) = course.location.as_deref().filter(|x| !x.is_empty()) {
                    out.push_str(&format!("LOCATION:{}\r\n", ics_escape(location)));
                }
                let description = match course.teacher.as_deref().filter(|x| !x.is_empty()) {
                    Some(teacher) => format!("教师：{}\\n第{}-{}节 · 第{}周", teacher, course.start_section, course.end_section, week),
                    None => format!("第{}-{}节 · 第{}周", course.start_section, course.end_section, week),
                };
                out.push_str(&format!("DESCRIPTION:{}\r\n", ics_escape(&description)));
                out.push_str("END:VEVENT\r\n");
            }
        }
        out.push_str("END:VCALENDAR\r\n");
        Ok(out)
    }
}

fn dump_table(conn: &rusqlite::Connection, table: &str) -> Result<Vec<BTreeMap<String, Value>>, String> {
    let mut stmt = conn.prepare(&format!("SELECT * FROM {table}"))
        .map_err(|e| format!("读取 {table} 失败: {e}"))?;
    let columns = stmt.column_names().iter().map(|x| (*x).to_string()).collect::<Vec<_>>();
    let mut rows = stmt.query([]).map_err(|e| e.to_string())?;
    let mut output = Vec::new();
    while let Some(row) = rows.next().map_err(|e| e.to_string())? {
        let mut object = BTreeMap::new();
        for (index, column) in columns.iter().enumerate() {
            let value = match row.get_ref(index).map_err(|e| e.to_string())? {
                ValueRef::Null => Value::Null,
                ValueRef::Integer(value) => Value::from(value),
                ValueRef::Real(value) => Value::from(value),
                ValueRef::Text(value) => Value::String(String::from_utf8_lossy(value).into_owned()),
                ValueRef::Blob(value) => Value::String(format!("hex:{}", hex::encode(value))),
            };
            object.insert(column.clone(), value);
        }
        output.push(object);
    }
    Ok(output)
}

fn json_to_sql(value: &Value) -> Result<SqlValue, String> {
    match value {
        Value::Null => Ok(SqlValue::Null),
        Value::Bool(value) => Ok(SqlValue::Integer(if *value { 1 } else { 0 })),
        Value::Number(value) if value.is_i64() => Ok(SqlValue::Integer(value.as_i64().unwrap())),
        Value::Number(value) if value.is_u64() => {
            let value = value.as_u64().unwrap();
            if value > i64::MAX as u64 {
                return Err("备份中的整数超出 SQLite 范围".into());
            }
            Ok(SqlValue::Integer(value as i64))
        }
        Value::Number(value) => Ok(SqlValue::Real(value.as_f64().ok_or_else(|| "无效浮点数".to_string())?)),
        Value::String(value) if value.starts_with("hex:") => {
            let bytes = hex::decode(&value[4..]).map_err(|e| format!("备份 BLOB 解码失败: {e}"))?;
            Ok(SqlValue::Blob(bytes))
        }
        Value::String(value) => Ok(SqlValue::Text(value.clone())),
        Value::Array(_) | Value::Object(_) => Err("备份表字段不能直接包含数组或对象".into()),
    }
}

fn parse_section_times(raw: Option<&String>) -> HashMap<u8, (String, String)> {
    let mut result = HashMap::new();
    let Some(raw) = raw else { return result };
    let Ok(value) = serde_json::from_str::<Value>(raw) else { return result };
    let Some(items) = value.as_array() else { return result };
    for item in items {
        let number = item.get("number").and_then(Value::as_u64).map(|x| x as u8);
        let start = item.get("startTime").or_else(|| item.get("start_time")).and_then(Value::as_str);
        let end = item.get("endTime").or_else(|| item.get("end_time")).and_then(Value::as_str);
        if let (Some(number), Some(start), Some(end)) = (number, start, end) {
            result.insert(number, (start.to_string(), end.to_string()));
        }
    }
    result
}

fn compact_time(value: &str) -> Result<String, String> {
    let mut parts = value.trim().split(':');
    let hour = parts.next().and_then(|x| x.parse::<u8>().ok()).filter(|x| *x <= 23)
        .ok_or_else(|| format!("无效时间: {value}"))?;
    let minute = parts.next().and_then(|x| x.parse::<u8>().ok()).filter(|x| *x <= 59)
        .ok_or_else(|| format!("无效时间: {value}"))?;
    Ok(format!("{hour:02}{minute:02}"))
}

fn ics_escape(value: &str) -> String {
    value
        .replace('\\', "\\\\")
        .replace(';', "\\;")
        .replace(',', "\\,")
        .replace("\r\n", "\\n")
        .replace('\n', "\\n")
        .replace('\r', "\\n")
}
