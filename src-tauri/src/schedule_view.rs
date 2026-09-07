use crate::db::{AppDb, CourseView};
use chrono::{FixedOffset, NaiveDate, Utc};
use rusqlite::OptionalExtension;
use serde::Serialize;
use serde_json::Value;
use tauri::State;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ScheduleSnapshot {
    pub courses: Vec<CourseView>,
    pub term_name: Option<String>,
    pub term_start: Option<String>,
    pub week_count: Option<u8>,
    pub current_week: Option<u8>,
}

#[tauri::command]
pub fn get_schedule_snapshot(db: State<'_, AppDb>) -> Result<ScheduleSnapshot, String> {
    let mut courses = db.list_latest_schedule_courses()?;

    let info = {
        let conn = db.0.lock().map_err(|_| "database lock poisoned".to_string())?;
        conn.query_row(
            "SELECT t.name, t.start_date, t.week_count, COALESCE(ts.sections_json, '') \
             FROM schedules s \
             JOIN terms t ON t.id=s.term_id \
             LEFT JOIN time_schemes ts ON ts.id=s.time_scheme_id \
             ORDER BY s.rowid DESC LIMIT 1",
            [],
            |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, i64>(2)?,
                    row.get::<_, String>(3)?,
                ))
            },
        )
        .optional()
        .map_err(|e| e.to_string())?
    };

    let Some((term_name, term_start, week_count_raw, sections_json)) = info else {
        return Ok(ScheduleSnapshot {
            courses,
            term_name: None,
            term_start: None,
            week_count: None,
            current_week: None,
        });
    };

    let week_count = week_count_raw.clamp(1, 64) as u8;
    let current_week = academic_week(&term_start, week_count);

    if let Some(week) = current_week {
        courses.retain(|course| course.weeks.is_empty() || course.weeks.contains(&week));
    }

    if let Ok(value) = serde_json::from_str::<Value>(&sections_json) {
        for course in &mut courses {
            if course.start.trim().is_empty() {
                if let Some(start) = slot_time(&value, course.start_section, true) {
                    course.start = start;
                }
            }
            if course.end.trim().is_empty() {
                if let Some(end) = slot_time(&value, course.end_section, false) {
                    course.end = end;
                }
            }
        }
    }

    Ok(ScheduleSnapshot {
        courses,
        term_name: Some(term_name),
        term_start: (!term_start.trim().is_empty()).then_some(term_start),
        week_count: Some(week_count),
        current_week,
    })
}

fn academic_week(term_start: &str, week_count: u8) -> Option<u8> {
    let start = parse_date(term_start)?;
    let china = FixedOffset::east_opt(8 * 60 * 60)?;
    let today = Utc::now().with_timezone(&china).date_naive();
    let days = today.signed_duration_since(start).num_days();
    if days < 0 {
        return None;
    }
    let week = (days / 7) + 1;
    (week >= 1 && week <= i64::from(week_count) && week <= 64).then_some(week as u8)
}

fn parse_date(raw: &str) -> Option<NaiveDate> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    for format in ["%Y-%m-%d", "%Y/%m/%d", "%Y.%m.%d"] {
        if let Ok(date) = NaiveDate::parse_from_str(trimmed, format) {
            return Some(date);
        }
    }
    trimmed.get(..10).and_then(|prefix| NaiveDate::parse_from_str(prefix, "%Y-%m-%d").ok())
}

fn slot_time(value: &Value, section: u8, start: bool) -> Option<String> {
    let slots = value
        .as_array()
        .or_else(|| value.get("timeSlots").and_then(Value::as_array))
        .or_else(|| value.get("slots").and_then(Value::as_array))
        .or_else(|| value.get("sections").and_then(Value::as_array))?;

    let item = slots.iter().find(|slot| {
        slot_number(slot).is_some_and(|number| number == section)
    })?;

    let keys: &[&str] = if start {
        &["startTime", "start_time", "start"]
    } else {
        &["endTime", "end_time", "end"]
    };

    keys.iter()
        .find_map(|key| item.get(*key).and_then(Value::as_str))
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_string)
}

fn slot_number(value: &Value) -> Option<u8> {
    for key in ["number", "section", "index"] {
        let Some(raw) = value.get(key) else { continue };
        if let Some(number) = raw.as_u64().and_then(|number| u8::try_from(number).ok()) {
            return Some(number);
        }
        if let Some(number) = raw.as_str().and_then(|text| text.parse::<u8>().ok()) {
            return Some(number);
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resolves_shiguang_time_slots() {
        let slots = serde_json::json!([
            {"number": 1, "startTime": "08:30", "endTime": "09:15"},
            {"number": 2, "startTime": "09:20", "endTime": "10:05"}
        ]);
        assert_eq!(slot_time(&slots, 1, true).as_deref(), Some("08:30"));
        assert_eq!(slot_time(&slots, 2, false).as_deref(), Some("10:05"));
    }

    #[test]
    fn accepts_common_date_formats() {
        assert_eq!(parse_date("2026-09-07"), NaiveDate::from_ymd_opt(2026, 9, 7));
        assert_eq!(parse_date("2026/09/07"), NaiveDate::from_ymd_opt(2026, 9, 7));
    }
}
