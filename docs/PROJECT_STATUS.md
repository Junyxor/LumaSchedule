# LumaSchedule v0.1 状态

> 仓库：`Junyxor/LumaSchedule` · 应用 ID：`com.lumaschedule.app`

## 已经落地

### 核心与 UI
- [x] Svelte 5 + Tauri 2 跨平台壳
- [x] Responsive desktop/mobile layout
- [x] Liquid Glass 参数实时控制
- [x] Rust canonical schedule model
- [x] `WeekMask(u64)` 与课程冲突判断
- [x] SQLite 本地数据库
- [x] SQLite-backed Today / Week，空库时才回退 Demo

### 导入 / 导出
- [x] JSON / WakeUp-like / 拾光字段归一化导入
- [x] ICS 导入 +基础 Weekly RRULE 展开
- [x] CSV / TSV 导入
- [x] CSES v1 YAML 导入
- [x] 导入预览 + 新建课表事务提交
- [x] LumaSchedule JSON 导出
- [x] ICS 导出（按实际周次展开为 VEVENT）
- [x] 完整 `.luma.json` 数据快照导出 / 恢复
- [x] 完整快照包含课程、作息、异常调课、提醒、自动化、日历绑定、同步配置、设置与导入审计

### WebDAV
- [x] WebDAV `PROPFIND` 连接测试
- [x] 递归 `MKCOL` 创建备份目录
- [x] `PUT` 上传完整备份
- [x] `GET` 下载并事务恢复完整备份
- [x] 公网强制 HTTPS；HTTP 只允许 localhost / 局域网地址
- [x] WebDAV 密码不落 SQLite，仅当前会话使用

### 高校适配器
- [x] 拾光 `shiguang_warehouse` 根索引 / 学校 / adapter manifest 读取
- [x] 上游实时读取失败时回退应用内置 adapter snapshot
- [x] GitHub Actions 每日同步整个 `index/ + resources/ + LICENSE`
- [x] 构建时重新拉取整个适配仓库，APK / desktop bundle 内置当前快照
- [x] 保留上游 MIT LICENSE、maintainer 字段与来源说明
- [x] 官方 Bridge 兼容：`showAlert` / `showPrompt` / `showSingleSelection` / `showToast`
- [x] 官方 Bridge 兼容：`saveImportedCourses` / `saveCourseConfig` / `savePresetTimeSlots` / `notifyTaskCompletion`
- [x] 旧接口别名：`AndroidBridgePromise` / `AndroidBridge`
- [x] Android 原生登录 WebView，Cookie 与 adapter JS 同 WebView 生命周期
- [x] Android WebView 禁用文件 / content 访问
- [x] Android navigation + subresource/fetch 请求域名限制
- [x] 兼容旧教务 HTTP：仅适配器声明时开放对应机构域名，并在 UI 显示明文传输风险警告
- [x] adapter 脚本不能通过源码里的任意 URL 自行扩大域名权限，只接受登录 / SSO 声明的同机构域名
- [x] adapter 输出进入统一 `ImportBundle` 预览，不直接写数据库
- [x] GDUT adapter 已作为首个真实目标接入，并增加本地 override（SSO 会话探测 + 常驻手动读取按钮）；实际账号登录仍需真机验收

### Android 系统能力
- [x] 2×1 RemoteViews 下一节课 Widget
- [x] Svelte → Rust → Kotlin → Widget 快照桥
- [x] AlarmManager 后台课程提醒
- [x] BOOT_COMPLETED / MY_PACKAGE_REPLACED 后恢复未来提醒
- [x] GitHub Actions Debug APK
- [x] GitHub Actions Release APK + AAB / Secrets 签名

## 仍需继续

- [ ] Merge / overwrite 导入 Diff 与逐项冲突决策
- [ ] XLS / XLSX（calamine）与通用 HTML 导入
- [ ] WakeUp 分享文本 / JSON 的更严格双向导出兼容
- [ ] 拾光适配器兼容性批量测试矩阵（不仅是 Bridge API 覆盖）
- [ ] adapter snapshot 签名 / 信任根（当前有 SHA-256 会话指纹 + 沙箱，但尚无仓库签名验证）
- [ ] 正方 / URP / 青果 / 超星 / Wisedu / EAMS first-party 通用协议实现
- [ ] 整学期提醒批量调度、节假日和临时调课重算
- [ ] 常驻“下一节课”通知、更多 Android Widget
- [ ] WebDAV 自动周期同步 / 冲突版本历史（当前是手动完整备份与恢复）
- [ ] CalDAV / 系统日历双向同步
- [ ] iOS WidgetKit / Live Activity
- [ ] Desktop tray / compact mode

## 当前环境验证限制

本生成容器有 Node，但无法访问 npm registry，且没有 Rust toolchain，所以无法在容器内完成 `npm install` / `cargo check` / Gradle APK 真编译。仓库 CI 和 Android Actions 是权威编译门禁；代码推送后按 Actions 日志继续修到通过。
