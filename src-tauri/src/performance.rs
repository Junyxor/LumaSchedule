use crate::db::AppDb;

pub fn tune(db: &AppDb) -> Result<(), String> {
    let conn = db.0.lock().map_err(|_| "database lock poisoned".to_string())?;
    conn.execute_batch(
        r#"
        PRAGMA synchronous=NORMAL;
        PRAGMA temp_store=MEMORY;

        CREATE INDEX IF NOT EXISTS idx_schedules_term
          ON schedules(term_id);
        CREATE INDEX IF NOT EXISTS idx_courses_schedule
          ON courses(schedule_id);
        CREATE INDEX IF NOT EXISTS idx_meetings_course_day_section
          ON course_meetings(course_id, weekday, start_section);
        CREATE INDEX IF NOT EXISTS idx_exceptions_meeting_week
          ON course_exceptions(meeting_id, week);
        CREATE INDEX IF NOT EXISTS idx_reminders_schedule_course
          ON reminder_rules(schedule_id, course_id);
        CREATE INDEX IF NOT EXISTS idx_import_audit_created
          ON import_audit(created_at);

        PRAGMA optimize;
        "#,
    )
    .map_err(|e| e.to_string())?;
    Ok(())
}
