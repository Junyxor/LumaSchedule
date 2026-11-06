# LumaSchedule · 流光课表

开源、无广告、Local-first 的 Android 课程表应用。

LumaSchedule 面向高校日常课表使用，重点提供 **高校教务导入、周课表、成绩与绩点、课程提醒、小组件、备份与多格式导入导出**，同时尽量保持较小的安装包和流畅的移动端体验。

- Android 8.0+（API 26+）
- Apache-2.0 License
- Svelte 5 + Kotlin Native Core

## 功能

### 课表

- 今日：正在上的课、下一节课倒计时、今天与明天的课程
- 周课表：按真实上课时间排布，左右滑动或点周次快速跳周，点空白格子添加课程
- 单双周 / 指定周次课程，同一时间段的冲突课程并排显示
- 智能周末显示，也可手动选择工作日、周六、周日或完整一周
- 学期设置：开学日期、总周数、每周起始日（周一 / 周日）、时区
- 作息时间：逐节设置上下课时间，或按规则一键生成
- 多份课表：新建、切换、重命名、删除，导入时可选择新建课表
- 手动新增、编辑、删除课程，可设置学分和课程颜色
- 教师、教室、时间、紧凑模式等显示项可配置

### 高校教务导入

- 搜索高校并选择对应 Adapter
- 在隔离的教务 WebView 中登录学校官方系统
- 导入前预览课程数据
- 支持新建、合并、覆盖等导入策略
- 提供正方、URP、青果、超星等通用兼容入口
- 学校未收录时可粘贴官方教务系统 URL 尝试兼容模式

高校适配主要使用 [拾光课程表适配仓库](https://github.com/XingHeYuZhuan/shiguang_warehouse) 的公开 Adapter 数据，并在构建时打包本地快照。

LumaSchedule 也兼容部分 WakeUp 常见课表数据和学校配置格式；WakeUp 的服务端解析能力不属于本项目，因此不宣称与 WakeUp 支持学校完全等价。

### 成绩与绩点

- 本地保存原始成绩、学分和教务来源绩点
- 按学期和课程搜索筛选
- 平均成绩与平均绩点统计
- 有学分时按学分加权
- 各学期趋势与成绩 / 绩点分布
- 不强制使用统一 GPA 换算表，优先保留学校教务提供的数据
- 支持通过学校官方教务 URL 打开登录窗口并尝试抓取成绩表

### 提醒与小组件

- 课程开始前提醒，由 Android 系统闹钟触发，不需要应用常驻后台
- 只滚动安排未来两周的提醒，避开 Android 单应用约 500 个闹钟的上限；开机、改时间、提醒触发后自动续排
- 「下一节课」桌面小组件在原生层直接读取课表，上课和下课时自动更新，支持深色模式
- 课程或课表变更后自动刷新提醒和小组件
- 还没设置作息时间的课程按常见作息估算时间，应用、提醒和小组件显示同一个时间，并提示去设置

### 导入、导出与备份

支持：

- LumaSchedule / 通用 JSON（导出的 JSON 可以完整导回，包括作息时间和学分）
- WakeUp「导出为文件」得到的 `.wakeup_schedule` 备份，以及常见 WakeUp JSON 字段
- ICS / iCalendar（支持 RRULE、EXDATE 和 UTC 时间；本应用导出的日历可以原样导回，包括周日起始的周次）
- CSV / TSV（自动识别中文表头和 GBK 编码）
- CSES v1 YAML
- 导出 ICS 到系统日历
- 完整 `.luma.json` 备份与恢复（可恢复旧版本备份）
- WebDAV 手动备份 / 恢复

## 隐私与安全

LumaSchedule 以本地数据为主：课程、成绩和设置保存在设备本地数据库中。

高校登录页面运行在独立 WebView 中，第三方 Adapter 不能直接访问主应用数据库、文件系统或 Shell。导入数据会先进入预览流程，确认后才写入本地课表。

更多实现细节见 [Adapter Runtime](docs/ADAPTER_RUNTIME.md)。

## 界面

界面以实用为先：扁平的卡片和分组列表、清晰的字号层级（最小 11px）、细分隔线，只在浮层上使用阴影。

- 浅色 / 深色 / 跟随系统，深色模式同步状态栏和导航栏
- 7 种主题色
- 课程卡默认统一配色，也可以开启「多彩课程」或给单门课程指定颜色
- 适配刘海屏、手势导航和键盘弹出；系统返回键先关闭弹窗、返回上一级

## 下载

正式版本请从仓库的 **Releases** 页面获取。

测试构建可能使用临时签名，无法覆盖安装正式版本；公开版本始终使用固定的长期 Android 发布签名。

## 本地开发

需要：

- Node.js 22+
- JDK 17
- Android SDK 36
- Gradle 8.9+

前端：

```bash
npm install
npm run dev        # 浏览器预览，自动使用内置模拟数据
```

在没有 Android Bridge 的浏览器里，`npm run dev` 和 `npm run build:mock`（输出到 `dist-mock/`）会加载 `src/lib/native/mock.ts` 的模拟数据，方便在桌面调试界面；正式构建不包含这部分代码。浏览器控制台执行 `lumaMockReset()` 可以重置模拟数据。

检查：

```bash
npm run build
npm run check
```

Android Release 构建：

```bash
npm install
npm run build
gradle -p android-native :app:assembleRelease
```

Android 主线不需要 Rust、Cargo、Tauri CLI 或 Android NDK。

## 项目文档

- [正式发布与 Android 签名](docs/RELEASE.md)
- [高校 Adapter 运行边界](docs/ADAPTER_RUNTIME.md)
- [参考项目与许可证边界](docs/REFERENCE_PROJECTS.md)
- [数据来源与更新策略](docs/DATA_SOURCES.md)
- [第三方声明](vendor/THIRD_PARTY_LICENSES.md)

## 参与贡献

欢迎提交 Issue、PR 和高校适配改进。对于新的高校教务支持，优先采用可维护的 Adapter / Profile 方式，而不是把学校逻辑硬编码进主应用。

## License

[Apache License 2.0](LICENSE)
