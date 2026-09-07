# Data sources and update policy

LumaSchedule is Local-first. User course data is not fetched from or uploaded to any LumaSchedule server. External network access is used only when the user invokes features that require it, such as university adapter discovery/login or WebDAV backup.

## 1. University adapter catalog

Primary source:

- Repository: https://github.com/XingHeYuZhuan/shiguang_warehouse
- Upstream project: https://github.com/XingHeYuZhuan/shiguangschedule
- Adapter warehouse license: MIT at the time of this document update.
- LumaSchedule compatibility runtime: Apache-2.0 code written in this repository.

LumaSchedule reads:

- `index/root_index.yaml`
- `resources/<school>/adapters.yaml`
- adapter JavaScript assets referenced by the selected adapter

The warehouse contains both school-specific entries and generic education-system entries such as Zhengfang, Chaoxing, Kingosoft/Qingguo and URP.

### Update strategy

1. Runtime prefers the current upstream GitHub raw content.
2. If the network request fails, LumaSchedule falls back to the bundled snapshot.
3. GitHub Actions periodically refreshes the bundled snapshot.
4. The UI shows the number of schools and generic entries from the catalog actually loaded at runtime instead of hardcoding a number in documentation.

### Trust model

Adapter data is treated as third-party input, not trusted application code. The runtime restricts declared origins and Bridge capabilities. See `docs/ADAPTER_RUNTIME.md`.

## 2. Open interchange formats

LumaSchedule also accepts user-provided data in interoperable formats:

- JSON / LumaSchedule canonical format
- WakeUp-like JSON fields
- ICS / iCalendar
- CSV / TSV
- CSES v1 YAML

These files are selected by the user and parsed locally by Rust.

CSES reference ecosystem:

- SmartTeachCN / CSES and ClassIsland related projects
- Used as an interoperability/schema reference; original licenses continue to apply to their own code and schemas.

## 3. WebDAV

WebDAV endpoints and credentials are supplied by the user.

- Server URL, username and remote path can be stored in local app settings.
- Password / app-specific password is kept only for the current process session.
- Public-network WebDAV requires HTTPS; local/private hosts may use HTTP according to the runtime policy.
- LumaSchedule currently performs manual full-backup upload/restore, not server-side indexing of user schedules.

## 4. Reference projects are not data sources

Projects listed in `docs/REFERENCE_PROJECTS.md` are used for product, UX, architecture and interoperability research. LumaSchedule does not automatically download or redistribute their source code unless a separate component is explicitly documented and its license permits that use.

## 5. XiaoAi / AISchedule ecosystem

There are many open GitHub repositories containing school-specific XiaoAi/AISchedule parser scripts. The ecosystem is potentially valuable for expanding university coverage, but it is fragmented across repositories and licenses.

LumaSchedule therefore does **not** mass-vendor these scripts today. A future compatibility layer must first define:

- a stable adapter contract;
- per-script license and attribution metadata;
- origin/capability sandboxing;
- automated compatibility tests;
- an opt-in distribution/update mechanism.

Until then, Shiguang Warehouse + generic education-system adapters + open file formats remain the supported university/import coverage strategy.
