# Data sources and update policy

LumaSchedule is Local-first. User course data is not fetched from or uploaded to any LumaSchedule server. External network access is used only when the user explicitly uses a feature that needs it, such as university login or WebDAV backup.

## 1. University adapter catalog

Primary source:

- Repository: https://github.com/XingHeYuZhuan/shiguang_warehouse
- Upstream app: https://github.com/XingHeYuZhuan/shiguangschedule
- Adapter warehouse license: MIT at the time of this document update.
- LumaSchedule compatibility runtime: Apache-2.0 code in this repository.

LumaSchedule uses:

- `index/root_index.yaml`
- `resources/<school>/adapters.yaml`
- the JavaScript asset referenced by the selected Adapter

The warehouse contains both school-specific entries and generic education-system entries such as Zhengfang, Chaoxing, Qingguo/Kingosoft and URP.

### Update strategy

1. GitHub Actions checks out the current upstream warehouse when building Android.
2. `scripts/sync-shiguang-adapters.mjs` copies the complete catalog/resources into `vendor/shiguang_warehouse` and records snapshot metadata.
3. The APK packages that snapshot as local Android Assets.
4. School search and Adapter lookup therefore open from local storage and an in-memory cache instead of waiting for GitHub network access.
5. The scheduled sync workflow keeps the repository snapshot fresh between release builds.
6. The UI reports the catalog it actually loaded instead of hardcoding a school count in documentation.

This design deliberately prefers a deterministic, offline-capable build snapshot over runtime code download.

### Trust model

Adapter data is treated as third-party input, not trusted application code. The school runtime restricts origins and Bridge capabilities. See `docs/ADAPTER_RUNTIME.md`.

## 2. Open interchange formats

LumaSchedule accepts user-selected local files in interoperable formats:

- JSON / LumaSchedule canonical format
- WakeUp-like JSON fields
- ICS / iCalendar
- CSV / TSV
- CSES v1 YAML

These small timetable files are parsed locally inside the app WebView by dependency-free TypeScript parsers, normalized into `ImportBundle`, previewed, and then committed to SQLite through the native Kotlin Bridge.

CSES reference ecosystem:

- SmartTeachCN / CSES and related ClassIsland projects
- Used as an interoperability/schema reference; their original licenses continue to apply to their own code and schemas.

## 3. WebDAV

WebDAV endpoints and credentials are supplied by the user.

- Server URL, username and remote path can be stored in local app settings.
- Password / app-specific password is kept only for the current process session.
- Public-network WebDAV requires HTTPS.
- HTTP is limited to localhost/private-network targets according to runtime policy.
- HTTPS uses the platform trust store and hostname verification.
- The current implementation performs manual full-backup upload/restore, not server-side indexing of user schedules.

## 4. Reference projects are not data sources

Projects listed in `docs/REFERENCE_PROJECTS.md` are used for product, UX, architecture and interoperability research. LumaSchedule does not automatically download or redistribute their source code unless a separate component is explicitly documented and its license permits that use.

## 5. XiaoAi / AISchedule ecosystem

There are many open GitHub repositories containing school-specific XiaoAi/AISchedule parser scripts. The ecosystem may be useful for expanding coverage, but it is fragmented across repositories and licenses.

LumaSchedule therefore does **not** mass-vendor these scripts today. A future compatibility layer must first define:

- a stable adapter contract;
- per-script license and attribution metadata;
- origin/capability sandboxing;
- automated compatibility tests;
- an opt-in distribution/update mechanism.

Until then, Shiguang Warehouse + generic education-system adapters + open file formats are the supported university/import coverage strategy.
