use chrono::{Datelike, Duration, NaiveDate};
use schedule_core::{ImportBundle, ImportedCourse};
use serde::Deserialize;
use std::collections::HashMap;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum ImportError {
    #[error("unsupported import format: {0}")]
    Unsupported(String),
    #[error("invalid JSON: {0}")]
    InvalidJson(#[from] serde_json::Error),
    #[error("invalid YAML/CSES: {0}")]
    InvalidYaml(#[from] serde_yaml::Error),
    #[error("invalid import structure: {0}")]
    InvalidStructure(&'static str),
    #[error("invalid or incomplete iCalendar data")]
    InvalidIcs,
    #[error("invalid CSV: {0}")]
    InvalidCsv(String),
}

pub fn parse(format: &str, payload: &str) -> Result<ImportBundle, ImportError> {
    match format.to_ascii_lowercase().as_str() {
        "json" | "shiguang-json" | "wakeup-json" => parse_json(payload),
        "ics" | "ical" => parse_ics(payload),
        "csv" | "tsv" => parse_csv(payload),
        "cses" | "yaml" | "yml" => parse_cses(payload),
        other => Err(ImportError::Unsupported(other.to_string())),
    }
}

fn parse_json(payload: &str) -> Result<ImportBundle, ImportError> {
    if let Ok(bundle) = serde_json::from_str::<ImportBundle>(payload) {
        return Ok(bundle);
    }

    let value: serde_json::Value = serde_json::from_str(payload)?;
    let rows = value
        .get("courses")
        .and_then(|v| v.as_array())
        .or_else(|| value.get("courseList").and_then(|v| v.as_array()))
        .or_else(|| value.as_array())
        .ok_or(ImportError::InvalidStructure("missing courses array"))?;

    let mut courses = Vec::new();
    for row in rows {
        let name = string_field(row, &["name", "courseName", "course", "kcmc"]).unwrap_or_else(|| "未命名课程".to_string());
        let weekday = number_field(row, &["day", "weekday", "weekDay", "xq"]).unwrap_or(1) as u8;
        let start_section = number_field(row, &["startSection", "startNode", "start", "start_section"]).unwrap_or(1) as u8;
        let end_section = number_field(row, &["endSection", "endNode", "end", "end_section"])
            .map(|v| v as u8)
            .or_else(|| number_field(row, &["step"]).map(|step| start_section.saturating_add(step as u8).saturating_sub(1)))
            .unwrap_or(start_section);
        let weeks = parse_weeks(row).unwrap_or_else(|| (1..=20).collect());

        courses.push(ImportedCourse {
            name,
            teacher: string_field(row, &["teacher", "teacherName", "teaxms"]),
            location: string_field(row, &["position", "location", "room", "jxcdmc"]),
            weekday,
            start_section,
            end_section,
            weeks,
            start_time: string_field(row, &["startTime", "start_time"]),
            end_time: string_field(row, &["endTime", "end_time"]),
        });
    }

    let source = if rows.iter().any(|r| r.get("startNode").is_some()) { "wakeup-json" } else { "json" };
    Ok(ImportBundle {
        source: source.into(),
        term_name: string_field(&value, &["termName", "semesterName"]),
        term_start: string_field(&value, &["termStart", "startDate"]),
        courses,
        metadata: HashMap::new(),
    })
}

fn string_field(value: &serde_json::Value, keys: &[&str]) -> Option<String> {
    keys.iter()
        .find_map(|key| value.get(*key))
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
}

fn number_field(value: &serde_json::Value, keys: &[&str]) -> Option<u64> {
    keys.iter().find_map(|key| {
        value.get(*key).and_then(|v| {
            v.as_u64().or_else(|| v.as_str().and_then(|s| s.parse::<u64>().ok()))
        })
    })
}

fn parse_weeks(row: &serde_json::Value) -> Option<Vec<u8>> {
    if let Some(array) = row.get("weeks").and_then(|v| v.as_array()) {
        return Some(array.iter().filter_map(|v| v.as_u64().map(|x| x as u8)).collect());
    }
    let start = number_field(row, &["startWeek", "start_week"])? as u8;
    let end = number_field(row, &["endWeek", "end_week"]).unwrap_or(start as u64) as u8;
    let kind = number_field(row, &["type", "weekType"]).unwrap_or(0);
    Some((start..=end)
        .filter(|week| match kind {
            1 => week % 2 == 1,
            2 => week % 2 == 0,
            _ => true,
        })
        .collect())
}

#[derive(Debug, Clone)]
struct IcsEvent {
    summary: String,
    location: Option<String>,
    start_date: NaiveDate,
    start_time: Option<String>,
    end_time: Option<String>,
    rrule: Option<String>,
}

fn parse_ics(payload: &str) -> Result<ImportBundle, ImportError> {
    let unfolded = unfold_ics(payload);
    let mut events = Vec::new();
    let mut current: HashMap<String, String> = HashMap::new();
    let mut in_event = false;

    for line in unfolded.lines() {
        match line {
            "BEGIN:VEVENT" => {
                in_event = true;
                current.clear();
            }
            "END:VEVENT" if in_event => {
                let start_raw = current.get("DTSTART").ok_or(ImportError::InvalidIcs)?;
                let (date, start_time) = parse_ics_datetime(start_raw).ok_or(ImportError::InvalidIcs)?;
                let end_time = current.get("DTEND").and_then(|v| parse_ics_datetime(v)).and_then(|(_, t)| t);
                events.push(IcsEvent {
                    summary: current.get("SUMMARY").cloned().unwrap_or_else(|| "未命名课程".into()),
                    location: current.get("LOCATION").cloned(),
                    start_date: date,
                    start_time,
                    end_time,
                    rrule: current.get("RRULE").cloned(),
                });
                in_event = false;
            }
            _ if in_event => {
                if let Some((key, value)) = line.split_once(':') {
                    let key = key.split(';').next().unwrap_or(key).to_string();
                    current.insert(key, unescape_ics(value));
                }
            }
            _ => {}
        }
    }

    if events.is_empty() {
        return Err(ImportError::InvalidIcs);
    }

    let first_date = events.iter().map(|event| event.start_date).min().ok_or(ImportError::InvalidIcs)?;
    let term_start = first_date - Duration::days(first_date.weekday().num_days_from_monday() as i64);
    let mut grouped: HashMap<String, ImportedCourse> = HashMap::new();

    for event in events {
        let dates = expand_rrule_dates(event.start_date, event.rrule.as_deref());
        for date in dates {
            let week = ((date - term_start).num_days() / 7 + 1).clamp(1, 64) as u8;
            let weekday = date.weekday().number_from_monday() as u8;
            let key = format!(
                "{}\u{1f}{}\u{1f}{}\u{1f}{}",
                event.summary,
                weekday,
                event.start_time.as_deref().unwrap_or(""),
                event.location.as_deref().unwrap_or("")
            );
            grouped
                .entry(key)
                .and_modify(|course| {
                    if !course.weeks.contains(&week) {
                        course.weeks.push(week);
                        course.weeks.sort_unstable();
                    }
                })
                .or_insert_with(|| ImportedCourse {
                    name: event.summary.clone(),
                    teacher: None,
                    location: event.location.clone(),
                    weekday,
                    start_section: 1,
                    end_section: 1,
                    weeks: vec![week],
                    start_time: event.start_time.clone(),
                    end_time: event.end_time.clone(),
                });
        }
    }

    Ok(ImportBundle {
        source: "ics".into(),
        term_name: None,
        term_start: Some(term_start.format("%Y-%m-%d").to_string()),
        courses: grouped.into_values().collect(),
        metadata: HashMap::new(),
    })
}

fn unfold_ics(payload: &str) -> String {
    let normalized = payload.replace("\r\n", "\n");
    let mut out: Vec<String> = Vec::new();
    for line in normalized.lines() {
        if (line.starts_with(' ') || line.starts_with('\t')) && !out.is_empty() {
            out.last_mut().unwrap().push_str(line.trim_start());
        } else {
            out.push(line.to_string());
        }
    }
    out.join("\n")
}

fn unescape_ics(value: &str) -> String {
    value
        .replace("\\n", "\n")
        .replace("\\,", ",")
        .replace("\\;", ";")
        .replace("\\\\", "\\")
}

fn parse_ics_datetime(value: &str) -> Option<(NaiveDate, Option<String>)> {
    let raw = value.trim_end_matches('Z');
    let digits: String = raw.chars().filter(|c| c.is_ascii_digit()).collect();
    if digits.len() < 8 {
        return None;
    }
    let date = NaiveDate::parse_from_str(&digits[..8], "%Y%m%d").ok()?;
    let time = if digits.len() >= 12 {
        Some(format!("{}:{}", &digits[8..10], &digits[10..12]))
    } else {
        None
    };
    Some((date, time))
}

fn expand_rrule_dates(start: NaiveDate, rule: Option<&str>) -> Vec<NaiveDate> {
    let Some(rule) = rule else { return vec![start]; };
    if !rule.to_ascii_uppercase().contains("FREQ=WEEKLY") {
        return vec![start];
    }
    let parts: HashMap<String, String> = rule
        .split(';')
        .filter_map(|part| part.split_once('='))
        .map(|(k, v)| (k.to_ascii_uppercase(), v.to_string()))
        .collect();
    let interval = parts.get("INTERVAL").and_then(|v| v.parse::<i64>().ok()).unwrap_or(1).max(1);
    let count = parts.get("COUNT").and_then(|v| v.parse::<usize>().ok()).unwrap_or(20).min(64);
    let until = parts.get("UNTIL").and_then(|v| parse_ics_datetime(v)).map(|(date, _)| date);

    let mut dates = Vec::new();
    let mut current = start;
    while dates.len() < count {
        if until.is_some_and(|limit| current > limit) {
            break;
        }
        dates.push(current);
        current += Duration::weeks(interval);
    }
    dates
}

fn parse_csv(payload: &str) -> Result<ImportBundle, ImportError> {
    let delimiter = if payload.lines().next().is_some_and(|line| line.contains('\t') && !line.contains(',')) { b'\t' } else { b',' };
    let mut reader = csv::ReaderBuilder::new()
        .delimiter(delimiter)
        .flexible(true)
        .trim(csv::Trim::All)
        .from_reader(payload.as_bytes());
    let headers = reader
        .headers()
        .map_err(|e| ImportError::InvalidCsv(e.to_string()))?
        .iter()
        .map(|value| value.trim().to_ascii_lowercase())
        .collect::<Vec<_>>();
    let idx = |names: &[&str]| headers.iter().position(|h| names.iter().any(|name| h == name));
    let name_idx = idx(&["name", "course", "课程", "课程名"]).ok_or_else(|| ImportError::InvalidCsv("missing course name column".into()))?;
    let day_idx = idx(&["day", "weekday", "星期"]);
    let start_idx = idx(&["startsection", "start", "开始节次"]);
    let end_idx = idx(&["endsection", "end", "结束节次"]);
    let room_idx = idx(&["room", "location", "教室"]);
    let teacher_idx = idx(&["teacher", "教师", "老师"]);
    let weeks_idx = idx(&["weeks", "周次"]);
    let start_time_idx = idx(&["starttime", "start_time", "开始时间"]);
    let end_time_idx = idx(&["endtime", "end_time", "结束时间"]);

    let mut courses = Vec::new();
    for row in reader.records() {
        let row = row.map_err(|e| ImportError::InvalidCsv(e.to_string()))?;
        let get = |index: Option<usize>| index.and_then(|i| row.get(i)).map(str::trim);
        let name = row.get(name_idx).unwrap_or("未命名课程").trim().to_string();
        let start_section = get(start_idx).and_then(|v| v.parse().ok()).unwrap_or(1);
        let weeks = get(weeks_idx).map(parse_week_text).filter(|v| !v.is_empty()).unwrap_or_else(|| (1..=20).collect());
        courses.push(ImportedCourse {
            name,
            teacher: get(teacher_idx).filter(|s| !s.is_empty()).map(str::to_string),
            location: get(room_idx).filter(|s| !s.is_empty()).map(str::to_string),
            weekday: get(day_idx).and_then(|v| v.parse().ok()).unwrap_or(1),
            start_section,
            end_section: get(end_idx).and_then(|v| v.parse().ok()).unwrap_or(start_section),
            weeks,
            start_time: get(start_time_idx).filter(|s| !s.is_empty()).map(str::to_string),
            end_time: get(end_time_idx).filter(|s| !s.is_empty()).map(str::to_string),
        });
    }

    Ok(ImportBundle {
        source: if delimiter == b'\t' { "tsv".into() } else { "csv".into() },
        term_name: None,
        term_start: None,
        courses,
        metadata: HashMap::new(),
    })
}

fn parse_week_text(value: &str) -> Vec<u8> {
    let mut weeks = Vec::new();
    for token in value.replace('，', ",").split(',') {
        let token = token.trim().trim_end_matches('周');
        if let Some((start, end)) = token.split_once('-').or_else(|| token.split_once('~')) {
            if let (Ok(start), Ok(end)) = (start.trim().parse::<u8>(), end.trim().parse::<u8>()) {
                weeks.extend(start..=end);
            }
        } else if let Ok(week) = token.parse::<u8>() {
            weeks.push(week);
        }
    }
    weeks.sort_unstable();
    weeks.dedup();
    weeks
}

#[derive(Debug, Deserialize)]
struct CsesFile {
    version: u8,
    subjects: Vec<CsesSubject>,
    schedules: Vec<CsesSchedule>,
}

#[derive(Debug, Deserialize)]
struct CsesSubject {
    name: String,
    teacher: Option<String>,
    room: Option<String>,
}

#[derive(Debug, Deserialize)]
struct CsesSchedule {
    enable_day: u8,
    weeks: String,
    classes: Vec<CsesClass>,
}

#[derive(Debug, Deserialize)]
struct CsesClass {
    subject: String,
    start_time: String,
    end_time: String,
}

fn parse_cses(payload: &str) -> Result<ImportBundle, ImportError> {
    let file: CsesFile = serde_yaml::from_str(payload)?;
    if file.version != 1 {
        return Err(ImportError::InvalidStructure("unsupported CSES version"));
    }
    let subjects: HashMap<String, CsesSubject> = file.subjects.into_iter().map(|subject| (subject.name.clone(), subject)).collect();
    let mut courses = Vec::new();
    for schedule in file.schedules {
        let weeks: Vec<u8> = match schedule.weeks.to_ascii_lowercase().as_str() {
            "odd" => (1..=24).filter(|week| week % 2 == 1).collect(),
            "even" => (1..=24).filter(|week| week % 2 == 0).collect(),
            _ => (1..=24).collect(),
        };
        for (index, class) in schedule.classes.into_iter().enumerate() {
            let subject = subjects.get(&class.subject);
            courses.push(ImportedCourse {
                name: class.subject,
                teacher: subject.and_then(|value| value.teacher.clone()),
                location: subject.and_then(|value| value.room.clone()),
                weekday: schedule.enable_day,
                start_section: (index + 1).min(u8::MAX as usize) as u8,
                end_section: (index + 1).min(u8::MAX as usize) as u8,
                weeks: weeks.clone(),
                start_time: Some(class.start_time),
                end_time: Some(class.end_time),
            });
        }
    }
    Ok(ImportBundle {
        source: "cses".into(),
        term_name: None,
        term_start: None,
        courses,
        metadata: HashMap::from([("schemaVersion".into(), file.version.to_string())]),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wakeup_style_json_is_normalized() {
        let bundle = parse("json", r#"[{"courseName":"高数","day":1,"startNode":3,"step":2,"startWeek":1,"endWeek":8,"type":1,"room":"A101"}]"#).unwrap();
        assert_eq!(bundle.courses[0].name, "高数");
        assert_eq!(bundle.courses[0].end_section, 4);
        assert_eq!(bundle.courses[0].weeks, vec![1, 3, 5, 7]);
    }

    #[test]
    fn cses_is_normalized() {
        let bundle = parse("cses", r#"version: 1
subjects:
  - name: 数学
    teacher: 张老师
    room: '101'
schedules:
  - name: 星期一
    enable_day: 1
    weeks: odd
    classes:
      - subject: 数学
        start_time: '08:00:00'
        end_time: '09:00:00'
"#).unwrap();
        assert_eq!(bundle.source, "cses");
        assert_eq!(bundle.courses[0].weeks[0], 1);
        assert_eq!(bundle.courses[0].weeks[1], 3);
    }
}
