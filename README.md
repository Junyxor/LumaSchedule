# LumaSchedule · 流光课表

开源、无广告、Local-first 的 Android 课程表应用。

LumaSchedule 面向高校日常课表使用，重点提供 **高校教务导入、周课表、成绩与绩点、课程提醒、小组件、备份与多格式导入导出**，同时尽量保持较小的安装包和流畅的移动端体验。

- Android 8.0+（API 26+）
- Apache-2.0 License
- Svelte 5 + Kotlin Native Core

## 功能

### 课表

- 今日课程与下一节课
- 周课表、前后周切换与快速跳周
- 单双周 / 指定周次课程
- 智能周末显示，也可手动选择工作日、周六、周日或完整周末
- 自定义学期、开学日期、总周数、每周起始日和节次数
- 手动新增、编辑、删除课程
- 教师、教室、时间等显示项可配置

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

- 课程开始前提醒
- Android 原生通知与课程提醒调度
- 下一节课桌面小组件
- 课程或课表变更后自动刷新相关提醒

### 导入、导出与备份

支持：

- LumaSchedule / 通用 JSON
- WakeUp 常见 JSON 字段
- ICS / iCalendar
- CSV / TSV
- CSES v1 YAML
- 完整 `.luma.json` 备份与恢复
- WebDAV 手动备份 / 恢复

## 隐私与安全

LumaSchedule 以本地数据为主：课程、成绩和设置保存在设备本地数据库中。

高校登录页面运行在独立 WebView 中，第三方 Adapter 不能直接访问主应用数据库、文件系统或 Shell。导入数据会先进入预览流程，确认后才写入本地课表。

更多实现细节见 [Adapter Runtime](docs/ADAPTER_RUNTIME.md)。

## 界面

LumaSchedule 使用可调节的 Liquid Glass 风格，并针对 Android WebView 控制模糊、透明度、饱和度、高光和动画开销。设置页可以恢复官方默认玻璃参数。

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
npm run dev
```

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
