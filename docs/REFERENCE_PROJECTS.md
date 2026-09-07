# Reference projects and license boundaries

LumaSchedule borrows product ideas, architecture lessons and interoperability goals from established timetable/scheduling projects. We do not copy incompatible source code or proprietary assets.

## ShiGuang Schedule

- https://github.com/XingHeYuZhuan/shiguangschedule
- App repository: Apache-2.0 at the time this project was bootstrapped.
- Key ideas studied: widgets, schedule customization, WebDAV, calendar export/sync, reminders, adapter-driven school import.

## ShiGuang adapter warehouse

- https://github.com/XingHeYuZhuan/shiguang_warehouse
- Adapter repository: MIT at the time this project was bootstrapped.
- Key ideas studied: indexed school catalog, YAML manifests, JavaScript adapters, reusable generic education-system adapters.
- LumaSchedule plans a compatibility runtime rather than silently vendoring the repository.

## WakeUp Schedule

- https://github.com/YZune/WakeUpSchedule
- Key ideas studied: simple student-first timetable UX, file-based backup/share, school import workflow.

## QuACS

- https://github.com/quacs/quacs
- MIT.
- Key idea studied: browser-side Rust/WASM for schedule computation, privacy-preserving client-side planning.

## BetterUntis

- https://github.com/SapuSeven/BetterUntis
- GPL-3.0.
- Key ideas studied: smart caching, mobile WeekView performance, room finding, notifications, multi-profile UX.
- GPL implementation code is not copied into this Apache-2.0 repository.

## AntAlmanac

- https://github.com/icssc/AntAlmanac
- MIT.
- Key ideas studied: course search, integrated calendar, maps, prerequisite/professor/grade context.

## Sleepy

- https://github.com/lingion/sleepy
- GPL-3.0 at the time this project was bootstrapped.
- Key ideas studied: Material You timetable UX, widgets, broad school/protocol import coverage.
- GPL implementation code is not copied into this Apache-2.0 repository.

## CSES / ClassIsland ecosystem

- SmartTeachCN/CSES is an MIT-licensed generic Course Schedule Exchange Schema. LumaSchedule v0.1 includes a CSES v1 YAML normalization path.
- ClassIsland is useful as a product reference for schedule import/export, temporary schedule changes, automation, plugins and strong reminders. Its GPL components are treated as design/behavior references only; Apache-2.0 LumaSchedule code does not copy GPL implementation code.
