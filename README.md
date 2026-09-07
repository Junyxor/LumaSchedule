# LumaSchedule · 流光课表

> 开源、无广告、Local-first 的课程表应用。Rust 负责数据模型、SQLite、导入、提醒计算和适配器安全边界；Svelte 5 + Tauri 2 提供跨平台界面；Android 原生 Kotlin 只承担通知、AlarmManager、Widget 与教务 WebView 等系统能力。

**Repository:** https://github.com/Junyxor/LumaSchedule  
**License:** Apache-2.0  
**Current stage:** v0.1 真机迭代中

LumaSchedule 不是截图原型。当前 Android arm64 Release 测试包已经可以安装运行，项目正在以“先把 Android v0.1 真机链路跑通，再扩平台和高级同步”为顺序收口。

## 核心原则

- **100% 开源、无广告**：不接广告 SDK，不依赖账号才能使用课表。
- **Local-first**：课程、设置、提醒规则默认保存在本地 SQLite。
- **Rust 作为核心层**：课程模型、周次、导入、存储、备份、提醒计算和适配器安全策略尽量不散落到平台代码。
- **平台原生能力保持原生**：Android 通知、AlarmManager、Widget、BootReceiver、教务登录 WebView 使用 Kotlin。
- **第三方适配器默认不可信**：只允许声明过的教务域名和受限 Bridge，不给脚本文件系统或 Shell 权限。
- **功能真实再展示**：未实现功能不在正式 UI 中伪装成可点击入口。

## 当前真实功能

### 课表

- 今日课程 / 下一节课
- 7 天周课表
- 当前教学周计算
- 单双周 / 指定周过滤
- 学期外自动清空当前周视图
- 学校作息表节次 → 真实时间映射
- 最多按实际课程动态扩展节次数（已覆盖广工 14 节场景）
- 手动新增课程
- 点击课程编辑
- 修改课程名、教师、教室、星期、节次、具体时间、上课周次
- 删除单个上课时段
- 空数据库显示真实空状态，不使用演示课程冒充用户数据

### 导入

当前 Rust 导入器支持：

- LumaSchedule Canonical JSON
- 拾光 / WakeUp 常见 JSON 字段
- ICS / iCalendar
- CSV
- TSV
- CSES v1 YAML

导入统一进入：

```text
File / School Adapter
        ↓
Rust parser / adapter bridge
        ↓
Canonical ImportBundle
        ↓
Preview
        ↓
SQLite transaction
        ↓
Term / Schedule / Course / Meeting
```

**暂未实现：XLS/XLSX、任意 HTML 自动识别。**

### 高校教务自动导入

当前使用开源的 `XingHeYuZhuan/shiguang_warehouse` 作为主要学校适配数据来源，并实现拾光 Bridge 兼容运行时。

流程：

```text
搜索学校
  ↓
选择学校 / 通用教务入口
  ↓
选择 adapter
  ↓
隔离 Android WebView 打开学校官方登录页
  ↓
用户自行登录
  ↓
adapter 抓取课程 / 作息 / 学期配置
  ↓
LumaSchedule 预览
  ↓
确认写入 SQLite
```

适配仓库采用**在线优先 + 内置快照兜底**：启动时优先读取 GitHub 上游当前索引；网络不可用时使用随 App 打包的最近 snapshot。GitHub Actions 也会定期同步上游。

除了具体学校，当前上游还提供以下通用入口作为兜底：

- 正方教务
- 超星教务
- 青果教务
- URP 教务

学校数量会随上游持续变化，因此 App 内直接显示当前同步到的具体高校数量与通用入口数量，不在 README 硬编码一个容易过期的数字。

广东工业大学 `GDUT_01` 是当前首个重点真机 E2E 验收目标。

### Android 通知与课程提醒

当前已有两层能力：

