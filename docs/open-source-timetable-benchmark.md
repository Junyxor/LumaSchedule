# LumaSchedule open-source timetable benchmark

This document keeps product decisions grounded in mature open-source timetable projects instead of adding settings ad hoc.

## Reference projects

| Project | What it is useful for | Relevant ideas for LumaSchedule |
| --- | --- | --- |
| `HF-CYGG/Dawn-Course` | Modern Android timetable app | local-first, multi-semester management, day/week views, course color management, widgets, teaching-system scripts with online/cache/assets fallback, backup preview, WebDAV auto-sync, reminders, DND/auto-mute |
| `zfman/TimetableView` / monster timetable ecosystem | Mature Android timetable component | weekend visibility, course color management, background/transparency, blank-cell interaction, long-press course actions, configurable dimensions, portable local configuration |
| `ClassIsland/ClassIsland` | Deep schedule presentation and reminder logic | hide finished classes, fade completed classes, current-class focus, configurable spacing, tomorrow-schedule rules, precise countdown, temporary schedule/change highlighting |
| `Class-Widgets/Class-Widgets` and Class Widgets 2 | Timetable widgets and customization | multiple timetable files, CSES exchange, temporary schedule/swap handling, theme system, reminders/pre-bell/TTS, customizable widgets and countdowns |
| `SmartTeachCN/CSES` | Timetable exchange standard | keep CSES v1 import/export compatibility as a first-class interchange path |
| `XingHeYuZhuan/shiguang_warehouse` | University teaching-system adapter ecosystem | school adapter discovery, generic teaching-system fallback, online-first + bundled snapshot fallback |

## Product decisions

### P0 — daily usability and data safety

- Smart weekend columns: `Auto / Weekdays / Saturday / Sunday / Both`.
  - Auto only renders weekend columns that contain classes in the currently selected/filtered week.
  - A Sunday-only week must not waste a blank Saturday column.
- Import diff before writing data.
  - New meetings, duplicates, time conflicts, and meetings removed by overwrite must be visible before commit.
  - Import modes: create new schedule, merge, overwrite.
- Native/Web confirmation sheets for destructive operations; no Tauri runtime dependency on Android.
- Semester basics: name, start date, total weeks, timezone, visible section count.
- Display controls: teacher, room, exact time, compact density.

### P1 — mature timetable behavior

- Multiple semesters / schedules with an explicit schedule switcher instead of relying on the newest database row.
- Course color management: automatic stable color plus per-course override.
- Finished-class behavior: show normally / fade / hide.
- Tomorrow preview: never / after today's classes / when today is empty / always.
- Temporary changes: cancel class, reschedule, substitute location/teacher, swap classes; show a clear visual marker.
- Conflict rendering: side-by-side or stacked conflict cards with conflict warning, not silent overlap.
- Import history with source, timestamp, counts, and rollback-friendly backup link.

### P2 — widgets, automation, and appearance

- Android widgets: next class, today's list, two-day view, full week view.
- Widget transparency/density and system-theme adaptation.
- Reminder profiles: per-course override, vibration/sound, quiet hours, custom lead time.
- Optional DND/auto-mute automation around class time with explicit permission and per-schedule control.
- Appearance: light/dark/system, accent color, background image, blur/brightness, Material-style dynamic color where appropriate while keeping Luma's Liquid Glass identity.
- WebDAV auto-backup policy: manual/daily/on-change, retention count, Wi-Fi-only option, last successful backup status.

## Deliberate non-goals for the current Native stabilization phase

- Do not reintroduce Rust/Tauri/NDK on Android for features that Android/Kotlin can provide directly.
- Do not add cloud accounts as a requirement; local-first remains the default.
- Do not add a feature only because another timetable has it. It must improve university timetable use, data portability, reliability, or daily glanceability.

## Current implementation status

- Native Android core, SQLite, reminders, next-course widget, SAF import/export, full backup/restore and WebDAV are connected.
- Shiguang adapter snapshot and GDUT path are bundled and smoke-tested.
- Smart weekend display, timetable display settings, adapter health summary, native confirmation UI, and import diff/merge/overwrite are being integrated on `feat/android-native-core`.
- Next large product phase after Native E2E stabilization: multi-schedule switcher + course colors + temporary schedule changes + richer widgets.
