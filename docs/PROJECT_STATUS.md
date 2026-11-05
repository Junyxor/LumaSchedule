# LumaSchedule 状态

> 仓库：`Junyxor/LumaSchedule` · 应用 ID：`com.lumaschedule.app`

## 已经落地

### 核心与 UI
- [x] Svelte 5（runes）前端 + Kotlin 原生层，Android WebView 加载打包在 APK 内的界面
- [x] 统一设计系统（`src/styles/tokens.css`）：浅色 / 深色 / 跟随系统，7 种主题色，可选多彩课程
- [x] 手机底部导航、平板与横屏左侧导航
- [x] 系统返回键先关闭弹窗、返回上一级；原生注入状态栏 / 导航栏 / 键盘安全区
- [x] SQLite 本地数据库（`PRAGMA user_version` 迁移，单例连接与统一锁）
- [x] 多课表：新建、切换、重命名、删除；`WeekMask(u64)` 周次与冲突判断
- [x] 作息时间编辑；未设置时按课程时间推算
- [x] 周次规则前端与原生一致：第 1 周是开学日期所在的那一周（周一或周日开始）

### 导入 / 导出
- [x] JSON / WakeUp-like / 拾光字段归一化导入
- [x] WakeUp `.wakeup_schedule` 备份文件导入
- [x] ICS 导入：RRULE（WEEKLY / DAILY、COUNT、UNTIL、INTERVAL、BYDAY）、EXDATE、UTC 时间，按周次合并
- [x] CSV / TSV 导入（中文表头、BOM、GBK 编码）
- [x] CSES v1 YAML 导入
- [x] 导入预览：逐条勾选、差异统计、合并 / 覆盖 / 新建课表
- [x] LumaSchedule JSON 导出（含作息时间、学期配置、学分，可完整导回）
- [x] ICS 导出（按实际周次展开为 UTC VEVENT，按 RFC 5545 折行）
- [x] 完整 `.luma.json` 数据快照导出 / 恢复，兼容旧格式备份
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
- [x] RemoteViews「下一节课」Widget：原生读取 SQLite，上课 / 下课时自动刷新，支持深色模式
- [x] AlarmManager 课程提醒：滚动安排未来 14 天（最多 160 个），提醒触发、开机、应用更新、改时间 / 时区后自动续排
- [x] 已安排的提醒 ID 记录在设备本地，恢复其他设备的备份不会留下孤儿提醒
- [x] Android 15+ 强制全面屏下，教务登录窗口避开状态栏、导航栏和键盘
- [x] GitHub Actions Debug APK
- [x] GitHub Actions Release APK + AAB / Secrets 签名

## 仍需继续

- [ ] 合并导入时逐项冲突决策（当前合并会保留两边课程并提示冲突数量）
- [ ] XLS / XLSX 与通用 HTML 导入
- [ ] WakeUp 分享口令（需要 WakeUp 服务端，当前只支持导出的文件）
- [ ] 拾光适配器兼容性批量测试矩阵（不仅是 Bridge API 覆盖）
- [ ] adapter snapshot 签名 / 信任根（当前有 SHA-256 会话指纹 + 沙箱，但尚无仓库签名验证）
- [ ] 正方 / URP / 青果 / 超星 / Wisedu / EAMS first-party 通用协议实现
- [ ] 节假日和临时调课
- [ ] 常驻“下一节课”通知、更多尺寸的 Android Widget
- [ ] WebDAV 自动周期同步 / 冲突版本历史（当前是手动完整备份与恢复）
- [ ] CalDAV / 系统日历双向同步
