# LumaSchedule

> 一个开源、无广告、Local-first 的跨平台课程表软件。Rust 负责课程模型、导入、冲突、存储和适配器安全边界；Svelte/Tauri 负责跨平台 UI；Kotlin/Swift 只承担 Widget、通知等系统原生能力。

当前仓库是 **v0.1 可编译施工版**，不是截图原型。除了 Liquid Glass UI、Rust/SQLite、Android Widget/后台提醒外，本轮已经补上完整数据备份/恢复、WebDAV、拾光全学校适配器同步与 Android 原生教务 WebView 兼容层。

## 设计原则

- **100% 开源、无广告**：本体 Apache-2.0，不接广告 SDK。
- **Local-first**：没有账号也能完整使用，云同步永远可选。
- **导入生态优先**：高校适配器、拾光兼容层、WakeUp/JSON、CSES、ICS、CSV/TSV 等最终落到同一规范模型。
- **系统集成是一等功能**：通知、桌面小组件、课程自动化、日历绑定不是“以后再加的小功能”。
- **跨平台但不强行同质化**：Rust/Core 与设计语言共享；Android/iOS/桌面保留原生能力。
- **第三方适配器默认不可信**：声明域名、能力白名单、哈希、版本与许可证，导入 WebView 与本地文件系统隔离。

## 技术栈

- Tauri 2
- Rust workspace
- Svelte 5 + Vite 8
- SQLite (`rusqlite`, bundled)
- Tauri Notification Plugin
- Android Kotlin `RemoteViews` / `AlarmManager`
- GitHub Actions：CI、Desktop Release、Android APK/AAB

## 工程结构

```text
assets/                      App icon source
crates/
  schedule-core/             Canonical model / WeekMask / conflicts / reminders
  schedule-import/           JSON / WakeUp-like / ICS / CSV / TSV / CSES
  schedule-adapter/          Adapter manifest / capability / origin policy
src/                         Svelte UI
src-tauri/                   Tauri commands + SQLite + native bridge
native/android/              Widget / reminder native sources
scripts/                     Android patcher + Shiguang full-repo sync
.github/workflows/           CI / Desktop / Android / adapter daily sync
```

## 已经落地的 UI

- 今日页：下一节课、进度、教室、时间线、临时调整卡片
- 完整周课表：7 天 × 节次网格、课程块、当前时间标记
- 导入中心：拖放/选择文件、Rust 解析、导入预览、事务新建课表
- 小组件中心：2×1 / 4×2 / 5×3 / 5×4 产品形态预览
- 设置：Liquid Glass 模糊、透明度、饱和度、高光、折射、噪点、动态效果
- 即时通知测试 + Android 后台提醒测试
- Desktop sidebar + Mobile bottom nav
- 系统浅色/深色模式

Liquid Glass 只用于导航、弹层、强调卡片和浮动表面；周课表主体保持可读，不把所有课程块都做成高模糊玻璃。

## Rust Core 已实现

- `Term`
- `Schedule`
- `TimeScheme` / `SectionTime`
- `Course`
- `CourseMeeting`
- `CourseException`
- `ReminderRule`
- `CourseAutomation`
- `WeekMask(u64)`
- 课程重叠/冲突检测
- `ImportBundle` 统一导入模型

SQLite 当前包含：学期、时间方案、课表、课程、上课时段、临时变更、适配器来源、提醒规则、课程自动化、系统日历绑定、同步配置、设置、导入审计。

## 真实导入链路

目前 Rust 导入器支持：

- Canonical JSON
- 拾光风格 JSON 字段
- WakeUp 常见 JSON 字段（`startNode` / `step` / `startWeek` / `endWeek` / 单双周）
- ICS/iCalendar（VEVENT、折行、基础 Weekly RRULE 展开）
- CSV
- TSV
- CSES v1 YAML

导入流程：

```text
File / Adapter
    ↓
Rust parser
    ↓
Canonical ImportBundle
    ↓
Preview
    ↓
SQLite transaction
    ↓
Term / Schedule / Course / Meeting
```

当前提交策略先实现“新建课表”，后续在同一事务层继续增加“合并 / 覆盖 / Diff 冲突决策”，不会改变导入协议。

> XLS/XLSX/HTML 尚未在 v0.1 Bootstrap 中实现解析；UI 和统一导入接口已经为它们预留入口。

## 高校适配器

`crates/schedule-adapter` 提供 Manifest、能力声明与 origin 边界；`src-tauri/src/shiguang.rs` 与 Android 原生 WebView 已实现拾光兼容运行时。

项目会完整镜像 `XingHeYuZhuan/shiguang_warehouse` 的 `index/`、`resources/` 与 MIT `LICENSE`，并由 GitHub Actions 每日更新；构建时也会重新同步，因此其他学校不需要逐个硬编码进主程序。离线时自动回退随 App 打包的最后一次 snapshot。

