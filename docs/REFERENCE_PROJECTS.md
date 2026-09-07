# Reference projects and license boundaries

LumaSchedule studies product behavior, interoperability formats and architecture patterns from established open-source timetable/scheduling projects. This does **not** mean their implementation code can be copied into this Apache-2.0 repository.

Repository: https://github.com/Junyxor/LumaSchedule

## ShiGuang Schedule / 拾光课程表

- https://github.com/XingHeYuZhuan/shiguangschedule
- License observed on GitHub: Apache-2.0.
- Active Kotlin / Compose Multiplatform timetable project focused on Chinese universities.
- Product ideas studied:
  - current-day and week timetable;
  - swipe week navigation and quick week jump;
  - global course management;
  - timetable/time-scheme binding;
  - school adapter import;
  - WebDAV;
  - calendar integration;
  - multiple widgets;
  - course reminders, DND/automation and holiday-aware behavior;
  - timetable block customization.

LumaSchedule does not clone its UI or Kotlin implementation. The major direct interoperability relationship is with its documented adapter Bridge/data ecosystem.

## ShiGuang adapter warehouse

- https://github.com/XingHeYuZhuan/shiguang_warehouse
- License observed on GitHub: MIT.
- This is an actual third-party **data/code source**, not merely a visual reference.
- LumaSchedule reads its school index, adapter manifests and selected JavaScript adapter assets through a sandboxed compatibility runtime.
- Upstream attribution/license material must stay available when snapshots are bundled.

See `docs/DATA_SOURCES.md` and `docs/ADAPTER_RUNTIME.md`.

## WakeUp Schedule

- https://github.com/YZune/WakeUpSchedule
- GitHub did not expose a repository-level SPDX license when this document was reviewed; therefore LumaSchedule treats it as a product/interoperability reference unless a specific file clearly grants compatible reuse rights.
- Ideas studied:
  - student-first timetable interaction;
  - import/share workflows;
  - familiar Chinese timetable field conventions.

## ClassIsland

- https://github.com/ClassIsland/ClassIsland
- License observed on GitHub: GPL-3.0.
- Ideas studied:
  - schedule exchange and temporary changes;
  - strong reminders/countdowns;
  - automation rules;
  - plugin architecture;
  - multi-platform schedule presentation.
- GPL implementation code is not copied into LumaSchedule.

## CSES ecosystem

- Course Schedule Exchange Schema is used as an interoperability/schema reference.
- LumaSchedule implements its own Rust normalization path for CSES v1 YAML.
- Schema/license attribution must follow the upstream CSES project where applicable.

## BetterUntis

- https://github.com/SapuSeven/BetterUntis
- Product ideas studied:
  - mobile week-view performance;
  - caching;
  - notifications;
  - room/profile workflows.
- Any GPL-licensed implementation remains reference-only for this Apache-2.0 project.

## AntAlmanac

- https://github.com/icssc/AntAlmanac
- Ideas studied:
  - integrated course search;
  - calendar-centric planning;
  - contextual course information.

This is primarily a planning/product reference rather than a Chinese teaching-system adapter source.

## QuACS

- https://github.com/quacs/quacs
- Ideas studied:
  - privacy-preserving client-side schedule computation;
  - Rust/WASM schedule logic;
  - fast local planning.

## XiaoAi / AISchedule adapter ecosystem

GitHub contains many school-specific XiaoAi/AISchedule parser repositories. Examples use a common browser-parser style, but the ecosystem is fragmented and licenses vary by repository/file.

LumaSchedule is researching this ecosystem as a possible **second adapter source**, but does not mass-vendor these scripts today. Before compatibility is enabled, the project needs:

1. a stable manifest/adapter contract;
2. per-script license metadata;
3. origin and Bridge sandboxing equivalent to the Shiguang runtime;
4. automated parser tests;
5. an opt-in distribution/update path.

## What we are actively borrowing at the product level

The current v0.1 roadmap intentionally prioritizes ideas that repeatedly appear across mature timetable projects:

- week navigation and quick week jump;
- explicit semester/start-date configuration;
- reliable course reminders;
- manual course CRUD;
- import preview/diff;
- generic education-system fallbacks;
- local-first backup/export;
- widgets;
- temporary course changes;
- calendar integration;
- strong but restrained customization.

The implementation remains LumaSchedule's own Rust/Tauri/Svelte/Kotlin code unless a third-party component is explicitly documented.
