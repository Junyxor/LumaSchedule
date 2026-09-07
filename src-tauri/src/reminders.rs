use crate::{db::AppDb, widget_plugin};
use chrono::{Duration, FixedOffset, NaiveDate, NaiveTime, TimeZone, Utc};
use rusqlite::{params, OptionalExtension};
use schedule_core::WeekMask;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use tauri::{AppHandle, Runtime, State};

const SETTINGS_KEY: &str = "reminders.course.default";
const SCHEDULED_IDS_KEY: &str = "reminders.course.native_ids";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CourseReminderSettings {
    pub enabled: bool,
    pub offset_minutes: u16,
}

impl Default for CourseReminderSettings {
    fn default() -> Self {
        Self { enabled: false, offset_minutes: 15 }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReminderSyncReport {
    pub enabled: bool,
    pub future_count: usize,
    pub scheduled_count: usize,
    pub cancelled_count: usize,
    pub skipped_count: usize,
}

#[derive(Debug)]
struct MeetingReminder {
    meeting_id: String,
    course_name: String,
    teacher: String,
    room: String,
    weekday: u8,
    start_section: u8,
    start_time: String,
    weeks_mask: u64,
}

#[tauri::command]
pub fn get_course_reminder_settings(db: State<'_, AppDb>) -> Result<CourseReminderSettings, String> {
    read_settings(db.inner())
}

#[tauri::command]
pub fn save_course_reminder_settings(
    db: State<'_, AppDb>,
    mut settings: CourseReminderSettings,
) -> Result<CourseReminderSettings, String> {
    settings.offset_minutes = settings.offset_minutes.min(180);
    db.set_json(SETTINGS_KEY, &serde_json::to_value(&settings).map_err(|e| e.to_string())?)?;
    Ok(settings)
}

#[tauri::command]
pub fn sync_course_reminders<R: Runtime>(
    app: AppHandle<R>,
    db: State<'_, AppDb>,
) -> Result<ReminderSyncReport, String> {
    let settings = read_settings(db.inner())?;
    let previous_ids = read_scheduled_ids(db.inner())?;

    if !settings.enabled {
        let cancelled = cancel_ids(&app, &previous_ids);
        save_scheduled_ids(db.inner(), &[])?;
        return Ok(ReminderSyncReport {
            enabled: false,
            future_count: 0,
            scheduled_count: 0,
            cancelled_count: cancelled,
            skipped_count: 0,
        });
    }

    let (schedule_id, term_start, week_count, sections_json) = {
        let conn = db.0.lock().map_err(|_| "database lock poisoned".to_string())?;
        conn.query_row(
            "SELECT s.id, t.start_date, t.week_count, COALESCE(ts.sections_json, '') \
             FROM schedules s \
             JOIN terms t ON t.id=s.term_id \
             LEFT JOIN time_schemes ts ON ts.id=s.time_scheme_id \
             ORDER BY s.rowid DESC LIMIT 1",
            [],
            |row| Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?, row.get::<_, i64>(2)?, row.get::<_, String>(3)?)),
        )
        .optional()
        .map_err(|e| e.to_string())?
        .unwrap_or_default()
    };

    if schedule_id.is_empty() {
        let cancelled = cancel_ids(&app, &previous_ids);
        save_scheduled_ids(db.inner(), &[])?;
        return Ok(ReminderSyncReport {
            enabled: true,
            future_count: 0,
            scheduled_count: 0,
            cancelled_count: cancelled,
            skipped_count: 0,
        });
    }

    let start_date = parse_date(&term_start).ok_or_else(|| "课表缺少有效的学期开始日期，暂时无法自动安排课程提醒。".to_string())?;
    let week_count = week_count.clamp(1, 64) as u8;
    let sections = serde_json::from_str::<Value>(&sections_json).ok();
    let meetings = load_meetings(db.inner(), &schedule_id)?;
    let now_ms = Utc::now().timestamp_millis();
    let china = FixedOffset::east_opt(8 * 60 * 60).ok_or_else(|| "无法初始化 Asia/Shanghai 时区".to_string())?;