1. **即时通知测试**：验证 Android 通知权限和通知渠道。
2. **真实课程提醒**：用户可开启“上课前提醒”，选择提前 5 / 10 / 15 / 20 / 30 / 60 分钟。

课程提醒由 Rust 根据：

- 学期开始日期
- 课程周次
- 星期
- 上课时间 / 学校作息表
- 用户提前时间

计算所有未来提醒，再交给 Android `AlarmManager`。课程重新导入或手动修改后会增量重算；旧提醒自动取消，新提醒补上。

Android 原生层已有：

- `AlarmManager.setAndAllowWhileIdle`
- `ReminderReceiver`
- Android Notification Channel
- SharedPreferences 原生提醒快照
- `BOOT_COMPLETED`
- `MY_PACKAGE_REPLACED`
- 设备重启 / 应用更新后的未来提醒恢复

默认不会偷偷开启提醒；用户主动开启后才申请通知权限。

### Android Widget

当前真正实现的是一个**下一节课原生 Widget**：

- Kotlin `RemoteViews`
- `NextCourseWidgetProvider`
- Svelte → Rust → Kotlin Snapshot 更新
- 课表清空时会主动覆盖旧 Widget 数据

更多 Widget 尺寸仍在后续计划，不在 v0.1 UI 中伪装成已完成。

### Liquid Glass / Apple-like mobile UI

移动端设计原则不是“所有卡片都毛玻璃”，而是：

- 内容层保持清晰和安静
- 底部 Tab Bar / 搜索 / Sheet / 重要浮层使用 Liquid Glass
- 模糊、透明度、饱和度、高光、折射、噪点支持实时调整
- 底部导航占用系统安全区并固定贴底
- App 主界面禁止网页式双指缩放

### 数据、备份与 WebDAV

当前已实现：

- LumaSchedule JSON 导出
- ICS 导出
- 完整 `.luma.json` 快照备份 / 恢复
- WebDAV `PROPFIND`
- `MKCOL`
- `PUT` 上传备份
- `GET` 恢复备份
- 公网 WebDAV 强制 HTTPS
- localhost / 局域网可按策略允许 HTTP
- WebDAV 密码只保留在当前进程，不写入 SQLite

当前 WebDAV 是**手动完整备份 / 恢复**，不是双向实时同步。自动同步、版本历史和冲突合并属于后续阶段。

## 性能与包体

Android 构建已经从早期约 700 MB 的 Universal Debug 错误产物收敛到约 13 MiB 的 arm64 Release 测试包。早期膨胀原因是四份带 Debug Symbols 的 Rust `.so`，不是课表数据或拾光适配器。

当前构建策略：

- Android 真机测试默认 arm64
- Rust `opt-level = "z"`
- LTO
- 单 codegen unit
- `panic = "abort"`
- strip symbols
- Android R8
- Android resource shrinking
- SQLite WAL
- `synchronous=NORMAL`
- 常用 SQLite 查询索引
- `PRAGMA optimize`

目标仍然是继续减少启动、页面刷新和导入链路的无意义工作，而不是为了跑分删除实际功能。

## 技术栈

- Tauri 2
- Rust stable
- Svelte 5 + TypeScript + Vite
- SQLite / rusqlite bundled
- reqwest + rustls
- Android Kotlin
- RemoteViews
- AlarmManager / BroadcastReceiver
- GitHub Actions

## 工程结构

```text
assets/                      App icon source
crates/
  schedule-core/             Canonical model / WeekMask / conflicts / reminders
  schedule-import/           JSON / WakeUp-like / ICS / CSV / TSV / CSES
  schedule-adapter/          Adapter manifest / capability / origin policy
src/                         Svelte UI
src-tauri/                   Tauri commands + SQLite + Rust services
native/android/              Widget / reminder / Shiguang WebView native sources
scripts/                     Android patcher + Shiguang adapter sync
vendor/                      Third-party snapshot / license notices
.github/workflows/           CI / Android / Desktop / adapter sync
```