当前兼容 Bridge 覆盖 `showAlert`、`showPrompt`、`showSingleSelection`、`showToast`、`saveImportedCourses`、`saveCourseConfig`、`savePresetTimeSlots`、`notifyTaskCompletion`，同时提供旧版 `AndroidBridge*` 别名。GDUT 是首个端到端真机验收目标。

为了覆盖老旧高校系统，HTTP 教务也可运行，但只有当对应适配器明确声明 HTTP 时才开放同机构域名，并在登录会话中显示明文传输风险提示；HTTPS 仍是默认。

第三方 adapter 文件保留上游 maintainer 与许可证；LumaSchedule 本体仍为 Apache-2.0。详见 [`docs/ADAPTER_RUNTIME.md`](docs/ADAPTER_RUNTIME.md)。


## 数据导出、完整备份与 WebDAV

当前设置页已经接通真实 Rust/SQLite 数据链路：

- 导出 LumaSchedule JSON；
- 导出标准 ICS；
- 导出 / 恢复完整 `.luma.json` 数据快照；
- 完整快照包含课程、作息、异常调课、提醒、自动化、日历绑定、同步配置、设置与导入审计；
- WebDAV `PROPFIND` 测试、`MKCOL` 建目录、`PUT` 备份、`GET` 恢复；
- 公网 WebDAV 强制 HTTPS；局域网 / localhost 才允许 HTTP；
- WebDAV 密码不写入 SQLite，仅当前会话使用。

当前 WebDAV 是“手动完整备份 / 恢复”闭环；自动周期同步、历史版本与双向冲突合并属于下一阶段。

## Android Widget：已经是原生链路

当前已有：

- `NextCourseWidgetProvider.kt`
- 2×1 `RemoteViews` Widget
- SharedPreferences Snapshot contract
- `ScheduleNativePlugin.kt` Tauri Kotlin 插件
- Svelte → Rust command → Kotlin → Widget 广播刷新

App 启动时会优先读取最近一次 SQLite 课表，并向原生层发布下一节课 Snapshot；数据库为空或纯浏览器预览时才回退到演示数据。

## Android 后台提醒

当前已有：

- Rust `ReminderRule` 数据结构 + SQLite `reminder_rules`
- `ScheduleNativePlugin.scheduleReminder`
- `AlarmManager.setAndAllowWhileIdle`
- `ReminderReceiver`
- Android 8+ Notification Channel
- 原生提醒快照持久化
- `BOOT_COMPLETED / MY_PACKAGE_REPLACED` 自动恢复未来提醒
- 设置页“1 分钟后后台提醒”测试入口

这条链路支持 App 退到后台、进程被回收以及设备重启/应用更新后的未来提醒恢复。下一阶段补：整学期批量调度、节假日/临时调课重算、可选精确闹钟模式。

## 本地开发

需要 Node 22+、Rust stable 和 Tauri 桌面依赖：

```bash
npm install
npm run desktop:dev
```

前端：

```bash
npm run dev
```

Rust tests：

```bash
cargo test -p schedule-core -p schedule-import -p schedule-adapter
```

## Android

本地想进 Android Studio/真机时：

```bash
npm install
npx tauri android init
npx tauri icon assets/app-icon.svg
node scripts/prepare-android-ci.mjs
npx tauri android dev
```

生成的 `src-tauri/gen/android` 不需要入库。CI 会从零生成、注入 Widget/Reminder 原生代码、生成图标并打包。

## GitHub Actions 与 Android 签名

Android release workflow 使用以下 GitHub Secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
- `ANDROID_STORE_PASSWORD`

Tag / 手动工作流会还原 keystore、生成 `keystore.properties`，最终产出 APK + AAB。普通 push / PR 则产生 Debug APK，不要求签名 Secrets。

详见 [`docs/ANDROID_SIGNING.md`](docs/ANDROID_SIGNING.md)。

## CI

- **CI**：Svelte build/check + Rust core tests + Tauri `cargo check`
- **Android**：从零 `tauri android init` → app icon → native patch → Debug APK / Signed APK+AAB
- **Desktop Release**：Windows / Ubuntu / macOS，通过 `tauri-action`

## 接下来施工顺序

1. 用 GitHub Actions + Android 真机把 GDUT 端到端导入验收跑通，并按失败日志补兼容。
2. 导入 Diff / merge / overwrite 与逐项冲突决策 UI。
3. XLS/XLSX/HTML 导入与 WakeUp 更严格双向兼容导出。
4. 拾光 adapter 批量兼容测试 + snapshot 签名信任根。
5. 正方 / URP / 青果 / 超星 / Wisedu / EAMS first-party 通用协议。
6. 整学期提醒调度、节假日重算、常驻“下一节课”通知。
7. WebDAV 自动同步 / 历史版本 + CalDAV / 系统日历。
8. 更多 Android Widget；iOS WidgetKit / Live Activity。
9. Desktop tray / compact floating schedule。

## License

本仓库代码默认使用 Apache-2.0，除非文件另有声明。任何第三方适配器、协议实现或参考项目继续遵循其各自许可证、署名和分发要求。
