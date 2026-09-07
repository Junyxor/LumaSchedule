# LumaSchedule · 流光课表

> 开源、无广告、Local-first 的 Android 课程表应用。当前主线使用 **Svelte 5 + 极薄 Kotlin Native Core**：界面运行在系统 WebView，SQLite、通知、课程提醒、Widget、文件、WebDAV 与高校教务登录直接使用 Android / JVM 平台能力。

**Repository:** https://github.com/Junyxor/LumaSchedule  
**License:** Apache-2.0  
**Current stage:** v0.1 真机迭代中  
**Android:** 8.0+ / API 26+

Rust/Tauri 版本没有删除，完整保存在 [`archive/rust-tauri-v0.1`](https://github.com/Junyxor/LumaSchedule/tree/archive/rust-tauri-v0.1) 分支；当前 Android 主线不再编译或运行 Rust、Tauri、NDK。

## 为什么改成 Native Core

早期 Tauri/Rust Release APK 已经从错误的约 700 MB Universal Debug 压到约 13.1 MiB，但拆包后发现其中约 9.5 MiB 是 Rust/Tauri `.so`。对于几十到几百条课程数据，Rust 的计算优势几乎不会转化成用户可感知的收益，反而增加了一层运行时、JNI/IPC 路径和包体。

当前 Native 架构：

```text
Svelte 5 / Liquid Glass UI
          │
          │ async JavaScriptInterface
          ▼
Kotlin Native Core
├─ SQLiteDatabase
├─ AlarmManager / NotificationManager
├─ AppWidget / BroadcastReceiver
├─ Android Storage Access Framework
├─ WebDAV transport
└─ isolated School WebView
```

Android Runtime **没有**引入 Compose、AndroidX、Room、Retrofit、OkHttp、Flutter、React Native 或其他大型运行时 SDK。

## 包体与性能

当前已通过 GitHub Actions 的首个“完整 Native”基线包包含：

- Svelte UI
- Kotlin Native Core
- SQLite 课表数据库
- 手动课程 CRUD
- Android 通知 / 整学期课程提醒
- 下一节课 Widget
- 拾光全量适配仓库快照
- 高校搜索 / Adapter / 隔离教务 WebView 运行时

实测：

- **APK：1,122,429 bytes ≈ 1.07 MiB**
- **无任何 `lib/*.so` / native shared library**
- 同次构建同步：**199 个资源目录 / 210 个 Adapter**

上述数字是已通过的基线构建，不保证未来每个提交完全相同；增加真实功能后继续以 CI 的 `Report APK size` 为准。

性能策略：

- 主 WebView 强制硬件加速
- UI 使用本地虚拟 HTTPS Origin：`https://app.luma.local/`
- JS/CSS 直接从 APK Assets 提供，静态资源使用 immutable cache
- 主 WebView 禁止任意远程资源；学校网络只进入独立教务 WebView
- Native Bridge 全部异步，SQLite / 网络任务使用专用 I/O 线程池
- SQLite WAL + `synchronous=NORMAL` + 常用索引 + `PRAGMA optimize`
- R8 + Android Resource Shrinking
- 学校索引本地读取并内存缓存，不把搜索首开速度交给 GitHub 网络
- Liquid Glass 只用于 Tab Bar、搜索、Sheet、重点浮层，避免满屏 `backdrop-filter` 消耗 GPU

项目会继续同时关注 **冷启动、页面帧率、内存、数据库查询和 APK 大小**，不是只追求安装包数字。

## 当前真实功能

### 课表

- 今日课程 / 下一节课
- 7 天周课表
- 当前教学周计算
- 单双周 / 指定周过滤
- 学期外自动清空当前周视图
- 学校作息节次 → 实际时间映射
- 动态节次数，覆盖广东工业大学 14 节等场景
- 手动新增课程
- 点击课程直接编辑
- 修改课程名、教师、教室、星期、节次、具体时间、上课周次
- 删除单个上课时段
- 数据库为空时展示真实空状态，不使用演示课冒充用户数据

### 文件导入

统一数据模型为 `ImportBundle`，当前 Native 主线支持：

- LumaSchedule / 通用 JSON
- WakeUp 常见 JSON 字段
- ICS / iCalendar
- CSV
- TSV
- CSES v1 YAML

流程：

```text
File / School Adapter
        ↓
WebView lightweight parser / Kotlin school runtime
        ↓
Canonical ImportBundle
        ↓
Preview
        ↓
SQLite transaction
        ↓
Term / Schedule / Course / Meeting
```

**暂未实现：XLS/XLSX、任意 HTML 自动识别。** 未实现功能不会在正式 UI 中伪装成可用入口。

### 高校教务自动导入

主要适配数据来源：

- https://github.com/XingHeYuZhuan/shiguang_warehouse
- 上游应用：https://github.com/XingHeYuZhuan/shiguangschedule

GitHub Actions 构建时同步完整 Adapter 仓库并随 APK 打包快照，因此：

- 学校列表可以本地秒开
- 离线仍能看到最近构建时的适配目录
- 不需要把每所学校硬编码进 LumaSchedule
- 全量 Adapter 对 APK 体积影响很小

流程：

```text
搜索学校
  ↓
选择具体学校 / 通用教务入口
  ↓
选择 Adapter
  ↓
隔离 WebView 打开学校官方登录系统
  ↓
用户自行登录
  ↓
Adapter 获取课程 / 作息 / 学期配置
  ↓
LumaSchedule 导入预览
  ↓
确认写入 SQLite
```

除具体学校外，上游还包含通用入口：

- 正方教务
- 超星教务
- 青果教务
- URP 教务

广东工业大学 `GDUT_01` 是当前首个重点真机 E2E 验收目标。

### 通知与课程提醒

课程提醒直接由 Kotlin 读取 SQLite 并计算未来课程：

- 用户主动开启后才申请通知权限
- 提前 5 / 10 / 15 / 20 / 30 / 60 分钟
- 使用学期开始日期、周次、星期、显式时间 / 学校作息表计算触发时间
- 课程新增、编辑、删除、重新导入后自动增量重排
- 只新增变化的 Alarm，取消已经过期或不再需要的 Alarm
- `AlarmManager.setAndAllowWhileIdle`
- Android Notification Channel
- `BOOT_COMPLETED / MY_PACKAGE_REPLACED` 后恢复未来提醒
- 即时测试通知和 1 分钟后台测试

不需要常驻后台 Service。

### Widget

当前真正实现：

- Android 原生 `RemoteViews`
- 下一节课 Widget
- App → Kotlin → SharedPreferences Snapshot → AppWidget 更新
- 课表清空时主动覆盖旧内容

更多 Widget 尺寸属于后续功能，不在 v0.1 中伪装成已完成。

### Liquid Glass / Apple-like UI

移动端视觉原则：

- 内容层保持清晰、克制
- 底部 Tab Bar、搜索、Bottom Sheet、重点浮层才使用 Liquid Glass
- 模糊、透明度、饱和度、高光、折射、噪点实时可调
- 底部 Tab Bar 固定在屏幕安全区底部
- 禁止主 App 网页式双指缩放
- 深色模式同步适配

## 数据导出、完整备份与 WebDAV

Native 主线已经迁移的数据能力：

- LumaSchedule JSON 导出
- ICS / iCalendar 导出
- 完整 `.luma.json` 数据库快照
- 完整备份恢复
- Android Storage Access Framework 文件选择 / 另存为
- WebDAV `PROPFIND / MKCOL / PUT / GET`
- 公网 WebDAV 强制 HTTPS
- localhost / 私有局域网允许 HTTP
- TLS 使用 Android/JVM 系统 Trust Store 与 HTTPS 主机名校验
- WebDAV 密码只存在当前运行内，不写 SQLite

当前 WebDAV 是**手动完整备份 / 恢复**，不是双向实时同步。自动同步、历史版本和冲突合并属于后续阶段。

## SQLite 数据模型

当前数据库保持稳定 schema：

- `terms`
- `time_schemes`
- `schedules`
- `courses`
- `course_meetings`
- `course_exceptions`
- `adapter_sources`
- `reminder_rules`
- `course_automations`
- `calendar_bindings`
- `sync_profiles`
- `settings`
- `import_audit`

课程周次继续使用 64-bit WeekMask 存储；前端和 Adapter 对外使用周次数组，平台实现不绑定某一种语言。

## 安全边界

第三方高校 Adapter 默认不可信：

- 主 App WebView 使用独立本地 Origin，并拦截任何未知网络请求
- 主 App 禁止文件 / content 访问
- 教务系统运行在独立 `ShiguangImportActivity`
- Adapter 只允许声明过的教务域名 / 同机构受控子域
- 第三方脚本不能访问 App SQLite、文件系统或 Shell
- Bridge 只暴露导入课程所需的有限方法
- Adapter 明确声明旧 HTTP 登录时才进入兼容路径，并向用户显示风险提示
- 导入结果先预览，确认后才写数据库

详见 [`docs/ADAPTER_RUNTIME.md`](docs/ADAPTER_RUNTIME.md)。

## 工程结构

```text
android-native/               无 Rust Android App / Kotlin Native Core
  app/src/main/java/
    data/                     SQLite + 导出 / 完整备份
    reminders/                整学期提醒调度
    shiguang/                 学校目录 / Adapter 会话
    sync/                     WebDAV
    widgets/                  Widget / Receiver
src/                          Svelte 5 UI
  lib/nativeBridge.ts         异步 JS ↔ Kotlin Bridge
  lib/importers.ts            JSON / ICS / CSV / TSV / CSES 解析
native/android/shiguang/      隔离教务 WebView
native/android/res/           Widget XML / RemoteViews 资源
scripts/                      拾光 Adapter 同步
vendor/                       构建时 Adapter 快照与第三方声明
.github/workflows/            Native Android / frontend CI / adapter sync
```

Rust/Tauri 历史实现见归档分支，不是当前 Android 构建依赖。

## 本地开发

需要：

- Node.js 22+
- JDK 17
- Android SDK 36
- Gradle 8.9+

前端：

```bash
npm install
npm run dev
```

检查：

```bash
npm run build
npm run check
```

本地 Native Android Release：

```bash
npm install
npm run build
gradle -p android-native :app:assembleRelease
```

无需 Rust、Cargo、Tauri CLI 或 Android NDK。

## Android 签名

正式发布使用独立 Upload Key / Play App Signing。GitHub Actions 预留：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
- `ANDROID_STORE_PASSWORD`

普通 PR 真机验证使用 CI 临时测试签名，不把正式私钥提交进仓库。

详见 [`docs/ANDROID_SIGNING.md`](docs/ANDROID_SIGNING.md)。

## 参考项目与数据来源

LumaSchedule 会参考成熟开源项目的**产品交互、协议兼容和架构经验**，不会因为“开源”就复制许可证不兼容的实现代码。

- [参考项目与许可证边界](docs/REFERENCE_PROJECTS.md)
- [数据来源与更新策略](docs/DATA_SOURCES.md)
- [第三方分发声明](vendor/THIRD_PARTY_LICENSES.md)

主要参考 / 兼容生态：

- [拾光课程表](https://github.com/XingHeYuZhuan/shiguangschedule)
- [拾光适配仓库](https://github.com/XingHeYuZhuan/shiguang_warehouse)
- [WakeUp Schedule](https://github.com/YZune/WakeUpSchedule)
- CSES / ClassIsland ecosystem
- BetterUntis
- AntAlmanac
- QuACS
- 小爱课程表 Adapter 生态（研究中的第二适配源；脚本分散且许可证不统一，目前不直接打包）

## 当前优先级

1. 真机跑通 GDUT：搜索 → `GDUT_01` → 登录 → 抓课 → 预览 → 导入 → 周课表。
2. 完成 Native 架构的真机通知、课程提醒、Widget、导出/恢复、WebDAV 回归测试。
3. 移除活动分支残留的 Rust/Tauri 文件；归档分支继续永久保留。
4. 导入 Merge / Diff / Overwrite 和冲突决策。
5. 学期设置、切周 / 快速跳周、临时调课 / 停课。
6. 节假日过滤、勿扰 / 静音课程自动化。
7. WebDAV 自动同步 / 历史版本与 CalDAV / 系统日历绑定。
8. 更多 Android Widget。
9. 批量验证拾光 Adapter，并研究小爱课程表生态作为第二适配来源。
10. Android v0.1 稳定后再讨论 iOS / Desktop，不为尚未落地的平台拖累 Android 包体与性能。

## License

LumaSchedule 本体默认使用 Apache-2.0。第三方 Adapter、数据、协议实现和参考项目继续遵循各自许可证与署名要求。