## 数据模型

Rust Core 已有：

- `Term`
- `Schedule`
- `TimeScheme`
- `SectionTime`
- `Course`
- `CourseMeeting`
- `CourseException`
- `ReminderRule`
- `CourseAutomation`
- `WeekMask(u64)`
- 课程重叠 / 冲突检测
- `ImportBundle`

SQLite 包含：学期、作息方案、课表、课程、课程时段、临时变更、适配器来源、提醒规则、课程自动化、日历绑定、同步配置、设置和导入审计。

## 安全边界

高校 adapter 不因为“来自开源仓库”就自动获得信任。当前约束包括：

- 教务域名白名单
- SSO 同机构子域允许规则
- adapter 声明 HTTP 才允许旧系统使用 HTTP
- Android WebView 禁止任意文件 / content 访问
- 受限 Bridge
- adapter 只提交课程 / 作息 / 学期配置
- 导入结果先预览，再写入数据库

详见 [`docs/ADAPTER_RUNTIME.md`](docs/ADAPTER_RUNTIME.md)。

## 参考项目与数据来源

LumaSchedule 参考了多个开源项目的产品设计、兼容协议和架构经验，但不会直接复制许可证不兼容的实现代码。

- 参考项目与许可证边界：[`docs/REFERENCE_PROJECTS.md`](docs/REFERENCE_PROJECTS.md)
- 数据来源与更新策略：[`docs/DATA_SOURCES.md`](docs/DATA_SOURCES.md)
- 第三方分发声明：[`vendor/THIRD_PARTY_LICENSES.md`](vendor/THIRD_PARTY_LICENSES.md)

主要项目包括：

- https://github.com/XingHeYuZhuan/shiguangschedule
- https://github.com/XingHeYuZhuan/shiguang_warehouse
- https://github.com/YZune/WakeUpSchedule
- CSES / ClassIsland ecosystem
- BetterUntis
- AntAlmanac
- QuACS
- 小爱课程表 adapter 生态（研究中的第二适配源；脚本分散且许可证不统一，目前不直接打包）

## 本地开发

需要 Node 22+、Rust stable 和 Tauri 对应平台依赖。

```bash
npm install
npm run desktop:dev
```

前端检查：

```bash
npm run build
npm run check
```

Rust：

```bash
cargo test -p schedule-core -p schedule-import -p schedule-adapter
cargo check -p lumaschedule
```

Android：

```bash
npm install
npx tauri android init
npx tauri icon assets/app-icon.svg
node scripts/prepare-android-ci.mjs
npx tauri android dev
```

生成的 `src-tauri/gen/android` 不提交到仓库；CI 会从零生成并注入原生代码。

## Android 签名

正式发布将使用独立 Upload Key / Play App Signing。GitHub Actions 预留：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
- `ANDROID_STORE_PASSWORD`

普通 PR 真机验证使用 CI 临时测试签名，不把正式私钥提交进仓库。

详见 [`docs/ANDROID_SIGNING.md`](docs/ANDROID_SIGNING.md)。

## 当前优先级

1. 真机跑通 GDUT：搜索 → 登录 → 抓课 → 预览 → 导入 → 周课表。
2. 继续批量验证拾光学校适配器兼容性。
3. 导入 Merge / Diff / Overwrite 和冲突决策。
4. 学期设置、临时调课 / 停课 UI。
5. 日历绑定与 CalDAV。
6. WebDAV 自动同步 / 历史版本。
7. 更多 Android Widget。
8. 第一方正方 / URP / 青果 / 超星通用协议实现，降低对第三方 adapter 的依赖。
9. 评估小爱课程表 adapter 生态的兼容层和许可证可行性。
10. iOS WidgetKit / Live Activity 与桌面端进一步适配。

## License

LumaSchedule 本体默认使用 Apache-2.0。第三方 adapter、数据、协议实现和参考项目继续遵循各自许可证和署名要求。