    let mut desired: HashMap<i32, widget_plugin::NativeReminder> = HashMap::new();
    let mut skipped = 0usize;

    for meeting in meetings {
        let start_time = if meeting.start_time.trim().is_empty() {
            sections.as_ref().and_then(|value| slot_time(value, meeting.start_section))
        } else {
            Some(meeting.start_time.clone())
        };
        let Some(start_time) = start_time else {
            skipped += 1;
            continue;
        };
        let Some(time) = parse_time(&start_time) else {
            skipped += 1;
            continue;
        };

        let weeks = if meeting.weeks_mask == 0 {
            (1..=week_count).collect::<Vec<_>>()
        } else {
            WeekMask(meeting.weeks_mask).weeks().into_iter().filter(|week| *week <= week_count).collect()
        };

        for week in weeks {
            if !(1..=7).contains(&meeting.weekday) {
                skipped += 1;
                continue;
            }
            let days = i64::from(week.saturating_sub(1)) * 7 + i64::from(meeting.weekday.saturating_sub(1));
            let class_date = start_date + Duration::days(days);
            let naive = class_date.and_time(time);
            let Some(class_start) = china.from_local_datetime(&naive).single() else {
                skipped += 1;
                continue;
            };
            let trigger_ms = (class_start - Duration::minutes(i64::from(settings.offset_minutes))).timestamp_millis();
            if trigger_ms <= now_ms {
                skipped += 1;
                continue;
            }

            let id = reminder_id(&format!(
                "{}|{}|{}|{}|{}|{}|{}|{}",
                meeting.meeting_id,
                week,
                start_time,
                settings.offset_minutes,
                meeting.course_name,
                meeting.room,
                meeting.teacher,
                meeting.weekday
            ));
            let mut meta = format!("{} · 第 {} 周", start_time, week);
            if !meeting.room.trim().is_empty() {
                meta.push_str(&format!(" · {}", meeting.room.trim()));
            }
            if !meeting.teacher.trim().is_empty() {
                meta.push_str(&format!(" · {}", meeting.teacher.trim()));
            }
            desired.insert(
                id,
                widget_plugin::NativeReminder {
                    id,
                    trigger_at_epoch_ms: trigger_ms,
                    title: format!("上课提醒 · {}", meeting.course_name),
                    body: meta,
                },
            );
        }
    }

    let desired_ids: HashSet<i32> = desired.keys().copied().collect();
    let previous_set: HashSet<i32> = previous_ids.iter().copied().collect();
    let stale: Vec<i32> = previous_set.difference(&desired_ids).copied().collect();
    let cancelled_count = cancel_ids(&app, &stale);

    let mut scheduled_count = 0usize;
    for (id, reminder) in &desired {
        if previous_set.contains(id) {
            continue;
        }
        if widget_plugin::schedule_reminder(&app, reminder.clone()).unwrap_or(false) {
            scheduled_count += 1;
        }
    }

    let mut ids = desired_ids.into_iter().collect::<Vec<_>>();
    ids.sort_unstable();
    save_scheduled_ids(db.inner(), &ids)?;

    Ok(ReminderSyncReport {
        enabled: true,
        future_count: ids.len(),
        scheduled_count,
        cancelled_count,
        skipped_count: skipped,
    })
}

fn read_settings(db: &AppDb) -> Result<CourseReminderSettings, String> {
    let Some(value) = db.get_json(SETTINGS_KEY)? else { return Ok(CourseReminderSettings::default()); };
    serde_json::from_value(value).map_err(|e| format!("课程提醒设置损坏: {e}"))
}

fn read_scheduled_ids(db: &AppDb) -> Result<Vec<i32>, String> {
    let Some(value) = db.get_json(SCHEDULED_IDS_KEY)? else { return Ok(Vec::new()); };
    serde_json::from_value(value).map_err(|e| format!("课程提醒索引损坏: {e}"))
}

