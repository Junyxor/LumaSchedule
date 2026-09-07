use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct WeekMask(pub u64);

impl WeekMask {
    pub fn from_weeks(weeks: impl IntoIterator<Item = u8>) -> Self {
        let mut mask = 0u64;
        for week in weeks {
            if (1..=64).contains(&week) {
                mask |= 1u64 << (week - 1);
            }
        }
        Self(mask)
    }

    pub fn contains(self, week: u8) -> bool {
        (1..=64).contains(&week) && (self.0 & (1u64 << (week - 1))) != 0
    }

    pub fn intersects(self, other: Self) -> bool {
        (self.0 & other.0) != 0
    }

    pub fn weeks(self) -> Vec<u8> {
        (1..=64).filter(|week| self.contains(*week)).collect()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Term {
    pub id: Uuid,
    pub name: String,
    pub start_date: String,
    pub week_count: u8,
    pub timezone: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Schedule {
    pub id: Uuid,
    pub term_id: Uuid,
    pub name: String,
    pub week_starts_on: u8,
    pub time_scheme_id: Option<Uuid>,
}


#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SectionTime {
    pub number: u8,
    pub start_time: String,
    pub end_time: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TimeScheme {
    pub id: Uuid,
    pub name: String,
    pub sections: Vec<SectionTime>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum ReminderMoment {
    BeforeStart,
    AtStart,
    AtEnd,
    DailyDigest,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReminderRule {
    pub id: Uuid,
    pub schedule_id: Uuid,
    pub course_id: Option<Uuid>,
    pub moment: ReminderMoment,
    pub offset_minutes: i16,
    pub enabled: bool,
    pub sound: bool,
    pub vibration: bool,
    pub respect_holidays: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum AutomationAction {
    EnableDoNotDisturb,
    EnableSilent,
    RestoreAudioState,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CourseAutomation {
    pub id: Uuid,
    pub schedule_id: Uuid,
    pub action: AutomationAction,
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Course {
    pub id: Uuid,
    pub schedule_id: Uuid,
    pub name: String,
    pub code: Option<String>,
    pub teacher: Option<String>,
    pub color_token: String,
    pub note: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CourseMeeting {
    pub id: Uuid,
    pub course_id: Uuid,
    pub weekday: u8,
    pub start_section: u8,
    pub end_section: u8,
    pub start_time: Option<String>,
    pub end_time: Option<String>,
    pub weeks: WeekMask,
    pub location: Option<String>,
}

impl CourseMeeting {
    pub fn overlaps(&self, other: &Self) -> bool {
        self.weekday == other.weekday
            && self.weeks.intersects(other.weeks)
            && self.start_section <= other.end_section
            && other.start_section <= self.end_section
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum ExceptionKind {
    Cancelled,
    Rescheduled,
    RoomChanged,
    TeacherChanged,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CourseException {
    pub id: Uuid,
    pub meeting_id: Uuid,
    pub week: u8,
    pub kind: ExceptionKind,
    pub new_weekday: Option<u8>,
    pub new_start_section: Option<u8>,
    pub new_end_section: Option<u8>,
    pub new_location: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Conflict {
    pub left_meeting_id: Uuid,
    pub right_meeting_id: Uuid,
    pub shared_weeks: Vec<u8>,
}

pub fn detect_conflicts(meetings: &[CourseMeeting]) -> Vec<Conflict> {
    let mut out = Vec::new();
    for i in 0..meetings.len() {
        for j in (i + 1)..meetings.len() {
            let left = &meetings[i];
            let right = &meetings[j];
            if left.overlaps(right) {
                out.push(Conflict {
                    left_meeting_id: left.id,
                    right_meeting_id: right.id,
                    shared_weeks: WeekMask(left.weeks.0 & right.weeks.0).weeks(),
                });
            }
        }
    }
    out
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportedCourse {
    pub name: String,
    pub teacher: Option<String>,
    pub location: Option<String>,
    pub weekday: u8,
    pub start_section: u8,
    pub end_section: u8,
    pub weeks: Vec<u8>,
    pub start_time: Option<String>,
    pub end_time: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportBundle {
    pub source: String,
    pub term_name: Option<String>,
    pub term_start: Option<String>,
    pub courses: Vec<ImportedCourse>,
    pub metadata: HashMap<String, String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn week_mask_and_conflicts_work() {
        let a = WeekMask::from_weeks([1, 3, 5]);
        let b = WeekMask::from_weeks([2, 3, 4]);
        assert!(a.contains(5));
        assert!(!a.contains(2));
        assert!(a.intersects(b));
        assert_eq!(WeekMask(a.0 & b.0).weeks(), vec![3]);
    }
}