fn save_scheduled_ids(db: &AppDb, ids: &[i32]) -> Result<(), String> {
    db.set_json(SCHEDULED_IDS_KEY, &serde_json::to_value(ids).map_err(|e| e.to_string())?)
}

fn cancel_ids<R: Runtime>(app: &AppHandle<R>, ids: &[i32]) -> usize {
    ids.iter()
        .filter(|id| widget_plugin::cancel_reminder(app, widget_plugin::CancelReminder { id: **id }).unwrap_or(false))
        .count()
}

fn load_meetings(db: &AppDb, schedule_id: &str) -> Result<Vec<MeetingReminder>, String> {
    let conn = db.0.lock().map_err(|_| "database lock poisoned".to_string())?;
    let mut stmt = conn.prepare(
        "SELECT m.id, c.name, COALESCE(c.teacher, ''), COALESCE(m.location, ''), m.weekday, m.start_section, COALESCE(m.start_time, ''), m.weeks_mask \
         FROM courses c JOIN course_meetings m ON m.course_id=c.id \
         WHERE c.schedule_id=?1 ORDER BY m.weekday, m.start_section, c.name"
    ).map_err(|e| e.to_string())?;
    let rows = stmt.query_map(params![schedule_id], |row| {
        Ok(MeetingReminder {
            meeting_id: row.get(0)?,
            course_name: row.get(1)?,
            teacher: row.get(2)?,
            room: row.get(3)?,
            weekday: row.get::<_, i64>(4)? as u8,
            start_section: row.get::<_, i64>(5)? as u8,
            start_time: row.get(6)?,
            weeks_mask: row.get::<_, i64>(7)? as u64,
        })
    }).map_err(|e| e.to_string())?;
    rows.collect::<Result<Vec<_>, _>>().map_err(|e| e.to_string())
}

fn parse_date(raw: &str) -> Option<NaiveDate> {
    let trimmed = raw.trim();
    for format in ["%Y-%m-%d", "%Y/%m/%d", "%Y.%m.%d"] {
        if let Ok(date) = NaiveDate::parse_from_str(trimmed, format) {
            return Some(date);
        }
    }
    trimmed.get(..10).and_then(|prefix| NaiveDate::parse_from_str(prefix, "%Y-%m-%d").ok())
}

fn parse_time(raw: &str) -> Option<NaiveTime> {
    let trimmed = raw.trim();
    for format in ["%H:%M", "%H:%M:%S"] {
        if let Ok(time) = NaiveTime::parse_from_str(trimmed, format) {
            return Some(time);
        }
    }
    None
}

fn slot_time(value: &Value, section: u8) -> Option<String> {
    let slots = value
        .as_array()
        .or_else(|| value.get("timeSlots").and_then(Value::as_array))
        .or_else(|| value.get("slots").and_then(Value::as_array))
        .or_else(|| value.get("sections").and_then(Value::as_array))?;
    let item = slots.iter().find(|slot| {
        ["number", "section", "index"].iter().any(|key| {
            slot.get(*key).and_then(|raw| raw.as_u64().map(|n| n as u8).or_else(|| raw.as_str().and_then(|s| s.parse::<u8>().ok()))).is_some_and(|n| n == section)
        })
    })?;
    ["startTime", "start_time", "start"].iter()
        .find_map(|key| item.get(*key).and_then(Value::as_str))
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_string)
}

fn reminder_id(seed: &str) -> i32 {
    let mut hash = 0x811c9dc5u32;
    for byte in seed.as_bytes() {
        hash ^= u32::from(*byte);
        hash = hash.wrapping_mul(0x01000193);
    }
    let id = (hash & 0x7fff_ffff) as i32;
    if id == 0 { 1 } else { id }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reminder_ids_are_stable() {
        assert_eq!(reminder_id("meeting|1|08:30|15"), reminder_id("meeting|1|08:30|15"));
        assert_ne!(reminder_id("meeting|1|08:30|15"), reminder_id("meeting|2|08:30|15"));
    }

    #[test]
    fn accepts_common_times() {
        assert!(parse_time("08:30").is_some());
        assert!(parse_time("08:30:00").is_some());
    }
}
